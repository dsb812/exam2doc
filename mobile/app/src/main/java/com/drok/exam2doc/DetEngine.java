package com.drok.exam2doc;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** 文本检测：PP-OCRv6 det (DBNet)。参数与 PC 端 rapidocr config.yaml 完全一致。 */
public class DetEngine {

    private static final int LIMIT_SIDE = 736;      // limit_type: min
    private static final float THRESH = 0.3f;
    private static final float BOX_THRESH = 0.5f;
    private static final float UNCLIP_RATIO = 1.6f;

    private final OrtEnvironment env;
    private final OrtSession session;

    DetEngine(OrtEnvironment env, byte[] model, boolean useNnapi) throws Exception {
        this.env = env;
        session = OrtSessions.create(env, model, useNnapi);
    }

    public void close() throws Exception { session.close(); }

    /** 输入 BGR Mat，返回文本框（4x2，原图坐标系），按"左上-右上-右下-左下"。 */
    public List<float[]> detect(Mat bgr) throws Exception {
        Size origSize = bgr.size();
        // resize：短边补到 736，长宽取 32 的倍数（与 PC 端 limit_type=min 一致）
        int minSide = (int) Math.min(origSize.width, origSize.height);
        float ratio = minSide < LIMIT_SIDE ? (float) LIMIT_SIDE / minSide : 1.0f;
        int rw = Math.max(32, (int) (Math.round(origSize.width * ratio / 32.0) * 32));
        int rh = Math.max(32, (int) (Math.round(origSize.height * ratio / 32.0) * 32));
        Mat resized = new Mat();
        Imgproc.resize(bgr, resized, new Size(rw, rh), 0, 0, Imgproc.INTER_LINEAR);

        float[] input = normalizeCHW(resized);
        resized.release();

        long[] shape = {1, 3, rh, rw};
        List<float[]> boxes = new ArrayList<>();
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
             OrtSession.Result res = session.run(Collections.singletonMap("x", t))) {

            Object obj = res.get(0).getValue();
            float[][] prob;   // [H][W] prob map
            if (obj instanceof float[][][][] f4) prob = f4[0][0];
            else if (obj instanceof float[][][] f3) prob = f3[0];
            else throw new IllegalStateException("det output shape unexpected");

            boxes = extractBoxes(prob, ratio);
        }
        return boxes;
    }

    private static float[] normalizeCHW(Mat bgr) {
        int h = bgr.rows(), w = bgr.cols(), ch = 3;
        float[] out = new float[ch * h * w];
        byte[] px = new byte[h * w * ch];
        bgr.get(0, 0, px);
        // (x/255 - 0.5)/0.5，BGR 顺序
        for (int c = 0; c < ch; c++)
            for (int i = 0; i < h * w; i++) {
                float v = (px[i * ch + c] & 0xFF) / 255f;
                out[c * h * w + i] = (v - 0.5f) / 0.5f;
            }
        return out;
    }

    private List<float[]> extractBoxes(float[][] prob, float ratio) {
        int h = prob.length, w = prob[0].length;
        Mat probMat = new Mat(h, w, CvType.CV_32FC1);
        float[] flat = new float[h * w];
        for (int i = 0; i < h; i++) System.arraycopy(prob[i], 0, flat, i * w, w);
        probMat.put(0, 0, flat);

        Mat binary = new Mat();
        Imgproc.threshold(probMat, binary, THRESH, 255, Imgproc.THRESH_BINARY);
        binary.convertTo(binary, CvType.CV_8UC1);
        Imgproc.dilate(binary, binary, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2)));

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(binary, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<float[]> boxes = new ArrayList<>();
        for (MatOfPoint c : contours) {
            if (c.total() < 4) { c.release(); continue; }
            RotatedRect rect = Imgproc.minAreaRect(new org.opencv.core.MatOfPoint2f(c.toArray()));
            c.release();
            Size sz = rect.size;
            if (Math.min(sz.width, sz.height) < 2) continue;

            // box score：box 外接矩形内概率均值（fast 模式近似）
            org.opencv.core.Rect bb = rect.boundingRect();
            bb.x = Math.max(0, bb.x); bb.y = Math.max(0, bb.y);
            bb.width = Math.min(bb.width, w - bb.x); bb.height = Math.min(bb.height, h - bb.y);
            if (bb.width <= 0 || bb.height <= 0) continue;
            Scalar mean = Core.mean(probMat.submat(bb));
            if (mean.val[0] < BOX_THRESH) continue;

            // unclip：旋转矩形按 offset 双向外扩
            double offset = (sz.width * sz.height) * UNCLIP_RATIO / (2 * (sz.width + sz.height));
            Point[] pts = new Point[4];
            new RotatedRect(rect.center, new Size(sz.width + 2 * offset, sz.height + 2 * offset), rect.angle)
                    .points(pts);

            float[] box = new float[8];
            for (int i = 0; i < 4; i++) {
                box[i * 2] = (float) Math.max(0, Math.min(w - 1, pts[i].x)) / ratio;
                box[i * 2 + 1] = (float) Math.max(0, Math.min(h - 1, pts[i].y)) / ratio;
            }
            boxes.add(box);
        }
        probMat.release(); binary.release();
        return boxes;
    }
}
