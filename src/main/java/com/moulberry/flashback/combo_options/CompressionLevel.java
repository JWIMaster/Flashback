package com.moulberry.flashback.combo_options;

/**
 * How much of the encoder's automatically computed bitrate budget to actually use.
 *
 * <p>The automatic budget is deliberately generous (8 bits per pixel per second, capped at 288Mbps),
 * which produces very large files for gameplay footage that is mostly flat-coloured and moves
 * slowly. Scaling that budget is the single most effective control over output size, and unlike a
 * fixed bitrate it still adapts to resolution and framerate.
 */
public enum CompressionLevel implements ComboOption {

    MAXIMUM("Maximum Quality", 1.00),
    HIGH("High", 0.70),
    BALANCED("Balanced", 0.45),
    SMALL("Small Files", 0.28),
    TINY("Tiny Files", 0.15);

    private final String text;
    private final double bitrateRatio;

    CompressionLevel(String text, double bitrateRatio) {
        this.text = text;
        this.bitrateRatio = bitrateRatio;
    }

    @Override
    public String text() {
        return this.text;
    }

    /**
     * Fraction of the automatic bitrate budget to use.
     *
     * <p>Depth deliberately does not change this. An 8-bit source puts a ceiling on how much
     * detail the budget can usefully carry, but the automatic budget already sits far above that
     * ceiling, so scaling further would only starve the encoder - at 4K a depth-scaled "tiny"
     * setting worked out to roughly 22 Mbps, which is far too low for a 10/12-bit intermediate.
     */
    public double bitrateRatio() {
        return this.bitrateRatio;
    }

}
