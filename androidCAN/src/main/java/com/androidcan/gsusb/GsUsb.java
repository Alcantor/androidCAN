package com.androidcan.gsusb;

/**
 * Protocol constants for candleLight / gs_usb USB-CAN adapters (Geschwister
 * Schneider and compatible firmware), ported from the Linux kernel
 * {@code drivers/net/can/usb/gs_usb.c}.
 *
 * <p>Configuration goes over USB control transfers (host format, bit-timing,
 * mode, device/bit-timing-constant queries); CAN frames flow over a bulk
 * IN/OUT endpoint pair as fixed 20-byte structs. All multi-byte fields on the
 * wire are little-endian.</p>
 */
public final class GsUsb {

    private GsUsb() {
    }

    public static final int REQUEST_HOST_FORMAT = 0;
    public static final int REQUEST_BITTIMING = 1;
    public static final int REQUEST_MODE = 2;
    public static final int REQUEST_BT_CONST = 4;
    public static final int REQUEST_DEVICE_CONFIG = 5;
    public static final int REQUEST_IDENTIFY = 7;
    public static final int REQUEST_GET_STATE = 14;

    public static final int REQUEST_TYPE_OUT = 0x41;
    public static final int REQUEST_TYPE_IN = 0xC1;

    public static final int MODE_RESET = 0;
    public static final int MODE_START = 1;

    public static final int MODE_FLAG_LISTEN_ONLY = 0x01;
    public static final int MODE_FLAG_LOOP_BACK = 0x02;
    public static final int MODE_FLAG_TRIPLE_SAMPLE = 0x04;
    public static final int MODE_FLAG_ONE_SHOT = 0x08;
    public static final int MODE_FLAG_HW_TIMESTAMP = 0x10;
    public static final int MODE_FLAG_FD = 0x100;

    public static final int FEATURE_LISTEN_ONLY = 1 << 0;
    public static final int FEATURE_LOOP_BACK = 1 << 1;
    public static final int FEATURE_TRIPLE_SAMPLE = 1 << 2;
    public static final int FEATURE_ONE_SHOT = 1 << 3;
    public static final int FEATURE_HW_TIMESTAMP = 1 << 4;
    public static final int FEATURE_IDENTIFY = 1 << 5;
    public static final int FEATURE_FD = 1 << 8;
    public static final int FEATURE_TERMINATION = 1 << 11;
    public static final int FEATURE_GET_STATE = 1 << 13;

    public static final long CAN_EFF_FLAG = 0x80000000L;
    public static final long CAN_RTR_FLAG = 0x40000000L;
    public static final long CAN_ERR_FLAG = 0x20000000L;
    public static final long CAN_SFF_MASK = 0x7FFL;
    public static final long CAN_EFF_MASK = 0x1FFFFFFFL;

    // Error class bits carried in the id of a CAN_ERR_FLAG frame (linux/can/error.h).
    public static final long CAN_ERR_TX_TIMEOUT = 0x00000001L;
    public static final long CAN_ERR_LOSTARB = 0x00000002L;
    public static final long CAN_ERR_CRTL = 0x00000004L;
    public static final long CAN_ERR_PROT = 0x00000008L;
    public static final long CAN_ERR_TRX = 0x00000010L;
    public static final long CAN_ERR_ACK = 0x00000020L;
    public static final long CAN_ERR_BUSOFF = 0x00000040L;
    public static final long CAN_ERR_BUSERROR = 0x00000080L;
    public static final long CAN_ERR_RESTARTED = 0x00000100L;

    public static final long ECHO_ID_RX = 0xFFFFFFFFL;

    public static final int FRAME_FLAG_OVERFLOW = 0x01;
    public static final int FRAME_FLAG_FD = 0x02;
    public static final int FRAME_FLAG_BRS = 0x04;
    public static final int FRAME_FLAG_ESI = 0x08;

    public static final int FRAME_SIZE = 20;
    public static final int DEVICE_CONFIG_SIZE = 12;
    public static final int BT_CONST_SIZE = 40;
    public static final int BITTIMING_SIZE = 20;
    public static final int MODE_SIZE = 8;

    public static final int[][] SUPPORTED_DEVICES = {
        {0x1d50, 0x606f}, // gs_usb original
        {0x1209, 0x2323}, // candleLight
        {0x1cd2, 0x606f}, // CES CANext FD
        {0x16d0, 0x10b8}, // ABE CANdebugger FD
        {0x16d0, 0x0f30}, // Xylanta Saint3
        {0x1209, 0xca01}, // CANnectivity
    };
}
