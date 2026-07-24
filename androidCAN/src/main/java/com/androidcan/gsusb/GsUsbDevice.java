package com.androidcan.gsusb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.androidcan.AbstractUsbCanDevice;
import com.androidcan.AsyncBulkPump;
import com.androidcan.BitTiming;
import com.androidcan.CanFrame;
import com.androidcan.ReceivedFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Driver for candleLight / gs_usb USB-CAN adapters (Geschwister Schneider and
 * compatible firmware), speaking the same protocol as the Linux {@code gs_usb}
 * kernel driver. Configuration and framing use USB control transfers and two
 * bulk endpoints, all little-endian.
 */
public class GsUsbDevice extends AbstractUsbCanDevice {

    public static boolean isSupported(UsbDevice device) {
        return matches(device, GsUsb.SUPPORTED_DEVICES);
    }

    private UsbEndpoint epIn;
    private UsbEndpoint epOut;

    private int channelCount;
    private int swVersion;
    private int hwVersion;
    /* Bit-timing limits as reported by the device, see negotiate(). */
    private int fclkCan, tseg1Min, tseg1Max, tseg2Min, tseg2Max, sjwMax, brpMin, brpMax, brpInc;

    // Channel settings to reuse when auto-restarting after a bus-off.
    private volatile int currentChannel;
    private volatile BitTiming currentBitTiming;
    private volatile boolean currentListenOnly;

    public GsUsbDevice(UsbManager usbManager, UsbDevice device) {
        super(usbManager, device);
    }

    @Override
    public void open() throws IOException {
        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            throw new IOException("Failed to open USB device — check that permission was granted");
        }
        connection = conn;

