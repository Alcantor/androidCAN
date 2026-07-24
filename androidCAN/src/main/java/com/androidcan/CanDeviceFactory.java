package com.androidcan;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.androidcan.gsusb.GsUsbDevice;
import com.androidcan.usb8dev.Usb8DevDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers supported CAN adapters on the USB bus and builds the matching
 * {@link CanDevice} driver instance for a given {@link UsbDevice}.
 */
public final class CanDeviceFactory {

    private CanDeviceFactory() {
    }

    /** Identify which driver, if any, handles the given USB device. */
    public static CanDeviceInfo.Driver driverFor(UsbDevice device) {
        if (GsUsbDevice.isSupported(device)) {
            return CanDeviceInfo.Driver.GSUSB;
        }
        if (Usb8DevDevice.isSupported(device)) {
            return CanDeviceInfo.Driver.USB_8DEV;
        }
        return null;
    }

    public static boolean isSupported(UsbDevice device) {
        return driverFor(device) != null;
    }

    /** List every attached, supported CAN adapter. */
    public static List<CanDeviceInfo> enumerate(UsbManager usbManager) {
        List<CanDeviceInfo> result = new ArrayList<>();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            CanDeviceInfo.Driver driver = driverFor(device);
            if (driver != null) {
                result.add(new CanDeviceInfo(device, driver));
            }
        }
        return result;
    }

    /** Build a driver for the device, or {@code null} if it is not supported. */
    public static CanDevice create(UsbManager usbManager, UsbDevice device) {
        CanDeviceInfo.Driver driver = driverFor(device);
        if (driver == null) {
            return null;
        }
        switch (driver) {
            case GSUSB:
                return new GsUsbDevice(usbManager, device);
            case USB_8DEV:
                return new Usb8DevDevice(usbManager, device);
            default:
                return null;
        }
    }
}
