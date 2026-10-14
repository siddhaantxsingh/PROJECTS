#!/usr/bin/env python3
"""Render master/OWNER_ACTION_PLAN_20_PROJECTS.md (a small Markdown subset) to a PDF.

Supported: '# ' / '## ' / '### ' headings, paragraphs, '- ' bullets, '1. ' numbered
steps, '> ' callouts, fenced ``` code blocks, '|' tables (first row = header),
'---pagebreak---', **bold** and `code` inline.

Usage: python3 master/tools/build_owner_plan.py [in.md] [out.pdf]
"""
import html
import re
import sys
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (KeepTogether, PageBreak, Paragraph, Preformatted,
                                SimpleDocTemplate, Spacer, Table, TableStyle)
from reportlab.platypus.tableofcontents import TableOfContents

FONT_DIR = Path("/usr/share/fonts/truetype/dejavu")
for name, file in [("Sans", "DejaVuSans.ttf"), ("Sans-Bold", "DejaVuSans-Bold.ttf"),
                   ("Mono", "DejaVuSansMono.ttf")]:
    pdfmetrics.registerFont(TTFont(name, str(FONT_DIR / file)))
pdfmetrics.registerFontFamily("Sans", normal="Sans", bold="Sans-Bold", italic="Sans",
                              boldItalic="Sans-Bold")

INK = colors.HexColor("#1f2933")
ACCENT = colors.HexColor("#1d4e89")
MUTED = colors.HexColor("#52606d")
RULE = colors.HexColor("#cbd2d9")
CODE_BG = colors.HexColor("#f2f4f7")
NOTE_BG = colors.HexColor("#fff7e6")

base = dict(fontName="Sans", textColor=INK, alignment=TA_LEFT)
S = {
    "title": ParagraphStyle("title", fontSize=22, leading=27, spaceAfter=6, textColor=ACCENT,
                            fontName="Sans-Bold"),
    "subtitle": ParagraphStyle("subtitle", fontSize=11, leading=15, textColor=MUTED, fontName="Sans",
                               spaceAfter=14),
    "h1": ParagraphStyle("h1", fontSize=15, leading=19, spaceBefore=6, spaceAfter=6,
                         textColor=ACCENT, fontName="Sans-Bold"),
    "h2": ParagraphStyle("h2", fontSize=12, leading=15, spaceBefore=9, spaceAfter=4,
                         textColor=INK, fontName="Sans-Bold"),
    "h3": ParagraphStyle("h3", fontSize=10, leading=13, spaceBefore=6, spaceAfter=2,
                         textColor=ACCENT, fontName="Sans-Bold"),
    "p": ParagraphStyle("p", fontSize=9, leading=12.5, spaceAfter=4, **base),
    "li": ParagraphStyle("li", fontSize=9, leading=12.5, leftIndent=12, bulletIndent=2,
                         spaceAfter=1.5, **base),
    "step": ParagraphStyle("step", fontSize=9, leading=12.5, leftIndent=16, bulletIndent=0,
                           spaceAfter=2.5, **base),
    "note": ParagraphStyle("note", fontSize=8.8, leading=12, backColor=NOTE_BG, borderPadding=5,
                           spaceBefore=4, spaceAfter=8, leftIndent=4, rightIndent=4, **base),
    "code": ParagraphStyle("code", fontName="Mono", fontSize=7.6, leading=9.6, backColor=CODE_BG,
                           borderPadding=4, leftIndent=6, rightIndent=4, spaceBefore=2,
                           spaceAfter=6, textColor=INK),
    "cell": ParagraphStyle("cell", fontSize=7.6, leading=9.6, **base),
    "cellh": ParagraphStyle("cellh", fontSize=7.6, leading=9.6, fontName="Sans-Bold",
                            textColor=colors.white),
}


def inline(text):
    t = html.escape(text, quote=False)
    t = re.sub(r"`([^`]+)`", r'<font name="Mono" size="8">\1</font>', t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"(?<![\w*])\*([^*\s][^*]*)\*(?!\w)", r"<i>\1</i>", t)
    return t


class Doc(SimpleDocTemplate):
    def afterFlowable(self, f):
        if isinstance(f, Paragraph) and f.style.name in ("h1", "h2"):
            level = 0 if f.style.name == "h1" else 1
            text = f.getPlainText()
            key = f"h{id(f)}"
            self.canv.bookmarkPage(key)
            self.canv.addOutlineEntry(text, key, level=level, closed=level > 0)
            self.notify("TOCEntry", (level, text, self.page, key))


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont("Sans", 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, 10 * mm, "Owner action plan — 20 projects")
    canvas.drawRightString(A4[0] - 18 * mm, 10 * mm, f"page {doc.page}")
    canvas.setStrokeColor(RULE)
    canvas.line(18 * mm, 13 * mm, A4[0] - 18 * mm, 13 * mm)
    canvas.restoreState()


