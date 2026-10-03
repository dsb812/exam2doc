"""DOCX 组装：文本段落 + 原生 Word 表格 + OMML 原生公式 + 图片裁剪块。
依赖 python-docx、latex2mathml、mathml2omml（纯 Python，无外部程序）。
"""
from __future__ import annotations

import io
import re
import xml.sax.saxutils as saxutils
from html.parser import HTMLParser
from xml.etree import ElementTree as ET

import numpy as np
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import parse_xml
from docx.oxml.ns import qn
from docx.shared import Inches, Pt

from .ocr_engine import Line

M_NS = "http://schemas.openxmlformats.org/officeDocument/2006/math"
W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def latex_to_omml(latex: str) -> str | None:
    """LaTeX -> OMML XML 字符串（Word 原生可编辑公式）。失败返回 None。"""
    try:
        import latex2mathml.converter as l2m
        import mathml2omml

        mathml = l2m.convert(latex)
        omml = mathml2omml.convert(mathml)
        if not omml.lstrip().startswith("<m:oMath"):
            omml = f"<m:oMath>{omml}</m:oMath>"
        return omml
    except Exception:
        return None


def add_math_xml(p, latex: str) -> bool:
    omml = latex_to_omml(latex)
    if omml is None:
        return False
    xml = f'<m:oMathPara xmlns:m="{M_NS}">{omml}</m:oMathPara>'
    try:
        p._p.append(parse_xml(xml))
        return True
    except Exception:
        return False


class _TableParser(HTMLParser):
    """解析 rapid_table 输出的 HTML 表格 -> (rows, cells with rowspan/colspan)。"""

    def __init__(self):
        super().__init__()
        self.rows: list[list[dict]] = []
        self._row = None
        self._cell = None
        self._buf: list[str] = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "tr":
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            self._cell = {
                "rowspan": max(1, int(a.get("rowspan", 1))),
                "colspan": max(1, int(a.get("colspan", 1))),
                "text": "",
            }
            self._buf = []

    def handle_data(self, data):
        if self._cell is not None:
            self._buf.append(data)

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self._cell is not None:
            self._cell["text"] = "".join(self._buf).strip()
            self._row.append(self._cell)
            self._cell = None
        elif tag == "tr" and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None


def _strip_tags(html: str) -> str:
    return re.sub(r"<[^>]+>", "", html or "").strip()


def html_table_to_docx(doc: Document, html: str):
    tp = _TableParser()
    try:
        tp.feed(html)
    except Exception:
        tp = None
    rows = tp.rows if tp else []
    if not rows:
        # 解析失败兜底：当作图片处理调用方已覆盖
        raise ValueError("empty table html")
    n_cols = max(sum(c["colspan"] for c in row) for row in rows)
    n_rows = len(rows)
    table = doc.add_table(rows=n_rows, cols=n_cols)
    table.style = "Table Grid"

    occupied = [[False] * n_cols for _ in range(n_rows)]
    for ri, row in enumerate(rows):
        ci = 0
        for cell in row:
            while ci < n_cols and occupied[ri][ci]:
                ci += 1
            if ci >= n_cols:
                break
            rs, cs = cell["rowspan"], cell["colspan"]
            target = table.cell(ri, ci)
            if rs > 1 or cs > 1:
                try:
                    target = target.merge(table.cell(min(ri + rs - 1, n_rows - 1), min(ci + cs - 1, n_cols - 1)))
                except Exception:
                    pass
            for r_ in range(ri, min(ri + rs, n_rows)):
                for c_ in range(ci, min(ci + cs, n_cols)):
                    occupied[r_][c_] = True
            target.text = cell["text"]
            ci += cs


def _crop_png(crop: np.ndarray) -> bytes:
    import cv2

    ok, buf = cv2.imencode(".png", crop)
    return buf.tobytes() if ok else b""


def _setup_styles(doc: Document):
    style = doc.styles["Normal"]
    style.font.name = "Times New Roman"
    style.font.size = Pt(10.5)
    rpr = style.element.get_or_add_rPr()
    rfonts = rpr.get_or_add_rFonts()
    rfonts.set(qn("w:eastAsia"), "宋体")


def build_docx(pages: list[list[Line]], crops: dict[int, np.ndarray] | None = None) -> bytes:
    """pages: 每页的有序 Line 列表；crops: line.id -> 裁剪图（图片块用）。"""
    crops = crops or {}
    doc = Document()
    _setup_styles(doc)

    for pi, lines in enumerate(pages):
        if pi > 0:
            doc.add_page_break()
        for line in lines:
            if line.kind == "table" and line.html:
                try:
                    html_table_to_docx(doc, line.html)
                    continue
                except Exception:
                    pass
            if line.kind == "formula" and line.latex:
                p = doc.add_paragraph()
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                if not add_math_xml(p, line.latex):
                    p.add_run(line.latex).italic = True
                continue
            if line.kind == "image" and id(line) in crops:
                data = _crop_png(crops[id(line)])
                if data:
                    p = doc.add_paragraph()
                    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
                    p.add_run().add_picture(io.BytesIO(data), width=Inches(5.8))
                    continue
            # 普通文本行
            text = line.text if isinstance(line.text, str) else ""
            if line.kind == "formula" and line.latex is None:
                p = doc.add_paragraph()
                if not add_math_xml(p, line.text):
                    p.add_run(line.text)
                continue
            doc.add_paragraph(text)

    out = io.BytesIO()
    doc.save(out)
    return out.getvalue()
