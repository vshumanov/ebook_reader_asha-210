package reader;

import java.io.*;
import java.util.Hashtable;
import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.*;

/**
 * Offline e-book reader for the Nokia Asha 210 (MIDP 2.0 / CLDC 1.1).
 *
 * Reads UTF-8 .txt books from "Books/" on the SD card (converted from
 * EPUB/PDF on a desktop -- see converter/). Streams pages so book size is
 * irrelevant to memory. Remembers your place, font size, and theme per book.
 */
public class ReaderMIDlet extends MIDlet implements CommandListener {

    private Display display;
    private Sd sd;

    private List listScreen;
    private String[] books = new String[0];   // what's currently shown, after sort+filter
    private Command openCmd, refreshCmd, exitCmd, diagCmd;
    private Command allCmd, newCmd, progressCmd;

    // reading status, used for sorting and the menu filters
    private static final int ST_PROGRESS = 0;  // opened, not finished  -> top
    private static final int ST_NEW = 1;       // never opened          -> middle
    private static final int ST_DONE = 2;      // finished              -> bottom

    private static final int FILTER_ALL = 0;
    private static final int FILTER_NEW = 1;
    private static final int FILTER_PROGRESS = 2;
    private int filter = FILTER_ALL;

    private ReaderCanvas canvas;

    // per-book reading state, keyed by file name
    private Hashtable state = new Hashtable();

    protected void startApp() {
        display = Display.getDisplay(this);
        if (sd == null) {
            sd = new Sd();
            if (sd.isAvailable()) loadState();
        }
        if (!sd.isAvailable()) {
            showNoCard();
            return;
        }
        if (canvas != null) {
            display.setCurrent(canvas);   // resuming after pause
        } else {
            showBookList();
        }
    }

    protected void pauseApp() {
        if (canvas != null) canvas.onPause();
    }

    protected void destroyApp(boolean unconditional) {
        if (canvas != null) canvas.onPause();
        if (sd != null) sd.closeStream();
    }

