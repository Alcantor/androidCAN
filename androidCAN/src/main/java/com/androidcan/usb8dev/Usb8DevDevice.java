package com.androidcan.usb8dev;

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
 * Driver for 8devices USB2CAN ("Korlan") adapters, speaking the same protocol
 * as the Linux {@code usb_8dev} kernel driver.
 *
 * <p>Unlike candleLight, this device is configured through command messages on
 * a dedicated pair of bulk endpoints, while CAN frames flow over a second pair.
 * All multi-byte fields on the wire are big-endian.</p>
 */
public class Usb8DevDevice extends AbstractUsbCanDevice {

    // Fixed segments at the 32 MHz clock: 16 time quanta per bit, sample point
    // 87.5% (combined tseg1 = 13, tseg2 = 2, sjw = 1). Only the prescaler
    // varies per bitrate, matching the kernel driver's constraints. The wire
    // format packs prop_seg + phase_seg1 into one byte (see sendOpen), so the
    // BitTiming carries all 13 quanta in phaseSeg1 and leaves propSeg at 0.
    private static final int TQ_PER_BIT = 16;

    public static boolean isSupported(UsbDevice device) {
        return matches(device, Usb8Dev.SUPPORTED_DEVICES);
    }

    private UsbEndpoint dataIn;
    private UsbEndpoint dataOut;
    private UsbEndpoint cmdIn;
    private UsbEndpoint cmdOut;

    private int swVersion;
    private int hwVersion;

    /** Bit-timing to reuse when auto-restarting after a bus-off. */
    private volatile BitTiming currentBitTiming;

    public Usb8DevDevice(UsbManager usbManager, UsbDevice device) {
        super(usbManager, device);
    }

    @Override
    public void open() throws IOException {
        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            throw new IOException("Failed to open USB device — check that permission was granted");
        }
        connection = conn;

