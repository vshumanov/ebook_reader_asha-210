package reader;

/**
 * UTF-8 helpers for byte-accurate paging.
 *
 * The reader tracks reading position as a BYTE offset into the file (so it
 * never loads the whole book), but lays out text in decoded chars. These
 * helpers bridge the two: decode only whole UTF-8 sequences from a byte window,
 * and compute the byte length of any decoded prefix so the next page's byte
 * offset is exact.
 */
public final class Utf8 {

    private Utf8() {}

    /** Number of UTF-8 bytes a single BMP/supplementary char range would use. */
    public static int byteLen(String s) {
        int n = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0xD800 && c <= 0xDBFF) {
                // high surrogate: the pair (this + following low) encodes to 4 bytes
                n += 4;
                i++; // skip the low surrogate
            } else if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else {
                n += 3;
            }
        }
        return n;
    }

    /**
     * Largest prefix length (in bytes) of buf[0..len) that contains only whole
     * UTF-8 sequences -- i.e. drops a trailing partial multi-byte char.
     */
    public static int completeByteLen(byte[] buf, int len) {
        if (len <= 0) {
            return 0;
        }
        // Walk back over continuation bytes (10xxxxxx).
        int i = len - 1;
        int cont = 0;
        while (i >= 0 && (buf[i] & 0xC0) == 0x80) {
            cont++;
            i--;
        }
        if (i < 0) {
            return 0; // all continuation bytes (shouldn't happen at a real boundary)
        }
        int lead = buf[i] & 0xff;
        int need;
        if (lead < 0x80) {
            need = 1;
        } else if ((lead & 0xE0) == 0xC0) {
            need = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            need = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            need = 4;
        } else {
            need = 1; // invalid lead; treat as single byte
        }
        int have = cont + 1;
        if (have >= need) {
            return len;           // the last sequence is complete
        }
        return i;                 // cut before the incomplete lead byte
    }

    /** Decode the whole-char prefix of buf[0..len) as a UTF-8 String. */
    public static String decode(byte[] buf, int len) {
        int good = completeByteLen(buf, len);
        try {
            return new String(buf, 0, good, "UTF-8");
        } catch (Exception e) {
            return new String(buf, 0, good);
        }
    }
}