    private void showNoCard() {
        Form f = new Form("No SD card");
        f.append(sd.getProblem() + "\n\nPut your .txt books in a \"Books\" folder "
               + "on the memory card, then reopen.\n\n--- diagnostic ---\n"
               + sd.getReport());
        Command retry = new Command("Retry", Command.OK, 1);
        Command quit = new Command("Exit", Command.EXIT, 2);
        f.addCommand(retry);
        f.addCommand(quit);
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.EXIT) {
                    destroyApp(true);
                    notifyDestroyed();
                } else {
                    sd = new Sd();
                    if (sd.isAvailable()) { loadState(); showBookList(); }
                    else showNoCard();
                }
            }
        });
        display.setCurrent(f);
    }

    public void showBookList() {
        canvas = null;
        sd.closeStream();

        // Sort all books by status (in-progress, then new, then done),
        // then drop whatever the current filter hides.
        books = arrange(sd.listBooks());

        String title = (filter == FILTER_NEW) ? "Books - new"
                     : (filter == FILTER_PROGRESS) ? "Books - reading"
                     : "Books";
        listScreen = new List(title, List.IMPLICIT);
        for (int i = 0; i < books.length; i++) {
            listScreen.append(label(books[i]), null);
        }
        if (books.length == 0) {
            listScreen.append(emptyMessage(), null);
        }
        openCmd = new Command("Open", Command.ITEM, 1);
        allCmd = new Command("Show all", Command.SCREEN, 2);
        newCmd = new Command("Show new", Command.SCREEN, 3);
        progressCmd = new Command("Show in progress", Command.SCREEN, 4);
        refreshCmd = new Command("Refresh", Command.SCREEN, 5);
        diagCmd = new Command("Diagnostics", Command.SCREEN, 6);
        exitCmd = new Command("Exit", Command.EXIT, 7);
        listScreen.addCommand(openCmd);
        if (filter != FILTER_ALL) listScreen.addCommand(allCmd);
        if (filter != FILTER_NEW) listScreen.addCommand(newCmd);
        if (filter != FILTER_PROGRESS) listScreen.addCommand(progressCmd);
        listScreen.addCommand(refreshCmd);
        listScreen.addCommand(diagCmd);
        listScreen.addCommand(exitCmd);
        listScreen.setCommandListener(this);
        display.setCurrent(listScreen);
    }

    /** Sort by status (in-progress, new, done) and apply the current filter. */
    private String[] arrange(String[] all) {
        // stable bucketing keeps each group in the order sd returned it
        java.util.Vector prog = new java.util.Vector();
        java.util.Vector fresh = new java.util.Vector();
        java.util.Vector done = new java.util.Vector();
        for (int i = 0; i < all.length; i++) {
            switch (status(all[i])) {
                case ST_PROGRESS: prog.addElement(all[i]); break;
                case ST_DONE:     done.addElement(all[i]); break;
                default:          fresh.addElement(all[i]); break;
            }
        }
        java.util.Vector out = new java.util.Vector();
        if (filter == FILTER_NEW) {
            append(out, fresh);
        } else if (filter == FILTER_PROGRESS) {
            append(out, prog);
        } else {
            append(out, prog);
            append(out, fresh);
            append(out, done);
        }
        String[] result = new String[out.size()];
        out.copyInto(result);
        return result;
    }

    private static void append(java.util.Vector dst, java.util.Vector src) {
        for (int i = 0; i < src.size(); i++) dst.addElement(src.elementAt(i));
    }

    private int status(String file) {
        long[] st = (long[]) state.get(file);
        if (st == null) return ST_NEW;
        if (st.length > 3 && st[3] == 1) return ST_DONE;
        return ST_PROGRESS;
    }

    private String emptyMessage() {
        if (filter == FILTER_NEW) return "(no new books)";
        if (filter == FILTER_PROGRESS) return "(nothing in progress)";
        return "(no books - add .txt to Books/ on card)";
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            destroyApp(true);
            notifyDestroyed();
        } else if (c == refreshCmd) {
            showBookList();
        } else if (c == allCmd) {
            filter = FILTER_ALL;
            showBookList();
        } else if (c == newCmd) {
            filter = FILTER_NEW;
            showBookList();
        } else if (c == progressCmd) {
            filter = FILTER_PROGRESS;
            showBookList();
        } else if (c == diagCmd) {
            Alert a = new Alert("Diagnostics",
                    "found " + sd.listBooks().length + " book(s)\n" + sd.getReport(),
                    null, AlertType.INFO);
            a.setTimeout(Alert.FOREVER);
            display.setCurrent(a, listScreen);
        } else if (c == openCmd || c == List.SELECT_COMMAND) {
            int idx = listScreen.getSelectedIndex();
            if (idx >= 0 && idx < books.length) {
                openBook(books[idx]);
            }
        }
    }

    private void openBook(String name) {
        try {
            long size = sd.size(name);
            long[] st = (long[]) state.get(name);   // {offset, fontIdx, dark}
            long offset = (st != null) ? st[0] : 0;
            int fontIdx = (st != null) ? (int) st[1] : 1;
            boolean dark = (st != null) && st[2] == 1;
            canvas = new ReaderCanvas(this, sd, name, size, offset, fontIdx, dark);
            display.setCurrent(canvas);
        } catch (Exception e) {
            Alert a = new Alert("Error", "Could not open: " + e.getMessage(), null, AlertType.ERROR);
            a.setTimeout(Alert.FOREVER);
            display.setCurrent(a, listScreen);
        }
    }

    /** Called by the canvas whenever position/font/theme changes. */
    public void savePosition(String name, long offset, int fontIdx, boolean dark, boolean done) {
        state.put(name, new long[] { offset, fontIdx, dark ? 1 : 0, done ? 1 : 0 });
        saveState();
    }

    // ---- state file (small; rewritten whole) ----

    private void loadState() {
        byte[] data = sd.readState();
        if (data == null) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            // v1 begins with a positive count; v2+ begins with a negative
            // version marker so we can add fields without losing old progress.
            int first = in.readInt();
            int version, n;
            if (first < 0) { version = -first; n = in.readInt(); }
            else { version = 1; n = first; }
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                long offset = in.readLong();
                int fontIdx = in.readByte();
                int dark = in.readByte();
                int done = (version >= 2) ? in.readByte() : 0;
                state.put(name, new long[] { offset, fontIdx, dark, done });
            }
        } catch (IOException e) {
            // ignore a corrupt state file
        }
    }

    private void saveState() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(-2);                  // v2 marker (see loadState)
            out.writeInt(state.size());
            java.util.Enumeration keys = state.keys();
            while (keys.hasMoreElements()) {
                String name = (String) keys.nextElement();
                long[] st = (long[]) state.get(name);
                out.writeUTF(name);
                out.writeLong(st[0]);
                out.writeByte((int) st[1]);
                out.writeByte((int) st[2]);
                out.writeByte(st.length > 3 ? (int) st[3] : 0);
            }
            out.flush();
            sd.writeState(bos.toByteArray());
        } catch (IOException e) {
            // best effort
        }
    }

    /** List entry: book name plus "(done)" or "(NN%)" once it has been opened. */
    private String label(String file) {
        String base = display(file);
        long[] st = (long[]) state.get(file);
        if (st == null) return base + "  (new)";        // never opened
        if (st.length > 3 && st[3] == 1) return base + "  (done)";
        long size = 0;
        try { size = sd.size(file); } catch (Exception e) {}
        if (size <= 0) return base;
        long pct = st[0] * 100L / size;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return base + "  (" + pct + "%)";
    }

    private static String display(String file) {
        String s = file;
        if (endsWith(s, ".txt")) s = s.substring(0, s.length() - 4);
        return s.replace('_', ' ');
    }

    private static boolean endsWith(String s, String suf) {
        int a = s.length(), b = suf.length();
        return a >= b && s.substring(a - b).toLowerCase().equals(suf);
    }
}