        // Any failure past this point must release what we acquired; close() is
        // idempotent and cleans up whatever fields were set.
        try {
            if (!resolveInterface(conn)) {
                throw new IOException("Could not locate the four usb_8dev bulk endpoints");
            }
            // Reset then read the firmware version — also validates the command channel.
            sendCommand(Usb8Dev.CMD_RESET, 0, 0, new byte[10]);
            readVersion();
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    private boolean resolveInterface(UsbDeviceConnection conn) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            UsbEndpoint in = null, out = null, cin = null, cout = null;
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                int num = ep.getEndpointNumber();
                boolean isIn = ep.getDirection() == UsbConstants.USB_DIR_IN;
                if (num == Usb8Dev.ENDP_DATA_RX && isIn) in = ep;
                else if (num == Usb8Dev.ENDP_DATA_TX && !isIn) out = ep;
                else if (num == Usb8Dev.ENDP_CMD_RX && isIn) cin = ep;
                else if (num == Usb8Dev.ENDP_CMD_TX && !isIn) cout = ep;
            }
            if (in != null && out != null && cin != null && cout != null) {
                if (!conn.claimInterface(iface, true)) {
                    return false;
                }
                usbInterface = iface;
                dataIn = in;
                dataOut = out;
                cmdIn = cin;
                cmdOut = cout;
                return true;
            }
        }
        return false;
    }

    @Override
    public void start(int bitrate) throws IOException {
        requireConnection();
        stopRxThread();

        currentBitTiming = bitTimingFor(bitrate);
        sendOpen(currentBitTiming);

        startRxThread("usb8dev-rx", this::runRxLoop);
    }

    /** Send the OPEN command with the given bit-timing to go on-bus. */
    private void sendOpen(BitTiming bitTiming) throws IOException {
        // BAUD_MANUAL (0x09) is a command opt1 selector, not a flags bit — it
        // must not be OR'd in here. Doing so used to set the SILENT bit
        // (0x01, since 0x09 = SILENT | 0x08) by accident, putting the device
        // into listen-only mode on every open and silently dropping its ACKs.
        int flags = Usb8Dev.STATUS_FRAME;

        byte[] data = new byte[10];
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (bitTiming.propSeg + bitTiming.phaseSeg1)); // data[0] = prop_seg + phase_seg1
        bb.put((byte) bitTiming.phaseSeg2);                       // data[1] = phase_seg2
        bb.put((byte) bitTiming.sjw);                             // data[2] = sjw
        bb.putShort((short) bitTiming.brp);                       // data[3..4] = brp (be16)
        bb.putInt(flags);                                         // data[5..8] = flags (be32)

        sendCommand(Usb8Dev.CMD_OPEN, Usb8Dev.BAUD_MANUAL, 0, data);
    }

    /** Recover from bus-off by re-issuing OPEN, which restarts the controller. */
    @Override
    protected void restartAfterBusOff() throws IOException {
        BitTiming bitTiming = currentBitTiming;
        if (bitTiming == null) return;
        sendOpen(bitTiming);
    }

    @Override
    public void stop() throws IOException {
        /* CLOSE goes first, while the RX pump is still draining the data
         * endpoint. Tearing the pump down first leaves nothing reading, and a
         * controller in error-passive can be pushing status messages by the
         * thousand per second - a firmware blocked delivering into a full
         * endpoint buffer stops answering on its command endpoint, so CLOSE
         * times out and the device is left open. On an idle bus the order makes
         * no difference; under bus errors it is the difference between a clean
         * close and an adapter that needs replugging. */
        try {
            if (connection != null) {
                sendCommand(Usb8Dev.CMD_CLOSE, 0, 0, new byte[10]);
            }
        } finally {
            stopRxThread();
        }
    }

    @Override
    public void send(CanFrame frame) throws IOException {
        UsbDeviceConnection conn = requireConnection();

        int flags = 0;
        if (frame.isExtended) flags |= Usb8Dev.EXTID;
        if (frame.isRemote) flags |= Usb8Dev.RTR;

        ByteBuffer buf = ByteBuffer.allocate(Usb8Dev.TX_MSG_SIZE).order(ByteOrder.BIG_ENDIAN);
        buf.put(Usb8Dev.DATA_START);
        buf.put((byte) flags);
        buf.putInt(frame.id);            // id (be32), upper 3 bits unused
        buf.put((byte) frame.data.length);
        buf.put(padTo8(frame.data));
        buf.put(Usb8Dev.DATA_END);

        int result = conn.bulkTransfer(dataOut, buf.array(), Usb8Dev.TX_MSG_SIZE, Usb8Dev.CMD_TIMEOUT_MS);
        if (result < 0) throw new IOException("Data bulk OUT failed: " + result);
    }

    @Override
    public String getDeviceInfo() {
        if (connection == null) {
            return "Korlan USB2CAN (not open)";
        }
        // Each 16-bit version is a major.minor pair, matching the kernel's
        // "firmware: %d.%d, hardware: %d.%d" formatting.
        return "Korlan USB2CAN (usb_8dev)"
                + "  fw:" + ((swVersion >> 8) & 0xFF) + "." + (swVersion & 0xFF)
                + "  hw:" + ((hwVersion >> 8) & 0xFF) + "." + (hwVersion & 0xFF);
    }

    private static BitTiming bitTimingFor(int bitrate) throws IOException {
        int denom = bitrate * TQ_PER_BIT;
        if (denom <= 0 || Usb8Dev.ABP_CLOCK % denom != 0) {
            throw new IOException("Unsupported bitrate " + bitrate
                    + " (needs " + Usb8Dev.ABP_CLOCK + " / (brp*" + TQ_PER_BIT + "))");
        }
        int brp = Usb8Dev.ABP_CLOCK / denom;
        if (brp < 1 || brp > 1024) {
            throw new IOException("Bitrate " + bitrate + " yields out-of-range prescaler " + brp);
        }
        return new BitTiming(0, 13, 2, 1, brp);
    }

    /** Build a command message, send it, and validate the device's reply. */
    private void sendCommand(int command, int opt1, int opt2, byte[] data10) throws IOException {
        UsbDeviceConnection conn = requireConnection();

        ByteBuffer out = ByteBuffer.allocate(Usb8Dev.CMD_MSG_SIZE).order(ByteOrder.BIG_ENDIAN);
        out.put(Usb8Dev.CMD_START);
        out.put((byte) 0);               // channel
        out.put((byte) command);
        out.put((byte) opt1);
        out.put((byte) opt2);
        out.put(data10, 0, 10);
        out.put(Usb8Dev.CMD_END);

        int sent = conn.bulkTransfer(cmdOut, out.array(), Usb8Dev.CMD_MSG_SIZE, Usb8Dev.CMD_TIMEOUT_MS);
        if (sent < 0) throw new IOException("Command " + command + " bulk OUT failed: " + sent);

        byte[] in = readReply(command);
        int replyOpt1 = in[3] & 0xFF;
        if (replyOpt1 != Usb8Dev.CMD_SUCCESS) {
            throw new IOException("Command " + command + " rejected (opt1=" + replyOpt1 + ")");
        }
    }

    private byte[] readReply(int command) throws IOException {
        byte[] in = new byte[Usb8Dev.CMD_MSG_SIZE];
        int read = connection.bulkTransfer(cmdIn, in, in.length, Usb8Dev.CMD_TIMEOUT_MS);
        if (read < Usb8Dev.CMD_MSG_SIZE) {
            throw new IOException("Command " + command + " short reply: " + read);
        }
        if (in[0] != Usb8Dev.CMD_START || in[Usb8Dev.CMD_MSG_SIZE - 1] != Usb8Dev.CMD_END) {
            throw new IOException("Command " + command + " bad reply markers: begin=0x"
                    + Integer.toHexString(in[0] & 0xFF)
                    + " end=0x" + Integer.toHexString(in[Usb8Dev.CMD_MSG_SIZE - 1] & 0xFF));
        }
        return in;
    }

    /**
     * GET_SOFTW_HARDW_VER returns a big-endian u32 in the reply's data field.
     * The kernel decodes it as firmware:(b3.b2), hardware:(b1.b0) — i.e. the
     * upper 16 bits are the software (firmware) version and the lower 16 the
     * hardware version.
     */
    private void readVersion() throws IOException {
        UsbDeviceConnection conn = requireConnection();

        ByteBuffer out = ByteBuffer.allocate(Usb8Dev.CMD_MSG_SIZE).order(ByteOrder.BIG_ENDIAN);
        out.put(Usb8Dev.CMD_START);
        out.put((byte) 0);
        out.put((byte) Usb8Dev.CMD_GET_SOFTW_HARDW_VER);
        out.put(new byte[12]);           // opt1, opt2, data[10]
        out.put(Usb8Dev.CMD_END);

        int sent = conn.bulkTransfer(cmdOut, out.array(), Usb8Dev.CMD_MSG_SIZE, Usb8Dev.CMD_TIMEOUT_MS);
        if (sent < 0) throw new IOException("Version request failed: " + sent);

        byte[] in = readReply(Usb8Dev.CMD_GET_SOFTW_HARDW_VER);
        // Reply layout mirrors the command struct: data[10] starts at offset 5.
        int version = ByteBuffer.wrap(in, 5, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        swVersion = (version >> 16) & 0xFFFF;
        hwVersion = version & 0xFFFF;
    }

    /** See {@link AsyncBulkPump} for why this replaces one synchronous bulkTransfer() at a time. */
    private void runRxLoop() {
        UsbDeviceConnection conn = connection;
        UsbEndpoint ep = dataIn;
        if (conn == null || ep == null) return;

        AsyncBulkPump pump = new AsyncBulkPump(conn, ep, Usb8Dev.RX_BUFFER_SIZE);
        try {
            pump.start();
            while (rxRunning) {
                byte[] buf = pump.poll(100);
                if (buf == null || buf.length < Usb8Dev.RX_MSG_SIZE) continue;
                handleRxChunk(buf, buf.length);
            }
        } finally {
            pump.close();
        }
    }

    /** A single bulk transfer may carry several packed 21-byte messages. */
    private void handleRxChunk(byte[] buf, int n) {
        for (int pos = 0; pos + Usb8Dev.RX_MSG_SIZE <= n; pos += Usb8Dev.RX_MSG_SIZE) {
            if (buf[pos] != Usb8Dev.DATA_START) break;
            handleFrame(buf, pos);
        }
    }

    /**
     * Handle one 21-byte RX message, dispatching it if it turns out to be a CAN
     * frame. Takes an offset because a single bulk transfer packs several
     * messages, unlike gs_usb where one transfer is one frame.
     */
    private void handleFrame(byte[] buf, int pos) {
        ByteBuffer bb = ByteBuffer.wrap(buf, pos, Usb8Dev.RX_MSG_SIZE).order(ByteOrder.BIG_ENDIAN);
        bb.get(); // DATA_START, already checked by the caller

        int type = bb.get() & 0xFF;
        int flags = bb.get() & 0xFF;
        int rawId = bb.getInt();
        int dlc = Math.min(bb.get() & 0xFF, 8);
        // bb now sits on data[0], whatever the message turns out to be.

        // The kernel treats a message as a status/error frame only when BOTH
        // type == TYPE_ERROR_FRAME and flags == ERR_FLAG (exact match, not a
        // bit test); anything that is neither that nor a CAN frame is dropped.
        if (type == Usb8Dev.TYPE_ERROR_FRAME && flags == Usb8Dev.ERR_FLAG) {
            // In a status frame, data[0] carries the controller state.
            int state = bb.get() & 0xFF;
            Log.w(tag, "CAN status frame: " + Usb8Dev.statusName(state)
                    + " (0x" + Integer.toHexString(state) + ")");
            if (state == Usb8Dev.STATUSMSG_BUSOFF) {
                maybeRestartAfterBusOff();
            }
            return;
        }
        if (type != Usb8Dev.TYPE_CAN_FRAME) {
            Log.w(tag, "Unknown frame type " + type + " (flags=0x" + Integer.toHexString(flags) + ")");
            return;
        }

        boolean isExtended = (flags & Usb8Dev.EXTID) != 0;
        boolean isRemote = (flags & Usb8Dev.RTR) != 0;
        int id = rawId & (isExtended ? 0x1FFFFFFF : 0x7FF);

        /* A remote frame carries no payload on the wire - its dlc asks for that
         * many bytes rather than announcing them. Whatever sits in the message's
         * data field is residue from the adapter, so report none of it. */
        byte[] data = new byte[isRemote ? 0 : dlc];
        bb.get(data);

        dispatch(new ReceivedFrame(0, new CanFrame(id, isExtended, isRemote, data)));
    }
}
