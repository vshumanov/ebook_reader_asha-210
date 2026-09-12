# Build & packaging notes

Target: **Nokia Asha 210**, Series 40 — **MIDP 2.0 / CLDC 1.1**.

## Build the MIDlet (Docker — verified path)

A MIDlet needs CLDC-range bytecode (modern JDKs can't emit it) and a
preverification pass (adds the CLDC StackMap attributes the phone's VM needs).
This does both in a JDK 8 container using ProGuard's `-microedition` mode
instead of a WTK `preverify` binary:

```bash
# one-time: fetch ProGuard next to the repo
curl -sSL -o /tmp/pg.zip \
  https://github.com/Guardsquare/proguard/releases/download/v7.4.2/proguard-7.4.2.zip
mkdir -p /tmp/pg && unzip -q /tmp/pg.zip -d /tmp/pg

# build
docker run --rm -v "$PWD":/src -v /tmp/pg:/pg \
  eclipse-temurin:8-jdk bash /src/build/docker-build.sh
```

Output: `dist/asha-reader.jar` + `dist/asha-reader.jad`. Copy **both** into the
same folder on the SD card and open the `.jad` on the phone to install. The
build prints the class version (major 47 = CLDC) and confirms StackMap
attributes were written; the jar contains only `reader.*` classes.

The `stubs/` tree is a compile-only stand-in for the `javax.microedition.*` API
(lcdui incl. Canvas/Graphics/Font, MIDlet, and JSR-75 io/io.file) with
**spec-correct constant values**, so constant-inlining is correct and no real
device jars are needed to compile. Stubs are never shipped.

## Validate the paging engine (no phone)

The pagination core is pure, MIDP-free Java:

```bash
javac -d bin src/reader/TextMeasure.java src/reader/Utf8.java \
             src/reader/Paginator.java test/PaginatorTest.java
java -cp bin PaginatorTest        # "ALL CHECKS PASS"
```

It verifies UTF-8 boundary handling, line wrapping, and that byte-offset paging
(the exact device loop) reconstructs a book with no bytes lost — using a tiny
window to stress multi-byte boundaries, with Cyrillic in the sample.

## Converting books

`converter/convert.py` (Python 3) turns EPUB/PDF/TXT into the clean UTF-8 `.txt`
the reader streams. EPUB and TXT use only the standard library; PDF needs
`pip install pypdf` (text-based PDFs only). Copy the output into a `Books`
folder on the SD card.

## On-card layout

```
<memory card>/Books/          <- put converted .txt files here
<memory card>/Books/.reader-state   <- per-book position/font/theme (auto)
```
