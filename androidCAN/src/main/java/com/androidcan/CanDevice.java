package com.androidcan;

import android.hardware.usb.UsbDevice;

import java.io.Closeable;
import java.io.IOException;

/**
 * Common interface for a USB CAN adapter, independent of the underlying
 * protocol (candleLight/gs_usb, 8devices/usb_8dev, ...).
 *
 * Typical lifecycle:
 * <pre>
 *   dev.open();                 // claim USB interface, read device info
 *   dev.addFrameListener(l);
 *   dev.start(500000);          // go on-bus at the given bitrate
 *   dev.send(frame);
 *   dev.stop();                 // go off-bus
 *   dev.close();                // release USB interface
 * </pre>
 */
public interface CanDevice extends Closeable {

    /** Open the USB device, claim its interface and read its configuration. */
    void open() throws IOException;

    /** Configure the given bitrate (bit/s) and go on-bus, starting reception. */
    void start(int bitrate) throws IOException;

    /** Go off-bus and stop reception. Safe to call if never started. */
    void stop() throws IOException;

    /** Transmit a single classic CAN frame. */
    void send(CanFrame frame) throws IOException;

    void addFrameListener(FrameListener listener);

    void removeFrameListener(FrameListener listener);

    /**
     * The USB device this driver was built for - the one handed to
     * {@link CanDeviceFactory#create}. Lets a caller match an open device
     * against a {@code UsbManager} attach or detach broadcast without having
     * to keep its own copy of the reference.
     */
    UsbDevice getUsbDevice();

    /** Human-readable one-line description of the connected device. */
    String getDeviceInfo();

    /** Release the USB interface and connection. Idempotent. */
    @Override
    void close();
}
