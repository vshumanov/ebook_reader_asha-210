package reader;

/**
 * Abstracts text measurement so the pagination engine can be unit-tested on
 * the desktop (fixed-width measure) and run on the device (lcdui Font).
 * Width is accumulated per character -- MIDP bitmap fonts are effectively
 * additive, and this keeps wrapping O(n) instead of re-measuring substrings.
 */
public interface TextMeasure {
    int charWidth(char c);
}
