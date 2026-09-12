#!/usr/bin/env python3
"""
Convert EPUB / PDF / TXT into the clean UTF-8 .txt the Asha reader streams.

Design: the phone only ever reads plain UTF-8 text where each paragraph is one
logical line (the reader word-wraps to the screen) and paragraphs are separated
by a blank line. All the messy parsing happens here, on the desktop.

  EPUB : pure Python stdlib (zip + HTML parsing), no dependencies.
  PDF  : needs `pypdf`  ->  pip install pypdf   (text-based PDFs only; a scanned
         PDF has no text layer and would need OCR, which is out of scope).
  TXT  : just normalized.

Usage:
  python3 convert.py INPUT [-o OUTDIR]        # default OUTDIR = ./out
Then copy OUTDIR/*.txt into the "Books" folder on the SD card.
"""
import sys, os, re, zipfile, argparse, html
from html.parser import HTMLParser
from xml.etree import ElementTree as ET


def normalize(paragraphs):
    """List of raw paragraph strings -> clean text, one line per paragraph,
    blank line between."""
    out = []
    for p in paragraphs:
        p = p.replace("\r", "\n")
        # de-hyphenate words broken across lines: "exam-\nple" -> "example"
        p = re.sub(r"(\w)-\n(\w)", r"\1\2", p)
        # collapse all internal whitespace (incl. newlines) to single spaces
        p = re.sub(r"\s+", " ", p).strip()
        if p:
            out.append(p)
    return "\n\n".join(out) + "\n"


class _Text(HTMLParser):
    BLOCK = {"p", "div", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
             "blockquote", "section", "article"}
    SKIP = {"script", "style", "head"}

    def __init__(self):
        super().__init__()
        self.parts = []
        self.skip = 0

    SEP = "\x00"  # block-boundary marker, distinct from any text newline

    def handle_starttag(self, tag, attrs):
        if tag in self.SKIP:
            self.skip += 1
        elif tag in self.BLOCK:
            self.parts.append(self.SEP)

    def handle_endtag(self, tag):
        if tag in self.SKIP and self.skip:
            self.skip -= 1
        elif tag in self.BLOCK:
            self.parts.append(self.SEP)

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)

    def text(self):
        return html.unescape("".join(self.parts))


def html_to_paragraphs(markup):
    p = _Text()
    try:
        p.feed(markup)
    except Exception:
        pass
    # split on the block-boundary markers the parser inserted
    return p.text().split("\x00")


def from_epub(path):
    title = None
    paragraphs = []
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        # find the OPF via container.xml, else guess
        opf = None
        if "META-INF/container.xml" in names:
            root = ET.fromstring(z.read("META-INF/container.xml"))
            for el in root.iter():
                if el.tag.endswith("rootfile") and el.get("full-path"):
                    opf = el.get("full-path"); break
        docs = []
        if opf and opf in names:
            base = os.path.dirname(opf)
            tree = ET.fromstring(z.read(opf))
            ns = {"opf": "http://www.idpf.org/2007/opf"}
            ids = {}
            for it in tree.iter():
                if it.tag.endswith("}item") or it.tag == "item":
                    ids[it.get("id")] = it.get("href")
                if it.tag.endswith("}title") or it.tag == "title":
                    if it.text and not title:
                        title = it.text.strip()
            for it in tree.iter():
                if it.tag.endswith("}itemref") or it.tag == "itemref":
                    href = ids.get(it.get("idref"))
                    if href:
                        full = os.path.normpath(os.path.join(base, href)).replace("\\", "/")
                        if full in names:
                            docs.append(full)
        if not docs:
            docs = sorted(n for n in names if n.lower().endswith((".xhtml", ".html", ".htm")))
        for d in docs:
            try:
                markup = z.read(d).decode("utf-8", "replace")
            except KeyError:
                continue
            paragraphs.extend(html_to_paragraphs(markup))
    return title, normalize(paragraphs)


def from_pdf(path):
    try:
        from pypdf import PdfReader
    except ImportError:
        sys.exit("PDF needs pypdf:  pip install pypdf   (text-based PDFs only)")
    reader = PdfReader(path)
    title = None
    try:
        if reader.metadata and reader.metadata.title:
            title = reader.metadata.title
    except Exception:
        pass
    pages = []
    for pg in reader.pages:
        try:
            pages.append(pg.extract_text() or "")
        except Exception:
            pages.append("")
    raw = "\n".join(pages)
    # split into paragraphs on blank lines; single newlines are within a paragraph
    paragraphs = re.split(r"\n\s*\n", raw)
    return title, normalize(paragraphs)


def from_txt(path):
    with open(path, "rb") as f:
        raw = f.read().decode("utf-8", "replace")
    paragraphs = re.split(r"\n\s*\n", raw)
    return None, normalize(paragraphs)


def safe_name(title, fallback):
    base = title or fallback
    base = re.sub(r"[^\w\-. ]+", "", base).strip().replace(" ", "_")
    return (base or fallback)[:60] + ".txt"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("-o", "--outdir", default="out")
    args = ap.parse_args()

    ext = os.path.splitext(args.input)[1].lower()
    stem = os.path.splitext(os.path.basename(args.input))[0]
    if ext == ".epub":
        title, text = from_epub(args.input)
    elif ext == ".pdf":
        title, text = from_pdf(args.input)
    elif ext in (".txt", ".text"):
        title, text = from_txt(args.input)
    else:
        sys.exit(f"unsupported input: {ext} (use .epub, .pdf, or .txt)")

    os.makedirs(args.outdir, exist_ok=True)
    out = os.path.join(args.outdir, safe_name(title, stem))
    with open(out, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"wrote {out}  ({len(text)} chars)")


if __name__ == "__main__":
    main()
