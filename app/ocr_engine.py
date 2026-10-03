"""识别引擎：RapidOCR(PP-OCR ONNX) 文本 + OpenCV 版面启发（分栏/表格检测）。
全部 ONNX Runtime CPU 推理，兼容无 AVX2 老 CPU。
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field

import cv2
import numpy as np

log = logging.getLogger(__name__)

_ENGINE = None


def get_engine():
    global _ENGINE
    if _ENGINE is None:
        from rapidocr import RapidOCR

        _ENGINE = RapidOCR()
    return _ENGINE


@dataclass
class Line:
    text: str
    score: float
    box: np.ndarray  # 4x2 float
    kind: str = "text"  # text | table | formula | image
    html: str | None = None  # 表格 HTML
    latex: str | None = None  # 公式 LaTeX
    crop: np.ndarray | None = None  # 图块裁剪

    @property
    def x1(self) -> float:
        return float(self.box[:, 0].min())

    @property
    def y1(self) -> float:
        return float(self.box[:, 1].min())

    @property
    def x2(self) -> float:
        return float(self.box[:, 0].max())

    @property
    def y2(self) -> float:
        return float(self.box[:, 1].max())

    @property
    def cy(self) -> float:
        return (self.y1 + self.y2) / 2

    @property
    def cx(self) -> float:
        return (self.x1 + self.x2) / 2


@dataclass
class PageResult:
    lines: list[Line] = field(default_factory=list)
    width: int = 0
    height: int = 0


def ocr_lines(img: np.ndarray, score_thres: float = 0.55) -> list[Line]:
    """整页 OCR，返回按阅读顺序排序的文本行。"""
    engine = get_engine()
    result = engine(img)
    lines: list[Line] = []
    if result is None or result.txts is None:
        return lines
    for box, txt, score in zip(result.boxes, result.txts, result.scores):
        if score < score_thres:
            continue
        lines.append(Line(text=str(txt), score=float(score), box=np.asarray(box, dtype=np.float32)))
    return sort_reading_order(lines, img.shape[1])


def sort_reading_order(lines: list[Line], page_w: int) -> list[Line]:
    """双栏检测 + 行内排序。双栏常见于试卷，若中缝有明显空白则分栏。"""
    if len(lines) < 6:
        return _order_single_col(lines)
    mid1, mid2 = page_w * 0.38, page_w * 0.62
    crossing = [ln for ln in lines if ln.x1 < mid1 and ln.x2 > mid2]
    if len(crossing) > len(lines) * 0.25:
        return _order_single_col(lines)
    left = [ln for ln in lines if ln.cx <= page_w / 2]
    right = [ln for ln in lines if ln.cx > page_w / 2]
    if not left or not right:
        return _order_single_col(lines)
    return _order_single_col(left) + _order_single_col(right)


def _order_single_col(lines: list[Line]) -> list[Line]:
    if not lines:
        return lines
    lines = sorted(lines, key=lambda l: l.cy)
    ordered: list[Line] = []
    row: list[Line] = []
    row_y = None
    for ln in lines:
        h = ln.y2 - ln.y1
        if row_y is not None and abs(ln.cy - row_y) > max(h, 18) * 0.7:
            ordered.extend(sorted(row, key=lambda l: l.x1))
            row = []
        row.append(ln)
        row_y = np.mean([l.cy for l in row])
    ordered.extend(sorted(row, key=lambda l: l.x1))
    return ordered


# ---------- 表格检测（有线格的表格，OpenCV 直线检测） ----------

def detect_table_regions(img: np.ndarray) -> list[tuple[int, int, int, int]]:
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    binary = cv2.adaptiveThreshold(~gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY, 15, -2)
    h, w = binary.shape

    horiz = cv2.erode(binary, cv2.getStructuringElement(cv2.MORPH_RECT, (w // 30 + 1, 1)))
    horiz = cv2.dilate(horiz, cv2.getStructuringElement(cv2.MORPH_RECT, (w // 30 + 1, 1)))
    vert = cv2.erode(binary, cv2.getStructuringElement(cv2.MORPH_RECT, (1, h // 30 + 1)))
    vert = cv2.dilate(vert, cv2.getStructuringElement(cv2.MORPH_RECT, (1, h // 30 + 1)))
    grid = cv2.add(horiz, vert)

    cnts, _ = cv2.findContours(grid, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    regions = []
    for c in cnts:
        x, y, bw, bh = cv2.boundingRect(c)
        if bw < w * 0.2 or bh < h * 0.04 or bw * bh < w * h * 0.015:
            continue
        regions.append((x, y, bw, bh))
    # 去重叠，保大者
    regions.sort(key=lambda r: r[2] * r[3], reverse=True)
    keep: list[tuple[int, int, int, int]] = []
    for r in regions:
        if all(not _overlap(r, k) for k in keep):
            keep.append(r)
    keep.sort(key=lambda r: (r[1], r[0]))
    return keep


def _overlap(a, b) -> bool:
    ax1, ay1, ax2, ay2 = a[0], a[1], a[0] + a[2], a[1] + a[3]
    bx1, by1, bx2, by2 = b[0], b[1], b[0] + b[2], b[1] + b[3]
    ix = max(0, min(ax2, bx2) - max(ax1, bx1))
    iy = max(0, min(ay2, by2) - max(ay1, by1))
    return ix > 0 and iy > 0


_table_engine = None


def recognize_table(crop: np.ndarray) -> str | None:
    """表格区域 -> HTML。失败返回 None。"""
    global _table_engine
    try:
        from rapid_table import RapidTable, ModelType, RapidTableInput

        if _table_engine is None:
            _table_engine = RapidTable(RapidTableInput(model_type=ModelType.SLANETPLUS, use_ocr=True))
        out = _table_engine(crop)
        html = None
        if hasattr(out, "pred_htmls") and out.pred_htmls:
            html = out.pred_htmls[0]
        elif hasattr(out, "pred_html") and out.pred_html:
            html = out.pred_html
        if html:
            return html
    except Exception as e:  # 模型缺失/推理失败时优雅降级
        log.warning("table engine unavailable: %s", e)
    return None


MATH_CHARS = set("=+−-×÷*/^√∫∑±≤≥≠≈∈∆°(){}[]|<>≤≥")

def looks_like_math(text: str) -> bool:
    """启发式：某行含较多数学符号且中文占比低 → 疑似公式行。"""
    if not text:
        return False
    n_math = sum(c in MATH_CHARS for c in text)
    n_cjk = sum("\u4e00" <= c <= "\u9fff" for c in text)
    return n_math >= 4 and n_cjk <= len(text) / 3


_frac_like = re.compile(r"[a-zA-Z0-9]\s*[+-]\s*[a-zA-Z0-9]|\\frac|\\sqrt|\^\{|_\{")


_latex_engine_inst = None


def latex_engine():
    """懒加载 LaTeX 公式识别（可选，模型需已下载；单例缓存避免重复初始化）。"""
    global _latex_engine_inst
    if _latex_engine_inst is not None:
        return _latex_engine_inst
    try:
        from rapid_latex_ocr import LaTeXOCR

        _latex_engine_inst = LaTeXOCR()
        return _latex_engine_inst
    except Exception as e:
        log.warning("latex engine unavailable: %s", e)
        return None


def recognize_formula(engine, crop: np.ndarray) -> str | None:
    try:
        res, elapse = engine(crop)
        if res and len(res.strip()) > 1:
            return res.strip()
    except Exception as e:
        log.warning("formula recognize failed: %s", e)
    return None
