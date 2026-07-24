package com.androidcan;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared plumbing for the USB-CAN drivers: connection ownership, listener
 * registration and dispatch, the RX thread lifecycle, and bus-off restart
 * debouncing.
 *
 * <p>Nothing here is protocol-specific — none of it has a counterpart in the
 * Linux kernel drivers these are ported from, which use URBs and netdev rather
 * than threads and listeners. Wire format, endpoint discovery and device
 * commands stay in the concrete drivers, where they can be cross-checked
 * against {@code gs_usb.c} / {@code usb_8dev.c}.</p>
 *
 * <p>Subclasses must keep behavior consistent with each other; putting the
 * lifecycle here is what makes that structural rather than a convention.</p>
 */
public abstract class AbstractUsbCanDevice implements CanDevice {

    /** Log tag: the concrete driver's class name. */
    protected final String tag = getClass().getSimpleName();

    protected final UsbManager usbManager;
    protected final UsbDevice device;

    protected UsbDeviceConnection connection;
    protected UsbInterface usbInterface;

    private final CopyOnWriteArrayList<FrameListener> listeners = new CopyOnWriteArrayList<>();

    protected volatile boolean rxRunning;
    private Thread rxThread;

    /**
     * How long to wait between bus-off restart attempts, so a persistent bus
     * fault retries roughly once per second instead of on every error frame.
     * Analogous to the kernel's restart-ms.
     */
    private static final long BUS_OFF_RESTART_INTERVAL_MS = 1000;
    private volatile long lastRestartMs;

    protected AbstractUsbCanDevice(UsbManager usbManager, UsbDevice device) {
        this.usbManager = usbManager;
        this.device = device;
    }

    /** True if the device's VID/PID appears in the given supported-device table. */
    protected static boolean matches(UsbDevice device, int[][] supportedDevices) {
        for (int[] pair : supportedDevices) {
            if (device.getVendorId() == pair[0] && device.getProductId() == pair[1]) {
                return true;
            }
        }
        return false;
    }

    /** Copy a payload into a fixed 8-byte buffer, as both wire formats require. */
    protected static byte[] padTo8(byte[] data) {
        byte[] padded = new byte[8];
        System.arraycopy(data, 0, padded, 0, data.length);
        return padded;
    }

    @Override
    public UsbDevice getUsbDevice() {
        return device;
    }

    @Override
    public void addFrameListener(FrameListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeFrameListener(FrameListener listener) {
        listeners.remove(listener);
    }

    /** Hand a frame to every listener; a throwing listener must not kill the RX loop. */
    protected void dispatch(ReceivedFrame received) {
        for (FrameListener listener : listeners) {
            try {
                listener.onFrameReceived(received);
            } catch (RuntimeException e) {
                Log.e(tag, "Frame listener threw", e);
            }
        }
    }

    /** Start the RX loop, replacing any previous one. */
    protected void startRxThread(String name, Runnable body) {
        stopRxThread();
        rxRunning = true;
        rxThread = new Thread(body, name);
        rxThread.setDaemon(true);
        rxThread.start();
    }

    /**
     * Stop the RX loop and wait for it to actually exit. Idempotent, and safe
     * to call when never started.
     *
     * <p>The join matters: {@link #rxRunning} is one shared flag, not a
     * per-thread one, so if a caller flips it back to true (via
     * {@link #startRxThread}) before the old thread has noticed it went
     * false, the old thread never observes false at all and runs forever
     * alongside the new one — a leaked reader competing with the live one on
     * the same endpoint for every subsequent stop/start cycle (this is what
     * caused a real, reproducible {@code CMD_CLOSE} failure and a 0/20
     * "recovery after bitrate mismatch" test result before this join was
     * added). Blocking here until the old thread is confirmed gone — and,
     * for a driver using {@link AsyncBulkPump}, until its pump has finished
     * draining and closing its requests — is what prevents that.</p>
     */
    protected void stopRxThread() {
        rxRunning = false;
        if (rxThread != null) {
            rxThread.interrupt();
            try {
                rxThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            rxThread = null;
        }
    }

    /**
     * Called from the RX loop when the controller reports bus-off. Debounces,
     * then delegates the actual restart to the driver.
     */
    protected synchronized void maybeRestartAfterBusOff() {
        long now = System.currentTimeMillis();
        if (now - lastRestartMs < BUS_OFF_RESTART_INTERVAL_MS) {
            return;
        }
        lastRestartMs = now;
        try {
            restartAfterBusOff();
            Log.i(tag, "Bus-off recovery: controller restarted");
        } catch (IOException e) {
            Log.e(tag, "Bus-off recovery failed", e);
        }
    }

    /**
     * Re-issue whatever command takes this device back on-bus, reusing the
     * settings captured at start.
     *
     * <p>Runs on the RX thread, so implementations must not touch the RX thread
     * lifecycle — no {@link #stopRxThread()}, no {@link #startRxThread}.</p>
     */
    protected abstract void restartAfterBusOff() throws IOException;

    @Override
    public void close() {
        stopRxThread();
        if (connection == null) return;
        if (usbInterface != null) connection.releaseInterface(usbInterface);
        connection.close();
        connection = null;
    }

    protected UsbDeviceConnection requireConnection() throws IOException {
        if (connection == null) throw new IOException("Device is not open");
        return connection;
    }
}