def table(rows, width):
    header, body = rows[0], rows[1:]
    data = [[Paragraph(inline(c), S["cellh"]) for c in header]]
    data += [[Paragraph(inline(c), S["cell"]) for c in r] for r in body]
    n = len(header)
    # weight columns by average text length, clamped
    lens = [max(6, min(60, sum(len(r[i]) if i < len(r) else 0 for r in rows) / len(rows)))
            for i in range(n)]
    total = sum(lens)
    widths = [width * l / total for l in lens]
    t = Table(data, colWidths=widths, repeatRows=1)
    t.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), ACCENT),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("GRID", (0, 0), (-1, -1), 0.4, RULE),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f8fafc")]),
        ("LEFTPADDING", (0, 0), (-1, -1), 3), ("RIGHTPADDING", (0, 0), (-1, -1), 3),
        ("TOPPADDING", (0, 0), (-1, -1), 2), ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
    ]))
    return t


def build(src, out):
    lines = Path(src).read_text(encoding="utf-8").splitlines()
    doc = Doc(str(out), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
              topMargin=16 * mm, bottomMargin=18 * mm,
              title="Owner Action Plan — 20 Projects", author="Siddhant Singh")
    width = A4[0] - 36 * mm
    story = []
    toc = TableOfContents()
    toc.levelStyles = [ParagraphStyle("t0", fontName="Sans-Bold", fontSize=9, leading=12),
                       ParagraphStyle("t1", fontName="Sans", fontSize=8, leading=10.5,
                                      leftIndent=12, textColor=MUTED)]
    i, para, first_h1 = 0, [], True

    def flush():
        nonlocal para
        if para:
            story.append(Paragraph(inline(" ".join(para)), S["p"]))
            para = []

    while i < len(lines):
        ln = lines[i]
        s = ln.strip()
        if s.startswith("```"):
            flush()
            j = i + 1
            buf = []
            while j < len(lines) and not lines[j].strip().startswith("```"):
                buf.append(lines[j])
                j += 1
            story.append(Preformatted("\n".join(buf), S["code"], maxLineLength=118,
                                      newLineChars="  "))
            i = j + 1
            continue
        if s == "---pagebreak---":
            flush(); story.append(PageBreak()); i += 1; continue
        if s == "[[TOC]]":
            flush(); story.append(Paragraph("Contents", S["h3"])); story.append(toc); i += 1
            continue
        if s.startswith("|"):
            flush()
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            story.append(table(rows, width)); story.append(Spacer(1, 6))
            continue
        if s.startswith("#### TITLE "):
            flush(); story.append(Paragraph(inline(s[11:]), S["title"])); i += 1; continue
        if s.startswith("#### SUB "):
            flush(); story.append(Paragraph(inline(s[9:]), S["subtitle"])); i += 1; continue
        m = re.match(r"(#{1,3}) (.*)", s)
        if m:
            flush()
            lvl = len(m.group(1))
            if lvl == 1 and not first_h1:
                story.append(PageBreak())
            if lvl == 1:
                first_h1 = False
            story.append(Paragraph(inline(m.group(2)), S[f"h{lvl}"]))
            i += 1
            continue
        if s.startswith("> "):
            flush()
            buf = [s[2:]]
            i += 1
            while i < len(lines) and lines[i].strip().startswith("> "):
                buf.append(lines[i].strip()[2:]); i += 1
            story.append(Paragraph(inline(" ".join(buf)), S["note"]))
            continue
        m = re.match(r"(\d+)\. (.*)", s)
        if m:
            flush()
            story.append(Paragraph(inline(m.group(2)), S["step"], bulletText=f"{m.group(1)}."))
            i += 1
            continue
        if s.startswith("- "):
            flush()
            indent = len(ln) - len(ln.lstrip())
            st = S["li"] if indent < 2 else ParagraphStyle("li2", parent=S["li"], leftIndent=24,
                                                            bulletIndent=14)
            story.append(Paragraph(inline(s[2:]), st, bulletText="•"))
            i += 1
            continue
        if not s:
            flush(); i += 1; continue
        para.append(s)
        i += 1
    flush()
    doc.multiBuild(story, onFirstPage=footer, onLaterPages=footer)


if __name__ == "__main__":
    here = Path(__file__).resolve().parent.parent
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else here / "OWNER_ACTION_PLAN_20_PROJECTS.md"
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else here / "OWNER_ACTION_PLAN_20_PROJECTS.pdf"
    build(src, out)
    print(f"wrote {out}")
