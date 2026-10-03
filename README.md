# exam2doc · 拍照试卷生成电子版

拍照或扫描试卷，还原成**可编辑的 Word 文档**：印刷文字、表格、数学公式、插图一应俱全，
支持**手写擦除**（红笔批注、蓝笔、铅笔）把写满答案的卷子还原成可打印的空白卷。

**全程本地 CPU 处理，无需显卡、无需联网，数据不出本机。**

## 功能特性

- 📄 **文字识别**：基于 PP-OCRv6（RapidOCR），简体中文试卷实测置信度 0.96+
- 📊 **表格还原**：自动检测有线格表格，导出为 Word 原生表格（单元格可编辑、支持合并单元格）
- ∑ **公式识别**：数学公式行转 LaTeX，导出为 Word 原生可编辑公式（OMML），不是图片
- 🖼 **插图保留**：自动裁剪独立插图区域，按原位置贴回文档
- 🧹 **手写擦除**：颜色过滤（红/蓝/绿笔）+ 浅灰铅笔剔除 + OpenCV 修复，还原空白卷
- 📐 **拍照预处理**：透视矫正（纸张四角检测）+ 形态学去阴影/不均匀光照
- 🔍 **网页对照校对**：左侧原图、右侧识别结果逐块可编辑，修改后一并写入 Word
- 📱 **手机上传**：手机连同一 WiFi，浏览器即可拍照上传

## 快速开始

```bat
:: Windows（Python 3.11）
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
run.bat
```

浏览器访问 `http://127.0.0.1:8484`：

1. 拖入试卷照片（支持多张）
2. 勾选识别选项与手写擦除档位
3. 开始识别（每页约 5~60 秒，视分辨率与选项）
4. 对照校对 → 导出 `.docx`

## 技术架构

```
照片 → 预处理（透视矫正/去阴影）
     → 手写擦除（HSV颜色过滤 + 笔画形态学 + Telea修复）
     → 版面启发（OpenCV线格检测表格 / 双栏检测 / 大墨迹块插图）
     → 识别（RapidOCR 文本 · RapidTable 表格 · RapidLaTeXOCR 公式）
     → 组装导出（python-docx + latex2mathml + mathml2omml → 原生公式/表格）
```

| 环节 | 方案 | 说明 |
|---|---|---|
| 文本 OCR | [RapidOCR](https://github.com/RapidAI/RapidOCR) 3.9（PP-OCRv6） | ONNX Runtime CPU 推理，兼容无 AVX2 老 CPU |
| 表格 | [RapidTable](https://github.com/RapidAI/RapidTable) 3.0（SLANet-plus） | 表格结构 + 单元格 OCR 端到端 |
| 公式 | [RapidLaTeXOCR](https://github.com/RapidAI/RapidLaTeXOCR) | 可选组件，模型就绪后自动启用 |
| 修复 | OpenCV `inpaint`（Telea） | 零模型、秒级 |
| 服务 | FastAPI + 原生 HTML 前端 | 单文件页面，无构建步骤 |

## 手写擦除档位

| 档位 | 原理 | 适用 |
|---|---|---|
| 红/蓝/绿笔 | HSV 色域 + 通道运算 | 批注、打分、彩笔填空，效果好 |
| 铅笔 | 灰度区间（浅灰笔迹） | 铅笔作答，多数有效 |
| 细黑笔迹 | 笔画宽度估计区分手写/印刷 | 黑笔填空，慎用（可能误伤印刷体） |

> 黑色水笔大面积手写（如手写作文）暂不能完美擦除，需要手写分割网络，见路线图。

## 项目结构

```
app/
  preprocess.py   透视矫正、去阴影
  erase.py        手写擦除
  ocr_engine.py   OCR/表格/公式引擎 + 版面启发
  pipeline.py     识别管线编排
  docx_build.py   DOCX 组装（原生表格/OMML公式/插图）
  main.py         FastAPI 服务与接口
web/index.html    校对前端（单页）
run.bat           一键启动
samples/          示例图片
```

## 已知限制

- 拍照质量决定上限：要求清晰、光线均匀、文字方向正确；长边 2000~3200 像素为宜
- 版面还原为"语义流式"（按阅读顺序），不逐像素复刻原卷排版；分栏会按左右栏顺序输出
- 无线格表格（纯空白排布）检测率有限
- 手写公式与印刷公式混排时，公式检测为启发式，个别行可能漏判

## 路线图

- [ ] 手写分割网络（IS-Net/DeepLabV3+ ONNX 化）：黑色水笔手写擦除
- [ ] 接入版面分析模型（PP-DocLayout）：替代启发式版面，提升复杂版式还原
- [ ] 双引擎比对校验：专用 OCR 字符级 diff 提示可疑识别结果
- [ ] 多页试卷聚合：题号感知的跨页题目切分
- [ ] 纯 CPU 弯曲矫正（UVDoc ONNX）：书页卷曲拍摄场景
- [ ] 批量导出 Excel（题库结构化）

## License

MIT
