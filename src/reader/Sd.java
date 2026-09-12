package reader;

import java.io.*;
import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

/**
 * SD-card access via JSR-75 FileConnection. Books live in "Books/" on the
 * memory card as UTF-8 .txt (produced by the desktop converter from EPUB/PDF).
 * Reading state is a small file in the same folder.
 *
 * Keeps ONE input stream open at a time so the reader can stream a book
 * forward without reloading it. FileConnection is a protected API: the phone
 * will prompt for file access on first use ("Always allow" stops the prompts).
 */
public final class Sd {

    private static final String FOLDER = "Books/";
    private static final String STATE = ".reader-state";

    private String baseUrl;
    private boolean available;
    private String problem;

    private FileConnection openFc;
    private InputStream openIn;

    public Sd() {
        String cardUrl = System.getProperty("fileconn.dir.memorycard");
        if (cardUrl == null || cardUrl.length() == 0) {
            cardUrl = pickCardRoot();
        }
        if (cardUrl == null) {
            problem = (problem != null) ? problem : "No memory card found. Insert the SD card.";
            available = false;
            return;
        }
        if (!cardUrl.startsWith("file:")) {
            cardUrl = "file:///" + cardUrl;
        }
        if (!cardUrl.endsWith("/")) {
            cardUrl = cardUrl + "/";
        }
        baseUrl = cardUrl + FOLDER;
        available = true;
    }

    public boolean isAvailable() { return available; }
    public String getProblem() { return problem; }
    public String getBaseUrl() { return baseUrl; }

    private String pickCardRoot() {
        Enumeration roots;
        try {
            roots = FileSystemRegistry.listRoots();
        } catch (Throwable t) {
            problem = "FileConnection (JSR-75) unavailable on this device.";
            return null;
        }
        String first = null, nonC = null;
        while (roots != null && roots.hasMoreElements()) {
            String r = (String) roots.nextElement();
            if (first == null) first = r;
            String low = r.toLowerCase();
            if (low.indexOf("card") >= 0 || low.indexOf("mmc") >= 0
                    || low.indexOf("memory") >= 0 || low.startsWith("e:")) {
                return r;
            }
            if (!low.startsWith("c:")) nonC = r;
        }
        return (nonC != null) ? nonC : first;
    }

    /** Names of .txt books in Books/ (empty if the folder is missing). */
    public String[] listBooks() {
        Vector v = new Vector();
        FileConnection dir = null;
        try {
            dir = (FileConnection) Connector.open(baseUrl, Connector.READ);
            if (dir.exists()) {
                Enumeration e = dir.list();
                while (e != null && e.hasMoreElements()) {
                    String n = (String) e.nextElement();
                    if (!n.endsWith("/") && endsWithIgnoreCase(n, ".txt")) {
                        v.addElement(n);
                    }
                }
            }
        } catch (IOException e) {
            // treat as empty
        } finally {
            closeFc(dir);
        }
        String[] out = new String[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = (String) v.elementAt(i);
        return out;
    }

    public long size(String name) throws IOException {
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(baseUrl + name, Connector.READ);
            return fc.exists() ? fc.fileSize() : 0;
        } finally {
            closeFc(fc);
        }
    }

    /** Open (or reopen) a book for forward streaming, positioned at byteOffset. */
    public void openStream(String name, long byteOffset) throws IOException {
        closeStream();
        openFc = (FileConnection) Connector.open(baseUrl + name, Connector.READ);
        openIn = openFc.openInputStream();
        skipFully(openIn, byteOffset);
    }

    /** Read up to len more bytes from the open stream; returns the actual bytes (may be shorter, empty at EOF). */
    public byte[] readMore(int len) throws IOException {
        if (openIn == null) return new byte[0];
        byte[] tmp = new byte[len];
        int got = 0;
        while (got < len) {
            int r = openIn.read(tmp, got, len - got);
            if (r < 0) break;
            got += r;
        }
        if (got == len) return tmp;
        byte[] out = new byte[got];
        System.arraycopy(tmp, 0, out, 0, got);
        return out;
    }

    public void closeStream() {
        if (openIn != null) {
            try { openIn.close(); } catch (IOException e) {}
            openIn = null;
        }
        closeFc(openFc);
        openFc = null;
    }

    public byte[] readState() {
        FileConnection fc = null;
        InputStream in = null;
        try {
            fc = (FileConnection) Connector.open(baseUrl + STATE, Connector.READ);
            if (!fc.exists()) return null;
            int size = (int) fc.fileSize();
            in = fc.openInputStream();
            DataInputStream din = new DataInputStream(in);
            byte[] b = new byte[size];
            din.readFully(b);
            return b;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException e) {}
            closeFc(fc);
        }
    }

    public void writeState(byte[] data) {
        FileConnection fc = null;
        OutputStream out = null;
        try {
            fc = (FileConnection) Connector.open(baseUrl, Connector.READ_WRITE);
            if (!fc.exists()) fc.mkdir();
            closeFc(fc); fc = null;
            fc = (FileConnection) Connector.open(baseUrl + STATE, Connector.READ_WRITE);
            if (fc.exists()) fc.delete();
            fc.create();
            out = fc.openOutputStream();
            out.write(data);
            out.flush();
        } catch (IOException e) {
            // best effort; losing the bookmark is non-fatal
        } finally {
            if (out != null) try { out.close(); } catch (IOException e) {}
            closeFc(fc);
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) break;   // EOF
                s = 1;
            }
            n -= s;
        }
    }

    private static boolean endsWithIgnoreCase(String s, String suf) {
        int a = s.length(), b = suf.length();
        return a >= b && s.substring(a - b).toLowerCase().equals(suf);
    }

    private static void closeFc(FileConnection fc) {
        if (fc != null) {
            try { fc.close(); } catch (IOException e) {}
        }
    }
}
