package com.androidcan;

import android.hardware.usb.UsbDevice;

/**
 * Describes a supported CAN adapter discovered on the USB bus, together with
 * the driver that can talk to it. Returned by {@link CanDeviceFactory#enumerate}.
 */
public final class CanDeviceInfo {

    /** Driver family able to handle the device. */
    public enum Driver {
        GSUSB("candleLight (gs_usb)"),
        USB_8DEV("8devices (usb_8dev)");

        public final String label;

        Driver(String label) {
            this.label = label;
        }
    }

    public final UsbDevice device;
    public final Driver driver;

    public CanDeviceInfo(UsbDevice device, Driver driver) {
        this.device = device;
        this.driver = driver;
    }

    /** Product name if the OS exposes one, otherwise the VID:PID pair. */
    public String displayName() {
        String product = device.getProductName();
        if (product != null && !product.isEmpty()) {
            return product;
        }
        return String.format("%04x:%04x", device.getVendorId(), device.getProductId());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(displayName()).append(" — ").append(driver.label);

        String version = device.getVersion();
        if (version != null && !version.isEmpty()) {
            sb.append("  v").append(version);
        }

        // Only non-null once USB permission has been granted (API 29+ requirement).
        String serial = device.getSerialNumber();
        if (serial != null && !serial.isEmpty()) {
            sb.append("  SN:").append(serial);
        }

        return sb.toString();
    }
}
