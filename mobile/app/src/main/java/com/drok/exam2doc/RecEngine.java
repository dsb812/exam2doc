package com.drok.exam2doc;

import org.opencv.core.Core;
import org.opencv.core.Mat;
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

/** 文本识别：PP-OCRv6 rec，字典从模型元数据 "character" 读取，CTC 贪心解码。 */
public class RecEngine {

    private static final int IMG_H = 48;
    private static final int IMG_W = 320;
    private static final float TEXT_SCORE = 0.5f;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final List<String> dict = new ArrayList<>();

    public static class Result {
        public final String text;
        public final float score;
        public Result(String text, float score) { this.text = text; this.score = score; }
    }

    RecEngine(OrtEnvironment env, byte[] model, boolean useNnapi) throws Exception {
        this.env = env;
        session = OrtSessions.create(env, model, useNnapi);
        Map<String, String> meta = session.getMetadata().getCustomMetadata();
        String chars = meta.get("character");
        if (chars == null) throw new IllegalStateException("模型元数据缺少 character 字典");
        Collections.addAll(dict, chars.split("\n", -1));
        if (!dict.isEmpty() && dict.get(dict.size() - 1).isEmpty()) dict.remove(dict.size() - 1);
    }

    public void close() throws Exception { session.close(); }

    /** box: [x1,y1,x2,y2,x3,y3,x4,y4]（原图坐标）；bgr 为原图。 */
    public Result recognize(Mat bgr, float[] box) throws Exception {
        Mat crop = cropBox(bgr, box);
        Mat resized = new Mat();
        Size cs = crop.size();
        float ratio = (float) (cs.width / cs.height);
        if (ratio * IMG_H >= IMG_W) {
            Imgproc.resize(crop, resized, new Size(IMG_W, IMG_H));
        } else {
            Imgproc.resize(crop, resized, new Size((float) cs.width * IMG_H / cs.height, IMG_H));
            int pad = IMG_W - resized.cols();
            if (pad > 0) {
                Mat padded = new Mat();
                Core.copyMakeBorder(resized, padded, 0, pad, 0, 0, Core.BORDER_CONSTANT, new Scalar(127, 127, 127));
                resized.release();
                resized = padded;
            }
        }
        crop.release();
        float[] input = normalizeCHW(resized);
        int rw = resized.cols(), rh = resized.rows();
        resized.release();

        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input),
                new long[]{1, 3, rh, rw});
             OrtSession.Result res = session.run(Collections.singletonMap("x", t))) {

            Object obj = res.get(0).getValue();
            float[][] logits;   // [T][C]
            if (obj instanceof float[][][] f3) logits = f3[0];
            else throw new IllegalStateException("rec output shape unexpected");

            return ctcDecode(logits);
        }
    }

    private Result ctcDecode(float[][] logits) {
        int T = logits.length, C = logits[0].length;
        StringBuilder sb = new StringBuilder();
        float scoreSum = 0; int scoreN = 0;
        int prev = 0;
        for (float[] frame : logits) {
            int best = 0; float bv = frame[0];
            for (int i = 1; i < C; i++) if (frame[i] > bv) { bv = frame[i]; best = i; }
            if (best != 0 && best != prev) {
                int dictIdx = best - 1;   // 0 为 blank
                if (dictIdx < dict.size()) {
                    sb.append(dict.get(dictIdx));
                    scoreSum += bv; scoreN++;
                } else if (dictIdx == dict.size()) {
                    sb.append(' ');       // 空格位
                    scoreSum += bv; scoreN++;
                }
            }
            prev = best;
        }
        String text = sb.toString().trim();
        float score = scoreN > 0 ? scoreSum / scoreN : 0f;
        if (score < TEXT_SCORE) text = "";
        return new Result(text, score);
    }

    private static Mat cropBox(Mat bgr, float[] box) {
        // box: 左上-右上-右下-左下
        float[] xs = {box[0], box[2], box[4], box[6]};
        float[] ys = {box[1], box[3], box[5], box[7]};
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -1, maxY = -1;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        int w = Math.max(2, (int) (maxX - minX));
        int h = Math.max(2, (int) (maxY - minY));
        Mat src = new Mat(4, 1, org.opencv.core.CvType.CV_32FC2);
        Mat dst = new Mat(4, 1, org.opencv.core.CvType.CV_32FC2);
        src.put(0, 0, box[0], box[1], box[2], box[3], box[4], box[5], box[6], box[7]);
        dst.put(0, 0, 0f, 0f, w - 1f, 0f, w - 1f, h - 1f, 0f, h - 1f);
        Mat M = Imgproc.getPerspectiveTransform(src, dst);
        Mat out = new Mat();
        Imgproc.warpPerspective(bgr, out, M, new Size(w, h));
        src.release(); dst.release(); M.release();
        return out;
    }

    private static float[] normalizeCHW(Mat bgr) {
        int h = bgr.rows(), w = bgr.cols(), ch = 3;
        float[] out = new float[ch * h * w];
        byte[] px = new byte[h * w * ch];
        bgr.get(0, 0, px);
        for (int c = 0; c < ch; c++)
            for (int i = 0; i < h * w; i++) {
                float v = (px[i * ch + c] & 0xFF) / 255f;
                out[c * h * w + i] = (v - 0.5f) / 0.5f;
            }
        return out;
    }
}
