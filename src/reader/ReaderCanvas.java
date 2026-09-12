package reader;

import java.io.IOException;
import javax.microedition.lcdui.*;

/**
 * Full-screen reading view. Streams pages from a Book, paints one screenful,
 * and turns pages on keys or softkey commands. Keeps only the current page's
 * lines in memory.
 *
 * Keys: right/down/space = next page, left/up = previous, 1/3 = font -/+,
 * 5 = light/dark. Commands mirror these under Options for discoverability.
 */
public final class ReaderCanvas extends Canvas implements CommandListener {

    private static final int[] SIZES = { Font.SIZE_SMALL, Font.SIZE_MEDIUM, Font.SIZE_LARGE };
    private static final int MARGIN_X = 4;
    private static final int MARGIN_TOP = 2;
    private static final int FOOTER = 12;

    private final ReaderMIDlet mid;
    private final Sd sd;
    private final String name;

    private Book book;
    private Font font;
    private int fontIdx;
    private boolean dark;
    private int lineH;
    private String status = "";

    private final Command backCmd = new Command("Back", Command.BACK, 1);
    private final Command biggerCmd = new Command("Font +", Command.SCREEN, 2);
    private final Command smallerCmd = new Command("Font -", Command.SCREEN, 3);
    private final Command themeCmd = new Command("Light/Dark", Command.SCREEN, 4);

    public ReaderCanvas(ReaderMIDlet mid, Sd sd, String name, long size,
                        long startOffset, int fontIdx, boolean dark) throws IOException {
        this.mid = mid;
        this.sd = sd;
        this.name = name;
        this.fontIdx = clampIdx(fontIdx);
        this.dark = dark;
        setFullScreenMode(true);
        applyFont();
        book = new Book(sd, name, size, new FontMeasure(font), maxWidth(), maxLines());
        book.openAt(startOffset);
        addCommand(backCmd);
        addCommand(biggerCmd);
        addCommand(smallerCmd);
        addCommand(themeCmd);
        setCommandListener(this);
    }

    private void applyFont() {
        font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, SIZES[fontIdx]);
        lineH = font.getHeight();
    }

    private int maxWidth() { return getWidth() - 2 * MARGIN_X; }
    private int maxLines() {
        int usable = getHeight() - MARGIN_TOP - FOOTER;
        int n = usable / lineH;
        return (n < 1) ? 1 : n;
    }

    protected void paint(Graphics g) {
        int bg = dark ? 0x000000 : 0xFFFFFF;
        int fg = dark ? 0xE8E8E8 : 0x000000;
        int dim = dark ? 0x808080 : 0x999999;
        g.setColor(bg);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(fg);
        g.setFont(font);
        String[] lines = book.getLines();
        int y = MARGIN_TOP;
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], MARGIN_X, y, Graphics.TOP | Graphics.LEFT);
            y += lineH;
        }
        // footer: progress + transient status
        g.setColor(dim);
        Font sf = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(sf);
        String pct = book.progressPercent() + "%";
        g.drawString(pct, MARGIN_X, getHeight() - 1, Graphics.BOTTOM | Graphics.LEFT);
        if (status.length() > 0) {
            g.drawString(status, getWidth() - MARGIN_X, getHeight() - 1,
                         Graphics.BOTTOM | Graphics.RIGHT);
        }
    }

    protected void keyPressed(int key) {
        int ga = 0;
        try { ga = getGameAction(key); } catch (Exception e) {}
        if (ga == Canvas.RIGHT || ga == Canvas.DOWN || ga == Canvas.FIRE
                || key == KEY_NUM6 || key == ' ') {
            turn(true);
        } else if (ga == Canvas.LEFT || ga == Canvas.UP || key == KEY_NUM4) {
            turn(false);
        } else if (key == KEY_NUM3) {
            changeFont(1);
        } else if (key == KEY_NUM1) {
            changeFont(-1);
        } else if (key == KEY_NUM5) {
            toggleTheme();
        }
    }

    private void turn(boolean forward) {
        try {
            if (forward) {
                if (book.hasNext()) book.next(); else { flash("End"); return; }
            } else {
                if (book.hasPrev()) book.prev(); else { flash("Start"); return; }
            }
            status = "";
            repaint();
            mid.savePosition(name, book.getPageStart(), fontIdx, dark);
        } catch (IOException e) {
            flash("read error");
        }
    }

    private void changeFont(int delta) {
        int ni = clampIdx(fontIdx + delta);
        if (ni == fontIdx) return;
        fontIdx = ni;
        applyFont();
        try {
            book.relayout(new FontMeasure(font), maxWidth(), maxLines());
            repaint();
            mid.savePosition(name, book.getPageStart(), fontIdx, dark);
        } catch (IOException e) {
            flash("read error");
        }
    }

    private void toggleTheme() {
        dark = !dark;
        repaint();
        mid.savePosition(name, book.getPageStart(), fontIdx, dark);
    }

    private void flash(String s) {
        status = s;
        repaint();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            book.close();
            mid.savePosition(name, book.getPageStart(), fontIdx, dark);
            mid.showBookList();
        } else if (c == biggerCmd) {
            changeFont(1);
        } else if (c == smallerCmd) {
            changeFont(-1);
        } else if (c == themeCmd) {
            toggleTheme();
        }
    }

    public void onPause() {
        book.close();
        mid.savePosition(name, book.getPageStart(), fontIdx, dark);
    }

    private static int clampIdx(int i) {
        if (i < 0) return 0;
        if (i >= SIZES.length) return SIZES.length - 1;
        return i;
    }
}
