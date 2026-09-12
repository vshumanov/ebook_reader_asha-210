# Asha Reader — offline e-book reader

A J2ME MIDlet (MIDP 2.0 / CLDC 1.1) for the **Nokia Asha 210**. A distraction-
free, fully offline reader: put books on the SD card, read them, pick up where
you left off. The text sibling of the audiobook player.

## How it works (and why it's built this way)

The Asha has ~4MB of heap, so **you can't load a book into memory**, and CLDC
has no way to parse EPUB (zipped XHTML) or PDF on the phone. So the split is:

- **On the desktop**, `converter/convert.py` turns **EPUB / PDF / TXT** into one
  clean UTF-8 `.txt` per book (each paragraph on one line; the phone wraps it).
- **On the phone**, the reader **streams** that `.txt` from the SD card,
  laying out one screenful at a time. Memory stays flat whether the book is 50KB
  or 5MB. Reading position is a byte offset, so bookmarks survive across
  sessions. Validated by `test/PaginatorTest` (byte-offset paging reconstructs
  the whole book, Cyrillic included, with a tiny stress window).

## Getting books onto the phone

```bash
# EPUB / TXT: no dependencies
python3 converter/convert.py mybook.epub -o out

# PDF: needs pypdf (text-based PDFs only; scanned PDFs have no text layer)
pip install pypdf
python3 converter/convert.py mybook.pdf -o out
```

Copy the resulting `out/*.txt` into a **`Books`** folder on the SD card. That's
it — open the app and they appear.

## Using it

- **Book list** → select a book to open (Open / Refresh / Exit).
- **Reading:** right / down / space = next page, left / up = previous.
  `1` / `3` = font smaller / larger, `5` = light / dark. The same actions are
  under the Options softkey (Back / Font ± / Light-Dark).
- Progress % shows bottom-left. Your place, font size, and theme are saved
  **per book** to `Books/.reader-state` on the card.
- First file access triggers a phone permission prompt — choose **"Always
  allow"**.

## Honest limitations

- **No on-device EPUB/PDF.** Those are handled by the desktop converter; the
  phone only reads `.txt`. This is a hardware reality, not a shortcut.
- **PDF quality varies.** `pypdf` extracts the text layer; complex layouts
  (columns, tables) may come out rough, and **scanned/image PDFs yield nothing**
  without OCR (out of scope). EPUB is far more reliable — prefer it when you have
  the choice.
- **Plain text only.** No images, fonts, or styling — clean prose is the point.
- **UTF-8 in, UTF-8 out.** The converter normalizes everything (incl. Cyrillic)
  to UTF-8 so the phone never guesses encodings.

## Layout

```
src/reader/     Utf8 + Paginator + TextMeasure (pure, unit-tested),
                Sd (SD streaming, JSR-75), Book (forward-streaming pager),
                FontMeasure + ReaderCanvas (full-screen UI), ReaderMIDlet
test/           PaginatorTest — desktop validation of the paging engine
converter/      convert.py — EPUB/PDF/TXT -> clean UTF-8 .txt
stubs/          compile-only MIDP/CLDC/JSR-75 type stubs (never shipped)
build/          manifest + docker-build.sh -> dist/asha-reader.jar + .jad
```

## Building

See **BUILD.md**. Short version (needs Docker): compiles in a JDK 8 container
and preverifies with ProGuard `-microedition`, producing
`dist/asha-reader.jar` + `.jad` to copy to the SD card.
