package reader;

import java.io.*;
import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connection;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

/**
 * SD-card access via JSR-75 FileConnection. Books live in "Books/" on the
 * memory card as UTF-8 .txt (produced by the desktop converter from EPUB/PDF).
 *
 * URL hygiene: FileConnection URLs must be percent-encoded -- a space (a card
 * root can be named "Memory card/") or a non-ASCII char (e.g. a Cyrillic book
 * filename) is otherwise rejected with "Malformed URL". Every open goes through
 * enc(). All opens also catch RuntimeException so a bad URL degrades to a
 * clean error instead of crashing the app.
 *
 * FileConnection is a protected API: the phone prompts for file access on first
 * use ("Always allow" stops the prompts).
 */
public final class Sd {

    private static final String FOLDER = "Books/";
    // No leading dot: Nokia FileConnection often refuses to create dot-files,
    // which silently killed progress saving.
    private static final String STATE = "reader_state.dat";
    private static final String HEX = "0123456789ABCDEF";

    private String baseUrl;
    private boolean available;
    private String problem;
    private String report = "";
    private String lastIo = "(no save yet)";

    private FileConnection openFc;
    private InputStream openIn;

    public Sd() {
        String prop = null;
        try { prop = System.getProperty("fileconn.dir.memorycard"); } catch (Throwable t) {}

        StringBuffer r = new StringBuffer();
        r.append("memorycard=").append(prop == null ? "(null)" : prop).append("\n");

        String cardUrl = (prop != null && prop.length() > 0) ? prop : null;
        String chosenRoot = null;
        Vector roots = listRootsSafe();
        r.append("roots=");
        for (int i = 0; i < roots.size(); i++) {
            r.append("[").append((String) roots.elementAt(i)).append("]");
        }
        r.append("\n");
        if (cardUrl == null) {
            chosenRoot = pickCardRoot(roots);
            cardUrl = chosenRoot;
        }

        if (cardUrl == null) {
            problem = "No memory card found. Insert the SD card.";
            available = false;
            report = r.toString();
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
        r.append("base=").append(baseUrl);
        report = r.toString();
    }

    public boolean isAvailable() { return available; }
    public String getProblem() { return problem; }
    public String getBaseUrl() { return baseUrl; }
    public String getReport() { return report + "\nstate: " + lastIo; }

    private static Vector listRootsSafe() {
        Vector v = new Vector();
        try {
            Enumeration e = FileSystemRegistry.listRoots();
            while (e != null && e.hasMoreElements()) {
                v.addElement((String) e.nextElement());
            }
        } catch (Throwable t) {
            // leave empty
        }
        return v;
    }

    private String pickCardRoot(Vector roots) {
        String first = null, nonC = null;
        for (int i = 0; i < roots.size(); i++) {
            String rt = (String) roots.elementAt(i);
            if (first == null) first = rt;
            String low = rt.toLowerCase();
            if (low.indexOf("card") >= 0 || low.indexOf("mmc") >= 0
                    || low.indexOf("memory") >= 0 || low.startsWith("e:")) {
                return rt;
            }
            if (!low.startsWith("c:")) nonC = rt;
        }
        if (nonC != null) return nonC;
        return first;
    }

    /** Percent-encode a file URL: keep unreserved chars + '/' and ':', UTF-8 %XX the rest. */
    private static String enc(String s) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == ':' || c == '-' || c == '_' || c == '.' || c == '~') {
                b.append(c);
            } else {
                byte[] bytes;
                try { bytes = ("" + c).getBytes("UTF-8"); }
                catch (Exception e) { bytes = new byte[] { (byte) c }; }
                for (int k = 0; k < bytes.length; k++) {
                    int v = bytes[k] & 0xff;
                    b.append('%').append(HEX.charAt(v >> 4)).append(HEX.charAt(v & 0xf));
                }
            }
        }
        return b.toString();
    }

    private static Connection open(String url, int mode) throws IOException {
        return Connector.open(enc(url), mode);
    }

    /** Names of .txt books in Books/ (empty if the folder is missing or unreadable). */
    public String[] listBooks() {
        Vector v = new Vector();
        FileConnection dir = null;
        try {
            dir = (FileConnection) open(baseUrl, Connector.READ);
            if (dir.exists()) {
                Enumeration e = dir.list();
                while (e != null && e.hasMoreElements()) {
                    String n = (String) e.nextElement();
                    if (!n.endsWith("/") && endsWithIgnoreCase(n, ".txt")) {
                        v.addElement(n);
                    }
                }
            }
        } catch (Exception e) {
            // treat as empty; report captured elsewhere
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
            fc = (FileConnection) open(baseUrl + name, Connector.READ);
            return fc.exists() ? fc.fileSize() : 0;
        } catch (RuntimeException e) {
            throw new IOException("bad path: " + e.getMessage());
        } finally {
            closeFc(fc);
        }
    }

    /** Open (or reopen) a book for forward streaming, positioned at byteOffset. */
    public void openStream(String name, long byteOffset) throws IOException {
        closeStream();
        try {
            openFc = (FileConnection) open(baseUrl + name, Connector.READ);
            openIn = openFc.openInputStream();
        } catch (RuntimeException e) {
            throw new IOException("bad path: " + e.getMessage());
        }
        skipFully(openIn, byteOffset);
    }

    public byte[] readMore(int len) throws IOException {
        if (openIn == null) return new byte[0];
        byte[] tmp = new byte[len];
        int got = 0;
        while (got < len) {
            int rd = openIn.read(tmp, got, len - got);
            if (rd < 0) break;
            got += rd;
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
            fc = (FileConnection) open(baseUrl + STATE, Connector.READ);
            if (!fc.exists()) return null;
            int size = (int) fc.fileSize();
            in = fc.openInputStream();
            DataInputStream din = new DataInputStream(in);
            byte[] b = new byte[size];
            din.readFully(b);
            lastIo = "read ok (" + size + "B)";
            return b;
        } catch (Exception e) {
            lastIo = "read ERR: " + e;
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
            fc = (FileConnection) open(baseUrl, Connector.READ_WRITE);
            if (!fc.exists()) fc.mkdir();
            closeFc(fc); fc = null;
            fc = (FileConnection) open(baseUrl + STATE, Connector.READ_WRITE);
            if (fc.exists()) fc.delete();
            fc.create();
            out = fc.openOutputStream();
            out.write(data);
            out.flush();
            lastIo = "write ok (" + data.length + "B)";
        } catch (Exception e) {
            lastIo = "write ERR: " + e;
        } finally {
            if (out != null) try { out.close(); } catch (IOException e) {}
            closeFc(fc);
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) break;
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
