package com.androidcan;

public class CanFrame {
    public int id;
    public boolean isExtended;
    public boolean isRemote;
    public byte[] data;

    public CanFrame(int id, boolean isExtended, boolean isRemote, byte[] data) {
        if (data.length > 8) {
            throw new IllegalArgumentException("Classic CAN data length must be <= 8");
        }
        this.id = id;
        this.isExtended = isExtended;
        this.isRemote = isRemote;
        this.data = data;
    }

    public int getDlc() {
        return data.length;
    }
}
