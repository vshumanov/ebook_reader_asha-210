package reader;

import java.util.Vector;

/**
 * Word-wrapping pagination over a decoded text window.
 *
 * Given the text starting at the current page and the screen geometry, it lays
 * out exactly one screenful of lines and reports where the next page begins
 * (as a char index into the window). The device turns that char index into a
 * byte offset via Utf8, so paging never needs the whole book in memory.
 *
 * Pure and side-effect free -> unit-tested on the desktop.
 */
public final class Paginator {

    /** One laid-out page. */
    public static final class Page {
        public final String[] lines;
        public final int nextIndex;   // char index in the window where the next page starts
        Page(String[] lines, int nextIndex) {
            this.lines = lines;
            this.nextIndex = nextIndex;
        }
    }

    private Paginator() {}

    /**
     * @param text     decoded window, whose index 0 == the page's first char
     * @param m        character-width measure
     * @param maxWidth pixels available per line
     * @param maxLines lines that fit on the screen
     */
    public static Page layout(String text, TextMeasure m, int maxWidth, int maxLines) {
        Vector lines = new Vector();
        int n = text.length();
        int i = 0;

        while (lines.size() < maxLines && i < n) {
            int lineStart = i;
            int w = 0;
            int lastSpace = -1;
            int j = i;
            int cut;         // exclusive end of the line's visible text
            int resume;      // index to continue from on the next line

            while (true) {
                if (j >= n) {
                    cut = n; resume = n; break;
                }
                char c = text.charAt(j);
                if (c == '\n') {
                    cut = j; resume = j + 1; break;   // hard paragraph break; consume '\n'
                }
                int cw = m.charWidth(c);
                if (w + cw > maxWidth && j > lineStart) {
                    if (lastSpace >= lineStart) {
                        cut = lastSpace; resume = lastSpace + 1; // wrap at space, consume it
                    } else {
                        cut = j; resume = j;                     // hard split a long word
                    }
                    break;
                }
                w += cw;
                if (c == ' ') {
                    lastSpace = j;
                }
                j++;
            }

            lines.addElement(text.substring(lineStart, cut));
            i = resume;
            // Guard against non-progress (shouldn't happen, but never loop forever).
            if (resume <= lineStart && resume < n) {
                i = lineStart + 1;
            }
        }

        String[] arr = new String[lines.size()];
        for (int k = 0; k < arr.length; k++) {
            arr[k] = (String) lines.elementAt(k);
        }
        return new Page(arr, i);
    }
}
