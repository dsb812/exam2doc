"""拍照预处理：透视矫正、去阴影、增强对比。纯 OpenCV，老 CPU 可跑。"""
from __future__ import annotations

import cv2
import numpy as np


def correct_perspective(img: np.ndarray) -> np.ndarray:
    """检测纸张四角并做透视矫正；检测失败时返回原图。"""
    h, w = img.shape[:2]
    scale = 1000.0 / max(h, w)
    small = cv2.resize(img, None, fx=scale, fy=scale) if scale < 1 else img
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blur, 50, 150)
    edges = cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=2)

    cnts, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return img
    cnt = max(cnts, key=cv2.contourArea)
    area = cv2.contourArea(cnt)
    # 纸张应占画面的大部分，否则视为没有有效边框（如平铺拍照局部）
    if area < 0.35 * small.shape[0] * small.shape[1]:
        return img

    peri = cv2.arcLength(cnt, True)
    approx = cv2.approxPolyDP(cnt, 0.02 * peri, True)
    if len(approx) != 4:
        return img

    pts = approx.reshape(4, 2).astype(np.float32) / scale
    return _warp_quad(img, pts)


def _warp_quad(img: np.ndarray, pts: np.ndarray) -> np.ndarray:
    rect = np.zeros((4, 2), dtype=np.float32)
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]   # 左上
    rect[2] = pts[np.argmax(s)]   # 右下
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]  # 右上
    rect[3] = pts[np.argmax(diff)]  # 左下

    w_top = np.linalg.norm(rect[1] - rect[0])
    w_bot = np.linalg.norm(rect[2] - rect[3])
    h_left = np.linalg.norm(rect[3] - rect[0])
    h_right = np.linalg.norm(rect[2] - rect[1])
    W, H = int(max(w_top, w_bot)), int(max(h_left, h_right))
    if W < 50 or H < 50 or W * H < 0.2 * img.shape[0] * img.shape[1]:
        return img
    dst = np.array([[0, 0], [W - 1, 0], [W - 1, H - 1], [0, H - 1]], dtype=np.float32)
    M = cv2.getPerspectiveTransform(rect, dst)
    return cv2.warpPerspective(img, M, (W, H), flags=cv2.INTER_CUBIC)


def remove_shadow(img: np.ndarray) -> np.ndarray:
    """形态学背景估计去阴影/不均匀光照，保留文字笔迹。"""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    # 大核闭运算估计背景亮度场
    k = max(img.shape[0] // 20, 21) | 1
    bg = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    bg = cv2.GaussianBlur(bg, (k, k), 0)
    # division 归一化
    norm = cv2.divide(gray, bg, scale=255)
    # 轻度压缩噪声
    norm = cv2.bilateralFilter(norm, 5, 30, 30)
    return cv2.cvtColor(norm, cv2.COLOR_GRAY2BGR)


def enhance(img: np.ndarray) -> np.ndarray:
    """轻量锐化增强，提升小字号识别率。"""
    blur = cv2.GaussianBlur(img, (0, 0), 2.0)
    return cv2.addWeighted(img, 1.4, blur, -0.4, 0)


def preprocess(img: np.ndarray, perspective: bool = True, shadow: bool = True) -> np.ndarray:
    out = img
    if perspective:
        out = correct_perspective(out)
    if shadow:
        out = remove_shadow(out)
    if out.shape[0] < 1600 and img.shape[0] < 1600:
        out = enhance(out)
    return out
