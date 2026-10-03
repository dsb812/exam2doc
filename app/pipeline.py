"""处理管线：图片字节 -> 预处理 -> 手写擦除 -> 表格/文本/公式/插图识别 -> 结构化结果。"""
from __future__ import annotations

import logging
import time
from dataclasses import asdict, dataclass, field

import cv2
import numpy as np

from . import erase as erase_mod
from . import preprocess as pre_mod
from .ocr_engine import (
    Line,
    PageResult,
    detect_table_regions,
    latex_engine,
    looks_like_math,
    ocr_lines,
    recognize_formula,
    recognize_table,
    sort_reading_order,
)

log = logging.getLogger(__name__)


@dataclass
class Options:
    perspective: bool = True
    shadow: bool = True
    erase_colors: list[str] = field(default_factory=list)  # ["red","blue","green"]
    erase_pencil: bool = False
    erase_thin_ink: bool = False
    tables: bool = True
    formulas: bool = True
    figures: bool = True


@dataclass
class LineOut:
    kind: str
    text: str = ""
    html: str | None = None
    latex: str | None = None
    score: float = 1.0
    box: list[list[float]] | None = None


@dataclass
class PageOut:
    index: int
    width: int
    height: int
    lines: list[LineOut]
    elapsed: float


def decode_image(data: bytes) -> np.ndarray:
    arr = np.frombuffer(data, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("无法解码图片，请使用 JPG/PNG 格式")
    # 过大的图降采样，控制 CPU 耗时
    h, w = img.shape[:2]
    if max(h, w) > 3200:
        s = 3200 / max(h, w)
        img = cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
    return img


def process_image(data: bytes, opt: Options, page_index: int = 0) -> PageOut:
    t0 = time.time()
    img = decode_image(data)
    H, W = img.shape[:2]

    if opt.perspective:
        img = pre_mod.correct_perspective(img)

    # 手写擦除必须在去阴影之前：去阴影会丢失色彩信息
    if opt.erase_colors or opt.erase_pencil or opt.erase_thin_ink:
        img = erase_mod.erase_handwriting(
            img,
            colors=opt.erase_colors,
            pencil=opt.erase_pencil,
            thin_ink=opt.erase_thin_ink,
        )

    if opt.shadow:
        img = pre_mod.remove_shadow(img)

    lines: list[Line] = []

    # 1) 表格区域
    table_regions: list[tuple[int, int, int, int]] = []
    if opt.tables:
        table_regions = detect_table_regions(img)
    for (x, y, w, h) in table_regions:
        crop = img[max(0, y - 4): y + h + 4, max(0, x - 4): x + w + 4]
        html = recognize_table(crop)
        box = np.array([[x, y], [x + w, y], [x + w, y + h], [x, y + h]], dtype=np.float32)
        if html:
            lines.append(Line(text="[表格]", score=1.0, box=box, kind="table", html=html))
        else:
            lines.append(Line(text="[表格]", score=1.0, box=box, kind="image", crop=crop))

    def in_table(l: Line) -> bool:
        cx, cy = l.cx, l.cy
        for (x, y, w, h) in table_regions:
            if x <= cx <= x + w and y <= cy <= y + h:
                return True
        return False

    # 2) 整页文本 OCR
    for ln in ocr_lines(img):
        if in_table(ln):
            continue
        lines.append(ln)

    # 3) 公式：疑似数学行裁剪送 LaTeX OCR（可选）
    latex = latex_engine() if opt.formulas else None
    if latex is not None:
        for ln in lines:
            if ln.kind == "text" and looks_like_math(ln.text):
                pad = 6
                x1, y1 = int(max(0, ln.x1 - pad)), int(max(0, ln.y1 - pad))
                x2, y2 = int(min(W, ln.x2 + pad)), int(min(H, ln.y2 + pad))
                res = recognize_formula(latex, img[y1:y2, x1:x2])
                if res and _latex_quality(res) >= _latex_quality(ln.text):
                    ln.kind = "formula"
                    ln.latex = res

    # 4) 插图块：无 OCR 覆盖、墨迹密集的大连通域
    if opt.figures:
        crops = _detect_figures(img, lines, table_regions)
        for (x, y, w, h, crop) in crops:
            box = np.array([[x, y], [x + w, y], [x + w, y + h], [x, y + h]], dtype=np.float32)
            ln = Line(text="[插图]", score=1.0, box=box, kind="image", crop=crop)
            lines.append(ln)

    lines = sort_reading_order(lines, img.shape[1])

    out_lines: list[LineOut] = []
    crops_map: dict[int, np.ndarray] = {}
    for ln in lines:
        out_lines.append(
            LineOut(
                kind=ln.kind,
                text=ln.text,
                html=ln.html,
                latex=ln.latex,
                score=round(ln.score, 3),
                box=[[round(float(p[0]), 1), round(float(p[1]), 1)] for p in ln.box],
            )
        )
        if ln.crop is not None:
            crops_map[len(out_lines) - 1] = ln.crop

    return PageOut(index=page_index, width=img.shape[1], height=img.shape[0], lines=out_lines,
                   elapsed=round(time.time() - t0, 1)), crops_map


def _latex_quality(s: str) -> float:
    if not s:
        return 0.0
    score = 0.0
    if "\\frac" in s or "\\sqrt" in s:
        score += 2
    score += sum(c.isalnum() for c in s) * 0.01
    score += s.count("\\") * 0.1
    return score


def _detect_figures(img, lines: list[Line], table_regions):
    """检测独立插图：非文字、非表格、墨迹密集的大块区域。"""
    H, W = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    ink = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 25, 12)
    # 去掉文字行/表格附近的区域
    for ln in lines:
        x1, y1, x2, y2 = int(ln.x1), int(ln.y1), int(ln.x2), int(ln.y2)
        pad = 8
        cv2.rectangle(
            ink, (max(0, x1 - pad), max(0, y1 - pad)), (min(W, x2 + pad), min(H, y2 + pad)), 0, -1
        )
    for (x, y, w, h) in table_regions:
        cv2.rectangle(ink, (x, y), (x + w, y + h), 0, -1)
    ink = cv2.morphologyEx(ink, cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))
    n, labels, stats, _ = cv2.connectedComponentsWithStats(ink, 8)
    out = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area < 0.004 * W * H or w < 80 or h < 80:
            continue
        density = area / (w * h)
        if density < 0.03:
            continue
        crop = img[y:y + h, x:x + w].copy()
        out.append((x, y, w, h, crop))
    out.sort(key=lambda r: (r[1], r[0]))
    return out[:6]
