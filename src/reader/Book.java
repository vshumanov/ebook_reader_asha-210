package reader;

import java.io.IOException;

/**
 * Device-side paging over one book, wrapping the pure Paginator with a
 * forward-streaming byte buffer so memory stays flat regardless of book size:
 *
 *   - Reading forward (next) never re-seeks: the unused tail of each window is
 *     carried into the next page and the open stream just continues.
 *   - Going back (prev) or jumping reopens the stream and skips to a stored
 *     byte offset (occasional, so the O(offset) skip cost is fine).
 *
 * Position is a byte offset, so bookmarks survive across sessions.
 */
public final class Book {

    private static final int WINDOW = 6000; // bytes buffered per page layout

    private final Sd sd;
    private final String name;
    private final long size;
    private TextMeasure measure;
    private int maxWidth;
    private int maxLines;

    private byte[] carry = new byte[0];
    private long pageStart;
    private long pendingNext;
    private String[] currentLines = new String[0];

    private long[] history = new long[64];
    private int histLen;

    public Book(Sd sd, String name, long size, TextMeasure m, int maxWidth, int maxLines) {
        this.sd = sd;
        this.name = name;
        this.size = size;
        this.measure = m;
        this.maxWidth = maxWidth;
        this.maxLines = maxLines;
    }

    public String[] getLines() { return currentLines; }
    public long getPageStart() { return pageStart; }
    public boolean hasNext() { return pendingNext < size; }
    public boolean hasPrev() { return histLen > 0; }

    public int progressPercent() {
        if (size <= 0) return 0;
        long p = pageStart * 100L / size;
        if (p > 100) p = 100;
        return (int) p;
    }

    /** Open (or reopen) at a byte offset and lay out that page. */
    public void openAt(long offset) throws IOException {
        if (offset < 0) offset = 0;
        if (offset > size) offset = size;
        sd.openStream(name, offset);
        pageStart = offset;
        carry = new byte[0];
        layoutHere();
    }

    public void next() throws IOException {
        if (!hasNext()) return;
        pushHistory(pageStart);
        pageStart = pendingNext;
        // carry already holds the bytes from pageStart onward that were pre-read
        layoutHere();
    }

    public void prev() throws IOException {
        if (histLen == 0) return;
        long target = history[--histLen];
        openAt(target); // reopen + skip; resets carry
    }

    /** Re-lay the current page after a font/geometry change. */
    public void relayout(TextMeasure m, int maxWidth, int maxLines) throws IOException {
        this.measure = m;
        this.maxWidth = maxWidth;
        this.maxLines = maxLines;
        openAt(pageStart);
    }

    /** Jump to a fraction (0..100) of the book, snapped to the next line start. */
    public void jumpPercent(int pct) throws IOException {
        if (pct < 0) pct = 0; if (pct > 100) pct = 100;
        long target = size * pct / 100L;
        histLen = 0;
        sd.openStream(name, target);
        // snap forward to just after the next newline so we start at a line
        byte[] probe = sd.readMore(512);
        int adj = 0;
        for (int i = 0; i < probe.length; i++) {
            adj++;
            if (probe[i] == '\n') break;
        }
        openAt(target + adj);
    }

    private void pushHistory(long v) {
        if (histLen == history.length) {
            long[] bigger = new long[history.length * 2];
            System.arraycopy(history, 0, bigger, 0, history.length);
            history = bigger;
        }
        history[histLen++] = v;
    }

    private void layoutHere() throws IOException {
        byte[] buf = carry;
        int want = WINDOW - buf.length;
        if (want > 0) {
            byte[] more = sd.readMore(want);
            if (more.length > 0) {
                byte[] joined = new byte[buf.length + more.length];
                System.arraycopy(buf, 0, joined, 0, buf.length);
                System.arraycopy(more, 0, joined, buf.length, more.length);
                buf = joined;
            }
        }
        String win = Utf8.decode(buf, buf.length);
        Paginator.Page pg = Paginator.layout(win, measure, maxWidth, maxLines);
        currentLines = pg.lines;

        int consumedChars = pg.nextIndex;
        if (consumedChars == 0) {
            consumedChars = win.length(); // avoid stalling on an odd window
        }
        int consumedBytes = Utf8.byteLen(win.substring(0, consumedChars));
        if (consumedBytes > buf.length) consumedBytes = buf.length;
        pendingNext = pageStart + consumedBytes;

        int rem = buf.length - consumedBytes;
        byte[] tail = new byte[rem];
        System.arraycopy(buf, consumedBytes, tail, 0, rem);
        carry = tail;
    }

    public void close() {
        sd.closeStream();
    }
}