        // Any failure past this point must release the connection/interface we
        // just acquired, otherwise a caller that drops the object leaks the USB
        // handle. close() is idempotent and cleans up whatever was set.
        try {
            UsbInterface iface = null;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface candidate = device.getInterface(i);
                if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                    iface = candidate;
                    break;
                }
            }
            if (iface == null) {
                iface = device.getInterface(0);
            }
            usbInterface = iface;

            if (!conn.claimInterface(iface, true)) {
                throw new IOException("Failed to claim USB interface");
            }

            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint ep = iface.getEndpoint(i);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    epIn = ep;
                } else if (ep.getDirection() == UsbConstants.USB_DIR_OUT && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    epOut = ep;
                }
            }
            if (epIn == null) throw new IOException("No bulk IN endpoint found");
            if (epOut == null) throw new IOException("No bulk OUT endpoint found");

            negotiate();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public void start(int bitrate) throws IOException {
        startChannel(0, bitTimingFor(bitrate));
    }

    @Override
    public void stop() throws IOException {
        stopChannel(0);
    }

    @Override
    public String getDeviceInfo() {
        if (connection == null) {
            return "candleLight (not open)";
        }
        return "candleLight  channels:" + channelCount
                + "  sw:" + swVersion
                + "  hw:" + hwVersion;
    }

    /**
     * Solve for bit timing against the clock the device actually reported,
     * aiming at the usual 87.5% sample point and staying inside every limit
     * from bt_const. This mirrors what the kernel's can_calc_bittiming() does
     * for gs_usb; a fixed table cannot work, because the same registers mean
     * different bitrates on boards clocked at 48, 64, 80 or 160 MHz.
     */
    private BitTiming bitTimingFor(int bitrate) throws IOException {
        if (fclkCan <= 0) throw new IOException("Device reported no CAN clock");
        if (bitrate <= 0) throw new IOException("Invalid bitrate: " + bitrate);

        BitTiming best = null;
        double bestErr = Double.MAX_VALUE;
        for (int brp = brpMin; brp <= brpMax; brp += Math.max(1, brpInc)) {
            /* Only exact divisions: an off-by-one tq means an off bitrate, and
             * a CAN bus does not tolerate that the way a UART would. */
            int div = brp * bitrate;
            if (fclkCan % div != 0) continue;
            int tq = fclkCan / div;                   // total quanta per bit
            if (tq < 1 + tseg1Min + tseg2Min || tq > 1 + tseg1Max + tseg2Max) continue;

            int tseg2 = (int) Math.round(tq * 0.125); // 87.5% sample point
            tseg2 = Math.max(tseg2Min, Math.min(tseg2Max, tseg2));
            int tseg1 = tq - 1 - tseg2;
            if (tseg1 < tseg1Min || tseg1 > tseg1Max) continue;

            double err = Math.abs(87.5 - 100.0 * (tq - tseg2) / tq);
            if (err < bestErr) {
                bestErr = err;
                /* gs_usb wants prop_seg and phase_seg1 separately; the kernel
                 * splits tseg1 down the middle, which is what produced the
                 * 6/7 pair this driver used to hard-code for 500 kbit/s. */
                int propSeg = tseg1 / 2;
                best = new BitTiming(propSeg, tseg1 - propSeg, tseg2,
                                     Math.min(1, sjwMax), brp);
            }
        }
        if (best == null) {
            throw new IOException("No bit timing for " + bitrate
                    + " bit/s at fclk " + fclkCan + " Hz");
        }
        Log.i(tag, "bitrate " + bitrate + " -> brp=" + best.brp + " seg="
                + best.propSeg + "/" + best.phaseSeg1 + "/" + best.phaseSeg2
                + " sjw=" + best.sjw + " (sample point off by "
                + String.format("%.2f", bestErr) + "%)");
        return best;
    }

    public void startChannel(int channel, BitTiming bitTiming) throws IOException {
        startChannel(channel, bitTiming, false);
    }

    public void startChannel(int channel, BitTiming bitTiming, boolean listenOnly) throws IOException {
        // Tear down any previously started RX loop so a repeated startChannel()
        // doesn't leave an orphaned thread running against a stale channel.
        stopRxThread();

        currentChannel = channel;
        currentBitTiming = bitTiming;
        currentListenOnly = listenOnly;
        applyChannelConfig(channel, bitTiming, listenOnly);

        startRxThread("gsusb-rx-" + channel, () -> runRxLoop(channel));
    }

    /** Push MODE_RESET, BITTIMING then MODE_START to take the channel on-bus. */
    private void applyChannelConfig(int channel, BitTiming bitTiming, boolean listenOnly) throws IOException {
        UsbDeviceConnection conn = requireConnection();

        /* Off-bus first, and not for tidiness: bit timing only reaches the
         * controller's registers while it is in configuration mode, so writing
         * it to a channel that is still running is silently dropped and the old
         * rate keeps running. A channel is still running more often than it
         * looks - an adapter left open by a killed process, or by an app that
         * was replaced while the service ran, enumerates already started.
         * Resetting also clears the error counters, which is what lets a
         * controller stuck error-passive after a bitrate mismatch come back
         * without a physical replug. MODE_RESET on an already-reset channel is
         * harmless, so this costs one control transfer and no special cases. */
        ByteBuffer resetBuf = ByteBuffer.allocate(GsUsb.MODE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        resetBuf.putInt(GsUsb.MODE_RESET);
        resetBuf.putInt(0);
        controlOut(conn, GsUsb.REQUEST_MODE, channel, 0, resetBuf.array());

        ByteBuffer btBuf = ByteBuffer.allocate(GsUsb.BITTIMING_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        btBuf.putInt(bitTiming.propSeg);
        btBuf.putInt(bitTiming.phaseSeg1);
        btBuf.putInt(bitTiming.phaseSeg2);
        btBuf.putInt(bitTiming.sjw);
        btBuf.putInt(bitTiming.brp);
        controlOut(conn, GsUsb.REQUEST_BITTIMING, channel, 0, btBuf.array());

        ByteBuffer modeBuf = ByteBuffer.allocate(GsUsb.MODE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        modeBuf.putInt(GsUsb.MODE_START);
        modeBuf.putInt(listenOnly ? GsUsb.MODE_FLAG_LISTEN_ONLY : 0);
        controlOut(conn, GsUsb.REQUEST_MODE, channel, 0, modeBuf.array());
    }

    /** Recover from bus-off by re-running BITTIMING + MODE_START on the live channel. */
    @Override
    protected void restartAfterBusOff() throws IOException {
        BitTiming bitTiming = currentBitTiming;
        if (bitTiming == null) return;
        applyChannelConfig(currentChannel, bitTiming, currentListenOnly);
    }

    public void stopChannel(int channel) throws IOException {
        /* Off-bus first, RX thread after - the same order as
         * Usb8DevDevice.stop(), so the two teardowns read alike. That driver
         * needs the order: its CLOSE travels over a bulk endpoint and the
         * firmware stops answering if nothing is draining the data endpoint.
         * Here it is merely harmless, MODE_RESET being a control transfer on
         * endpoint 0, which the device services regardless of what the RX pump
         * is doing - measured over four runs with this adapter in both slots. */
        try {
            if (connection != null) {
                ByteBuffer modeBuf = ByteBuffer.allocate(GsUsb.MODE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                modeBuf.putInt(GsUsb.MODE_RESET);
                modeBuf.putInt(0);
                controlOut(connection, GsUsb.REQUEST_MODE, channel, 0, modeBuf.array());
            }
        } finally {
            stopRxThread();
        }
    }

    public void send(CanFrame frame) throws IOException {
        send(frame, 0, 0);
    }

    public void send(CanFrame frame, int channel, int echoId) throws IOException {
        UsbDeviceConnection conn = requireConnection();
        if (epOut == null) throw new IOException("Device not open");

        ByteBuffer buf = ByteBuffer.allocate(GsUsb.FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(echoId);

        long canId = frame.id & 0xFFFFFFFFL;
        if (frame.isExtended) canId |= GsUsb.CAN_EFF_FLAG;
        if (frame.isRemote) canId |= GsUsb.CAN_RTR_FLAG;
        buf.putInt((int) canId);

        buf.put((byte) frame.data.length);
        buf.put((byte) channel);
        buf.put((byte) 0);  // flags
        buf.put((byte) 0);  // reserved

        buf.put(padTo8(frame.data));

        int result = conn.bulkTransfer(epOut, buf.array(), GsUsb.FRAME_SIZE, 1000);
        if (result < 0) throw new IOException("Bulk OUT transfer failed: " + result);
    }

    public void identify(int channel, boolean on) throws IOException {
        UsbDeviceConnection conn = requireConnection();
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(on ? 1 : 0);
        controlOut(conn, GsUsb.REQUEST_IDENTIFY, channel, 0, buf.array());
    }

    private void negotiate() throws IOException {
        UsbDeviceConnection conn = requireConnection();

        // 0x0000BEEF tells the device we are a little-endian host. Like the
        // kernel driver, this and DEVICE_CONFIG address the interface
        // directly (wIndex = bInterfaceNumber); everything per-channel below
        // (BITTIMING, MODE, IDENTIFY) uses wIndex = 0 instead.
        ByteBuffer hostFormatBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        hostFormatBuf.putInt(0x0000BEEF);
        controlOut(conn, GsUsb.REQUEST_HOST_FORMAT, 1, usbInterface.getId(), hostFormatBuf.array());

        byte[] devCfgBuf = new byte[GsUsb.DEVICE_CONFIG_SIZE];
        int devCfgRead = controlIn(conn, GsUsb.REQUEST_DEVICE_CONFIG, 1, usbInterface.getId(), devCfgBuf);
        if (devCfgRead < GsUsb.DEVICE_CONFIG_SIZE) {
            throw new IOException("Short DEVICE_CONFIG read: " + devCfgRead);
        }

        ByteBuffer devCfg = ByteBuffer.wrap(devCfgBuf).order(ByteOrder.LITTLE_ENDIAN);
        devCfg.position(3); // skip rsv[3]
        int icount = devCfg.get() & 0xFF;
        swVersion = devCfg.getInt();
        hwVersion = devCfg.getInt();
        // The device reports (channelCount - 1) as icount.
        channelCount = icount + 1;

        /* struct gs_device_bt_const, per channel (wValue = channel, wIndex = 0
         * like the kernel driver). Asking is the only way to know the CAN
         * clock: candleLight runs at 48 MHz on an STM32F042, but other boards
         * clock their controller differently, and programming a 48 MHz brp
         * into them yields the wrong bitrate rather than an error. */
        byte[] btcBuf = new byte[GsUsb.BT_CONST_SIZE];
        int btcRead = controlIn(conn, GsUsb.REQUEST_BT_CONST, 0, 0, btcBuf);
        if (btcRead < GsUsb.BT_CONST_SIZE) {
            throw new IOException("Short BT_CONST read: " + btcRead);
        }
        ByteBuffer btc = ByteBuffer.wrap(btcBuf).order(ByteOrder.LITTLE_ENDIAN);
        btc.getInt();                 // feature flags, already known from elsewhere
        fclkCan  = btc.getInt();
        tseg1Min = btc.getInt();
        tseg1Max = btc.getInt();
        tseg2Min = btc.getInt();
        tseg2Max = btc.getInt();
        sjwMax   = btc.getInt();
        brpMin   = btc.getInt();
        brpMax   = btc.getInt();
        brpInc   = btc.getInt();
        Log.i(tag, "bt_const: fclk=" + fclkCan + " tseg1=" + tseg1Min + ".." + tseg1Max
                + " tseg2=" + tseg2Min + ".." + tseg2Max + " sjw<=" + sjwMax
                + " brp=" + brpMin + ".." + brpMax + " step " + brpInc);
    }

    /** See {@link AsyncBulkPump} for why this replaces one synchronous bulkTransfer() at a time. */
    private void runRxLoop(int channel) {
        UsbDeviceConnection conn = connection;
        UsbEndpoint ep = epIn;
        if (conn == null || ep == null) return;

        // One host frame per read. FRAME_SIZE (20) is the classic-CAN layout:
        // 12-byte header + 8 data bytes. A timestamp-capable device appends a
        // 4-byte timestamp (24-byte frame), but we never enable
        // GS_CAN_MODE_HW_TIMESTAMP, so frames stay 20 bytes. Even if one didn't:
        // the timestamp trails the data, so the first 20 bytes are still a
        // complete frame — reading a fixed 20 stays frame-aligned either way.
        // Don't grow this to 24.
        AsyncBulkPump pump = new AsyncBulkPump(conn, ep, GsUsb.FRAME_SIZE);
        try {
            pump.start();
            while (rxRunning) {
                byte[] buf = pump.poll(100);
                if (buf == null || buf.length < GsUsb.FRAME_SIZE) continue;
                handleFrame(buf);
            }
        } finally {
            pump.close();
        }
    }

    private void handleFrame(byte[] buf) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        long echoId = bb.getInt() & 0xFFFFFFFFL;
        if (echoId != GsUsb.ECHO_ID_RX) return; // TX echo, not a received frame

        long rawCanId = bb.getInt() & 0xFFFFFFFFL;
        if ((rawCanId & GsUsb.CAN_ERR_FLAG) != 0) {
            // Controller/bus error notification, not a data frame. The error
            // class bits live in the id itself, the detail in the payload
            // (linux/can/error.h): data[1] controller status, data[2] protocol
            // error type, data[6]/data[7] the TX/RX error counters.
            Log.w(tag, "CAN error frame: id=0x" + Long.toHexString(rawCanId)
                    + " ctrl=0x" + Integer.toHexString(buf[13] & 0xFF)
                    + " prot=0x" + Integer.toHexString(buf[14] & 0xFF)
                    + " tec=" + (buf[18] & 0xFF)
                    + " rec=" + (buf[19] & 0xFF));
            if ((rawCanId & GsUsb.CAN_ERR_BUSOFF) != 0) {
                maybeRestartAfterBusOff();
            }
            return;
        }
        boolean isExtended = (rawCanId & GsUsb.CAN_EFF_FLAG) != 0;
        boolean isRemote = (rawCanId & GsUsb.CAN_RTR_FLAG) != 0;
        long idMask = isExtended ? GsUsb.CAN_EFF_MASK : GsUsb.CAN_SFF_MASK;
        int canId = (int) (rawCanId & idMask);

        int dlc = bb.get() & 0xFF;
        int frameChannel = bb.get() & 0xFF;
        int frameFlags = bb.get() & 0xFF;
        bb.get(); // reserved

        /* A remote frame carries no payload on the wire - its dlc asks for that
         * many bytes rather than announcing them. The host frame still has a
         * fixed-size data field, so whatever sits in it is residue from the
         * adapter: report none of it rather than handing back rubbish. */
        byte[] data = new byte[isRemote ? 0 : Math.min(dlc, 8)];
        bb.get(data);

        CanFrame frame = new CanFrame(canId, isExtended, isRemote, data);
        ReceivedFrame received = new ReceivedFrame(
            frameChannel,
            frame,
            frameFlags,
            (frameFlags & GsUsb.FRAME_FLAG_OVERFLOW) != 0
        );
        dispatch(received);
    }

    private void controlOut(UsbDeviceConnection conn, int request, int wValue, int wIndex, byte[] data) throws IOException {
        int result = conn.controlTransfer(
            GsUsb.REQUEST_TYPE_OUT, request, wValue, wIndex,
            data, data.length, 1000
        );
        if (result < 0) throw new IOException("Control OUT request " + request + " failed: " + result);
    }

    private int controlIn(UsbDeviceConnection conn, int request, int wValue, int wIndex, byte[] data) throws IOException {
        int result = conn.controlTransfer(
            GsUsb.REQUEST_TYPE_IN, request, wValue, wIndex,
            data, data.length, 1000
        );
        if (result < 0) throw new IOException("Control IN request " + request + " failed: " + result);
        return result;
    }
}
