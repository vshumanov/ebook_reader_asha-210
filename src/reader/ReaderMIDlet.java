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
    private String[] books = new String[0];
    private Command openCmd, refreshCmd, exitCmd, diagCmd;

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
        listScreen = new List("Books", List.IMPLICIT);
        books = sd.listBooks();
        for (int i = 0; i < books.length; i++) {
            listScreen.append(label(books[i]), null);
        }
        if (books.length == 0) {
            listScreen.append("(no books - add .txt to Books/ on card)", null);
        }
        openCmd = new Command("Open", Command.ITEM, 1);
        refreshCmd = new Command("Refresh", Command.SCREEN, 2);
        diagCmd = new Command("Diagnostics", Command.SCREEN, 3);
        exitCmd = new Command("Exit", Command.EXIT, 4);
        listScreen.addCommand(openCmd);
        listScreen.addCommand(refreshCmd);
        listScreen.addCommand(diagCmd);
        listScreen.addCommand(exitCmd);
        listScreen.setCommandListener(this);
        display.setCurrent(listScreen);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            destroyApp(true);
            notifyDestroyed();
        } else if (c == refreshCmd) {
            showBookList();
        } else if (c == diagCmd) {
            Alert a = new Alert("Diagnostics",
                    "found " + books.length + " book(s)\n" + sd.getReport(),
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
