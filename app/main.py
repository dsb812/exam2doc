"""exam2doc 本地服务：上传照片 -> 后台处理 -> 轮询结果 -> 校对 -> 导出 docx。"""
from __future__ import annotations

import logging
import threading
import time
import uuid
from pathlib import Path

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from .docx_build import build_docx
from .ocr_engine import Line
from .pipeline import Options, PageOut, process_image

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("exam2doc")

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "work"
WORK.mkdir(exist_ok=True)
(WORK / "uploads").mkdir(exist_ok=True)
(WORK / "pages").mkdir(exist_ok=True)

APK_PATH = WORK / "apk" / "exam2doc.apk"


def _app_version() -> str:
    """从 Android 工程读取 versionName，与 APK 保持一致。"""
    try:
        import re
        gradle = (ROOT / "mobile" / "app" / "build.gradle").read_text(encoding="utf-8")
        m = re.search(r'versionName\s+"([^"]+)"', gradle)
        if m:
            return m.group(1)
    except Exception:
        pass
    return "0.0.0"


def _start_mdns():
    """注册 mDNS 服务，手机 App 可自动发现本机。"""
    try:
        import socket

        from zeroconf import ServiceInfo, Zeroconf

        zc = Zeroconf()
        ips = [
            socket.inet_aton(sa[4][0])
            for sa in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
            if not sa[4][0].startswith("127.")
        ]
        if not ips:
            return
        info = ServiceInfo(
            "_exam2doc._tcp.local.",
            "exam2doc._exam2doc._tcp.local.",
            addresses=ips,
            port=8484,
            properties={"path": "/"},
            server="exam2doc.local.",
        )
        zc.register_service(info)
        log.info("mDNS 服务已注册: exam2doc._exam2doc._tcp.local. port=8484")
    except Exception as e:
        log.warning("mDNS 注册失败（不影响手动输入地址连接）: %s", e)


_start_mdns()

app = FastAPI(title="exam2doc 试卷电子化")

# job_id -> dict(status, pages, crops, error, options)
JOBS: dict[str, dict] = {}
_LOCK = threading.Lock()


def _run_job(job_id: str, paths: list[Path], opt: Options):
    job = JOBS[job_id]
    try:
        pages_out: list[PageOut] = []
        crops_all: dict[str, np.ndarray] = {}
        for i, p in enumerate(paths):
            data = p.read_bytes()
            page, crops = process_image(data, opt, page_index=i)
            # 保存矫正后原图供前端对照
            img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
            page_img_path = WORK / "pages" / f"{job_id}_p{i}.jpg"
            cv2.imwrite(str(page_img_path), img, [cv2.IMWRITE_JPEG_QUALITY, 85])
            pageDict = {
                "index": page.index,
                "width": page.width,
                "height": page.height,
                "elapsed": page.elapsed,
                "image": f"/work/pages/{page_img_path.name}",
                "lines": [l.__dict__ for l in page.lines],
            }
            pages_out.append(page)  # keep PageOut objects for docx build
            job["pages_json"].append(pageDict)
            for idx, crop in crops.items():
                crops_all[f"{i}:{idx}"] = crop
            job["done"] = i + 1
        job["pages_obj"] = pages_out
        job["crops"] = crops_all
        job["status"] = "done"
    except Exception as e:
        log.exception("job failed")
        job["status"] = "error"
        job["error"] = str(e)


