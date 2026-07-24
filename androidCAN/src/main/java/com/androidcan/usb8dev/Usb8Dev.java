package com.androidcan.usb8dev;

/**
 * Protocol constants for 8devices USB2CAN adapters (a.k.a. "Korlan"), ported
 * from the Linux kernel {@code drivers/net/can/usb/usb_8dev.c}.
 *
 * The device exposes four bulk endpoints (by endpoint number):
 * data RX (1, IN), data TX (2, OUT), command RX (3, IN), command TX (4, OUT).
 * Command messages are fixed 16-byte structs framed by begin/end markers; CAN
 * frames use separate 16-byte (TX) / 21-byte (RX) structs. Multi-byte fields
 * on the wire are big-endian.
 */
public final class Usb8Dev {

    private Usb8Dev() {
    }

    public static final int VENDOR_ID = 0x0483;
    public static final int PRODUCT_ID = 0x1234;

    public static final int[][] SUPPORTED_DEVICES = {
        {VENDOR_ID, PRODUCT_ID},
    };

    // Endpoint numbers (low nibble of the USB endpoint address).
    public static final int ENDP_DATA_RX = 1; // IN
    public static final int ENDP_DATA_TX = 2; // OUT
    public static final int ENDP_CMD_RX = 3;  // IN
    public static final int ENDP_CMD_TX = 4;  // OUT

    /** CAN controller clock feeding the bit-timing prescaler. */
    public static final int ABP_CLOCK = 32_000_000;

    // Command message framing.
    public static final byte CMD_START = 0x11;
    public static final byte CMD_END = 0x22;
    public static final int CMD_MSG_SIZE = 16;   // begin,channel,command,opt1,opt2,data[10],end
    public static final int CMD_SUCCESS = 0;
    public static final int CMD_TIMEOUT_MS = 1000;

    // Command opcodes (enum usb_8dev_cmd).
    public static final int CMD_RESET = 1;
    public static final int CMD_OPEN = 2;
    public static final int CMD_CLOSE = 3;
    public static final int CMD_SET_SPEED = 4;
    public static final int CMD_SET_MASK_FILTER = 5;
    public static final int CMD_GET_STATUS = 6;
    public static final int CMD_GET_STATISTICS = 7;
    public static final int CMD_GET_SERIAL = 8;
    public static final int CMD_GET_SOFTW_VER = 9;
    public static final int CMD_GET_HARDW_VER = 10;
    public static final int CMD_RESET_TIMESTAMP = 11;
    public static final int CMD_GET_SOFTW_HARDW_VER = 12;

    // Mode flags passed to CMD_OPEN.
    public static final int SILENT = 0x01;                 // listen-only
    public static final int LOOPBACK = 0x02;
    public static final int DISABLE_AUTO_RESTRANS = 0x04;  // one-shot
    public static final int STATUS_FRAME = 0x08;
    public static final int BAUD_MANUAL = 0x09;

    // Data frame framing.
    public static final byte DATA_START = 0x55;
    public static final byte DATA_END = (byte) 0xAA;
    public static final int TX_MSG_SIZE = 16;  // begin,flags,id[4],dlc,data[8],end
    public static final int RX_MSG_SIZE = 21;  // begin,type,flags,id[4],dlc,data[8],timestamp[4],end
    public static final int RX_BUFFER_SIZE = 64;

    // RX frame type (usb_8dev_rx_msg.type).
    public static final int TYPE_CAN_FRAME = 0;
    public static final int TYPE_ERROR_FRAME = 3;

    // Frame flag bits (usb_8dev_*_msg.flags).
    public static final int EXTID = 0x01;
    public static final int RTR = 0x02;
    public static final int ERR_FLAG = 0x04;

    /*
     * Reported in an error/status frame's data[0]. 0x00-0x04 are bus states,
     * 0x20 upwards are single protocol errors, so a bus in trouble emits a
     * stream of these mixed together. Values and names from usb_8dev.c.
     */
    public static final int STATUSMSG_OK = 0x00;         // normal condition
    public static final int STATUSMSG_OVERRUN = 0x01;    // overrun while sending
    public static final int STATUSMSG_BUSLIGHT = 0x02;   // error counter reached 96
    public static final int STATUSMSG_BUSHEAVY = 0x03;   // error counter reached 128
    public static final int STATUSMSG_BUSOFF = 0x04;
    public static final int STATUSMSG_STUFF = 0x20;      // stuff error
    public static final int STATUSMSG_FORM = 0x21;       // form error
    public static final int STATUSMSG_ACK = 0x23;        // ack error
    /* The kernel's comments on these two read "Bit1" for BIT0 and "Bit0" for
     * BIT1, which looks like a transposition upstream. Names kept as they are
     * there so the two sources can be compared line by line. */
    public static final int STATUSMSG_BIT0 = 0x24;
    public static final int STATUSMSG_BIT1 = 0x25;
    public static final int STATUSMSG_CRC = 0x27;        // CRC error

    /** Name of a status/error code, for logs. */
    public static String statusName(int status) {
        switch (status) {
            case STATUSMSG_OK: return "OK";
            case STATUSMSG_OVERRUN: return "OVERRUN";
            case STATUSMSG_BUSLIGHT: return "BUSLIGHT";
            case STATUSMSG_BUSHEAVY: return "BUSHEAVY";
            case STATUSMSG_BUSOFF: return "BUSOFF";
            case STATUSMSG_STUFF: return "STUFF";
            case STATUSMSG_FORM: return "FORM";
            case STATUSMSG_ACK: return "ACK";
            case STATUSMSG_BIT0: return "BIT0";
            case STATUSMSG_BIT1: return "BIT1";
            case STATUSMSG_CRC: return "CRC";
            default: return "unknown";
        }
    }
}
