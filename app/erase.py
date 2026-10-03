"""手写擦除：颜色过滤（红/蓝笔）+ 浅灰铅笔剔除 + OpenCV 修复。
路线1（零模型），每张约 1-5 秒。黑笔与印刷黑字的区分用几何规则，作为可选档。
"""
from __future__ import annotations

import cv2
import numpy as np


def _color_mask(bgr: np.ndarray, colors: list[str]) -> np.ndarray:
    """生成彩色笔迹掩膜（红/蓝/绿笔）。"""
    mask = np.zeros(bgr.shape[:2], np.uint8)
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    b, g, r = cv2.split(bgr)
    if "red" in colors:
        m1 = cv2.inRange(hsv, (0, 60, 60), (12, 255, 255))
        m2 = cv2.inRange(hsv, (168, 60, 60), (180, 255, 255))
        mask |= m1 | m2
        # 通道法补充：红字在 G/B 通道很暗
        mask |= (cv2.subtract(g, b) > 30) & (r > 100)
    if "blue" in colors:
        mask |= cv2.inRange(hsv, (95, 60, 60), (135, 255, 255))
    if "green" in colors:
        mask |= cv2.inRange(hsv, (35, 60, 60), (90, 255, 255))
    return mask


def _pencil_mask(bgr: np.ndarray) -> np.ndarray:
    """浅灰铅笔字：亮度高但比纸面暗的细笔画。"""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    # 纸面白 ~220-255，铅笔灰 ~120-200，印刷黑 ~30-90
    m = cv2.inRange(gray, 110, 205)
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    return m


def _thin_ink_mask(bgr: np.ndarray, sw_max: float = 4.5) -> np.ndarray:
    """黑/蓝黑墨细笔迹（相对印刷粗字）：
    全墨迹掩膜中，用距离变换估计笔画宽度，剔除宽度超过阈值的连通域
    （印刷体笔画宽度接近但整体更粗、高度规整；此规则对填空题短手写效果好，作文类需路线2模型）。
    """
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    ink = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 15)
    dist = cv2.distanceTransform(ink, cv2.DIST_L2, 3)
    sw = 2.0 * dist  # 笔画宽度估计

    n, labels, stats, _ = cv2.connectedComponentsWithStats(ink, 8)
    mask = np.zeros_like(ink)
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        comp = labels == i
        widths = sw[comp]
        if widths.size == 0:
            continue
        med_w = float(np.median(widths[widths > 0])) if (widths > 0).any() else 0.0
        # 宽笔画连通域、大块区域，视为印刷内容
        if med_w > sw_max or h > 90 and w > 90:
            continue
        mask[comp] = 255
    return mask


def erase_handwriting(
    img: np.ndarray,
    colors: list[str] | None = None,
    pencil: bool = False,
    thin_ink: bool = False,
    inpaint_radius: int = 4,
) -> np.ndarray:
    """按开启的档位生成掩膜并修复。colors: ["red","blue","green"] 子集。"""
    colors = colors or []
    mask = _color_mask(img, colors) if colors else np.zeros(img.shape[:2], np.uint8)
    if pencil:
        mask |= _pencil_mask(img)
    if thin_ink:
        mask |= _thin_ink_mask(img)
    if mask.max() == 0:
        return img
    # 掩膜稍作膨胀覆盖笔画边缘反锯齿
    mask = cv2.dilate(mask, np.ones((3, 3), np.uint8), iterations=1)
    out = cv2.inpaint(img, mask, inpaint_radius, cv2.INPAINT_TELEA)
    return out