@app.post("/api/upload")
async def upload(
    files: list[UploadFile] = File(...),
    perspective: bool = Form(True),
    shadow: bool = Form(True),
    erase_colors: str = Form(""),
    erase_pencil: bool = Form(False),
    erase_thin_ink: bool = Form(False),
    tables: bool = Form(True),
    formulas: bool = Form(True),
    figures: bool = Form(True),
):
    opt = Options(
        perspective=perspective,
        shadow=shadow,
        erase_colors=[c.strip() for c in erase_colors.split(",") if c.strip()],
        erase_pencil=erase_pencil,
        erase_thin_ink=erase_thin_ink,
        tables=tables,
        formulas=formulas,
        figures=figures,
    )
    job_id = uuid.uuid4().hex[:12]
    paths = []
    for f in files:
        suffix = Path(f.filename or "img.jpg").suffix.lower() or ".jpg"
        if suffix not in (".jpg", ".jpeg", ".png", ".bmp", ".webp"):
            suffix = ".jpg"
        p = WORK / "uploads" / f"{job_id}_{len(paths)}{suffix}"
        p.write_bytes(await f.read())
        paths.append(p)
    with _LOCK:
        JOBS[job_id] = {
            "status": "running",
            "total": len(paths),
            "done": 0,
            "pages_json": [],
            "pages_obj": None,
            "crops": None,
            "error": None,
            "created": time.time(),
        }
    threading.Thread(target=_run_job, args=(job_id, paths, opt), daemon=True).start()
    return {"job_id": job_id, "total": len(paths)}


@app.get("/api/job/{job_id}")
async def job_status(job_id: str):
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "job not found")
    return {
        "status": job["status"],
        "total": job["total"],
        "done": job["done"],
        "error": job["error"],
        "pages": job["pages_json"],
    }


@app.post("/api/export/{job_id}")
async def export_docx(job_id: str, pages: list[dict]):
    """接收前端校对后的页面数据，重建 docx。"""
    if job_id not in JOBS:
        raise HTTPException(404, "job not found")
    pages_lines: list[list[Line]] = []
    for page in pages:
        lines: list[Line] = []
        for i, l in enumerate(page.get("lines", [])):
            box = np.array(l.get("box") or [[0, 0], [1, 0], [1, 1], [0, 1]], dtype=np.float32)
            kind = l.get("kind", "text")
            crop = None
            key = f"{page.get('index', 0)}:{i}"
            if kind == "image" and JOBS[job_id].get("crops"):
                crop = JOBS[job_id]["crops"].get(key)
            lines.append(
                Line(
                    text=l.get("text") or "",
                    score=float(l.get("score") or 1.0),
                    box=box,
                    kind=kind,
                    html=l.get("html"),
                    latex=l.get("latex"),
                    crop=crop,
                )
            )
        pages_lines.append(lines)
    data = build_docx(pages_lines, crops={})
    return Response(
        content=data,
        media_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        headers={"Content-Disposition": f'attachment; filename="exam_{job_id}.docx"'},
    )


@app.get("/api/export-md/{job_id}")
async def export_md(job_id: str):
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "job not found")
    parts = []
    for page in job["pages_json"]:
        for l in page["lines"]:
            if l["kind"] == "table" and l.get("html"):
                parts.append(l["html"])
            elif l["kind"] == "formula" and l.get("latex"):
                parts.append(f"$$ {l['latex']} $$")
            else:
                parts.append(l.get("text") or "")
    md = "\n\n".join(parts)
    return Response(content=md, media_type="text/markdown; charset=utf-8")


@app.delete("/api/job/{job_id}")
async def job_delete(job_id: str):
    JOBS.pop(job_id, None)
    return {"ok": True}


@app.get("/api/app-version")
async def app_version():
    """手机 App 检查更新用：返回最新版本与 APK 下载地址。"""
    return {"version": _app_version(), "apk": "/download/apk", "has_apk": APK_PATH.exists()}


@app.get("/download/apk")
async def download_apk():
    if not APK_PATH.exists():
        raise HTTPException(404, "APK 未部署，请先构建并复制到 work/apk/exam2doc.apk")
    return FileResponse(
        str(APK_PATH),
        media_type="application/vnd.android.package-archive",
        filename=f"exam2doc-{_app_version()}.apk",
    )


app.mount("/work", StaticFiles(directory=str(WORK)), name="work")
app.mount("/", StaticFiles(directory=str(ROOT / "web"), html=True), name="web")
