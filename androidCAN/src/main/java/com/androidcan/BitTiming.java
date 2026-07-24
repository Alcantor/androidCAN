package com.androidcan;

/**
 * CAN bit-timing segments, in time quanta, plus the clock prescaler — the same
 * five values as the Linux kernel's generic {@code struct can_bittiming},
 * which both the gs_usb and usb_8dev kernel drivers configure their hardware
 * from. gs_usb sends all five fields separately on the wire; usb_8dev packs
 * {@code propSeg + phaseSeg1} into a single combined byte instead, since its
 * device doesn't distinguish the two segments.
 *
 * <p>baudrate = fclk / (brp * (1 + propSeg + phaseSeg1 + phaseSeg2))</p>
 */
public class BitTiming {
    public final int propSeg;
    public final int phaseSeg1;
    public final int phaseSeg2;
    public final int sjw;
    public final int brp;

    public BitTiming(int propSeg, int phaseSeg1, int phaseSeg2, int sjw, int brp) {
        this.propSeg = propSeg;
        this.phaseSeg1 = phaseSeg1;
        this.phaseSeg2 = phaseSeg2;
        this.sjw = sjw;
        this.brp = brp;
    }
}
