import reader.*;

/**
 * Desktop validation of the pagination engine and the byte-offset paging loop
 * that the device uses. NOT shipped. Fixed-width measure (1 unit/char) so
 * maxWidth == chars-per-line.
 */
public class PaginatorTest {
    static int fails = 0;
    static final TextMeasure M = new TextMeasure() {
        public int charWidth(char c) { return 1; }
    };

    public static void main(String[] a) throws Exception {
        // --- Utf8 basics (Cyrillic = 2 bytes/char, emoji = surrogate pair = 4 bytes) ---
        check("byteLen ascii", Utf8.byteLen("abc") == 3);
        check("byteLen cyrillic", Utf8.byteLen("Привет") == 12);      // 6 * 2
        check("byteLen emoji", Utf8.byteLen("A😀") == 5);   // 1 + 4

        // completeByteLen must drop a trailing partial char
        byte[] cyr = "Привет".getBytes("UTF-8");                       // 12 bytes
        check("complete full", Utf8.completeByteLen(cyr, 12) == 12);
        check("complete cut mid-char", Utf8.completeByteLen(cyr, 11) == 10); // last 2-byte char dropped
        check("decode cut", Utf8.decode(cyr, 11).equals("Приве"));

        // --- layout: line width and visible-char preservation ---
        String text = "The quick brown fox jumps over the lazy dog.\n\n"
                    + "Supercalifragilisticexpialidocious word here.\n"
                    + "Пример текста на кириллице для проверки перевода строк.";
        int maxWidth = 20, maxLines = 4;

        // page once, check line widths
        Paginator.Page p0 = Paginator.layout(text, M, maxWidth, maxLines);
        for (int i = 0; i < p0.lines.length; i++) {
            int w = width(p0.lines[i]);
            check("line " + i + " width<=max (w=" + w + ")", w <= maxWidth || p0.lines[i].length() == 1);
        }
        check("page has <= maxLines", p0.lines.length <= maxLines);

        // --- full paging via BYTE OFFSETS (exactly what the device does) ---
        byte[] bytes = text.getBytes("UTF-8");
        StringBuffer seen = new StringBuffer();
        int offset = 0, pages = 0, guard = 0;
        while (offset < bytes.length) {
            if (++guard > 100000) { check("no infinite loop", false); break; }
            int winLen = Math.min(64, bytes.length - offset);         // tiny window to stress boundaries
            byte[] win = new byte[winLen];
            System.arraycopy(bytes, offset, win, 0, winLen);
            String window = Utf8.decode(win, winLen);
            Paginator.Page pg = Paginator.layout(window, M, maxWidth, maxLines);
            for (int i = 0; i < pg.lines.length; i++) {
                seen.append(pg.lines[i]);
            }
            int consumedChars = pg.nextIndex;
            if (consumedChars == 0) {
                // window smaller than a line's worth mid-word: force progress by 1 char
                consumedChars = Math.min(1, window.length());
                if (consumedChars == 0) break;
            }
            int consumedBytes = Utf8.byteLen(window.substring(0, consumedChars));
            offset += consumedBytes;
            pages++;
        }
        check("paging terminated", offset >= bytes.length);
        // visible chars (drop all whitespace) must match original, in order
        check("no bytes lost", strip(seen.toString()).equals(strip(text)));
        System.out.println("   (" + pages + " pages, window=64B, book=" + bytes.length + "B)");

        System.out.println(fails == 0 ? "\nALL CHECKS PASS" : "\n" + fails + " FAILURES");
        if (fails != 0) System.exit(1);
    }

    static int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += M.charWidth(s.charAt(i));
        return w;
    }
    static String strip(String s) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') b.append(c);
        }
        return b.toString();
    }
    static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) fails++;
    }
}
