package reader;

import javax.microedition.lcdui.Font;

/** Device TextMeasure backed by an lcdui Font. */
public final class FontMeasure implements TextMeasure {
    private final Font font;
    public FontMeasure(Font font) { this.font = font; }
    public int charWidth(char c) { return font.charWidth(c); }
}
