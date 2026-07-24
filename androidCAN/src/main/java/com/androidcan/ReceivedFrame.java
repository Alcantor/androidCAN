package com.androidcan;

public class ReceivedFrame {
    public final int channel;
    public final CanFrame frame;
    public final int flags;
    public final boolean isOverflow;

    public ReceivedFrame(int channel, CanFrame frame, int flags, boolean isOverflow) {
        this.channel = channel;
        this.frame = frame;
        this.flags = flags;
        this.isOverflow = isOverflow;
    }

    public ReceivedFrame(int channel, CanFrame frame) {
        this(channel, frame, 0, false);
    }
}
