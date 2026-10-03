package com.drok.exam2doc;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 本机识别管线：检测 → 表格区域剔除 → 识别 → 阅读顺序 → 输出块序列（文本/表格图片）。 */
public class OcrPipeline {

    public static class Item {
        public String text;          // 文本（image 时为占位说明）
        public Bitmap image;         // 表格等图片块
        public float[] box;          // 8 值
        public boolean isImage;
    }

    private final DetEngine det;
    private final RecEngine rec;

    OcrPipeline(DetEngine det, RecEngine rec) {
        this.det = det;
        this.rec = rec;
    }

    /** bgr：OpenCV Mat（BGR）。返回按阅读顺序排列的结果块。 */
    public List<Item> run(Mat bgr) throws Exception {
        List<float[]> boxes = det.detect(bgr);

        List<int[]> tables = detectTableRegions(bgr);
        List<Item> items = new ArrayList<>();
        for (int[] r : tables) {
            Item it = new Item();
            it.isImage = true;
            it.text = "[表格]";
            it.box = new float[]{r[0], r[1], r[0] + r[2], r[1], r[0] + r[2], r[1] + r[3], r[0], r[1] + r[3]};
            it.image = matRegionToBitmap(bgr, r[0], r[1], r[2], r[3]);
            items.add(it);
        }

        for (float[] box : boxes) {
            float cx = (box[0] + box[4]) / 2, cy = (box[1] + box[5]) / 2;
            boolean inTable = false;
            for (int[] r : tables)
                if (cx >= r[0] && cx <= r[0] + r[2] && cy >= r[1] && cy <= r[1] + r[3]) { inTable = true; break; }
            if (inTable) continue;

            RecEngine.Result rr = rec.recognize(bgr, box);
            if (rr.text.isEmpty()) continue;
            Item it = new Item();
            it.text = rr.text;
            it.box = box;
            items.add(it);
        }

        sortByReadingOrder(items, bgr.cols());
        return items;
    }

    // ---------- 阅读顺序（与 PC 端 sort_reading_order 同逻辑） ----------

    private static void sortByReadingOrder(List<Item> items, int pageW) {
        if (items.size() >= 6) {
            float mid1 = pageW * 0.38f, mid2 = pageW * 0.62f;
            int crossing = 0;
            for (Item it : items)
                if (minX(it.box) < mid1 && maxX(it.box) > mid2) crossing++;
            if (crossing <= items.size() * 0.25) {
                List<Item> left = new ArrayList<>(), right = new ArrayList<>();
                for (Item it : items) {
                    if ((minX(it.box) + maxX(it.box)) / 2 <= pageW / 2f) left.add(it);
                    else right.add(it);
                }
                if (!left.isEmpty() && !right.isEmpty()) {
                    orderSingleCol(left);
                    orderSingleCol(right);
                    items.clear();
                    items.addAll(left);
                    items.addAll(right);
                    return;
                }
            }
        }
        orderSingleCol(items);
    }

    private static void orderSingleCol(List<Item> items) {
        items.sort(Comparator.comparingDouble(it -> (it.box[1] + it.box[5]) / 2));
        List<Item> out = new ArrayList<>();
        List<Item> row = new ArrayList<>();
        float rowY = Float.NaN;
        for (Item it : items) {
            float cy = (it.box[1] + it.box[5]) / 2;
            float h = it.box[5] - it.box[1];
            if (!Float.isNaN(rowY) && Math.abs(cy - rowY) > Math.max(h, 18) * 0.7) {
                row.sort(Comparator.comparingDouble(OcrPipeline::minX));
                out.addAll(row);
                row = new ArrayList<>();
            }
            row.add(it);
            float sum = 0;
            for (Item r : row) sum += (r.box[1] + r.box[5]) / 2;
            rowY = sum / row.size();
        }
        row.sort(Comparator.comparingDouble(OcrPipeline::minX));
        out.addAll(row);
        items.clear();
        items.addAll(out);
    }

    private static float minX(Item it) {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) m = Math.min(m, it.box[i * 2]);
        return m;
    }

    private static float maxX(Item it) {
        float m = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) m = Math.max(m, it.box[i * 2]);
        return m;
    }

    // ---------- 表格区域（OpenCV 线格检测，与 PC 端同逻辑） ----------

    private static List<int[]> detectTableRegions(Mat bgr) {
        int W = bgr.cols(), H = bgr.rows();
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
        Mat inv = new Mat();
        Imgproc.adaptiveThreshold(gray, inv, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY_INV, 15, -2);

        Mat horiz = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(W / 30.0 + 1, 1));
        Mat vert = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, H / 30.0 + 1));
        Mat hl = new Mat(), vl = new Mat();
        Imgproc.erode(inv, hl, horiz); Imgproc.dilate(hl, hl, horiz);
        Imgproc.erode(inv, vl, vert);  Imgproc.dilate(vl, vl, vert);
        Mat grid = new Mat();
        Core.add(hl, vl, grid);

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(grid, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        List<int[]> regions = new ArrayList<>();
        for (MatOfPoint c : contours) {
            Rect r = Imgproc.boundingRect(c);
            c.release();
            if (r.width < W * 0.2 || r.height < H * 0.04 || (double) r.width * r.height < W * H * 0.015)
                continue;
            regions.add(new int[]{r.x, r.y, r.width, r.height});
        }
        gray.release(); inv.release(); horiz.release(); vert.release();
        hl.release(); vl.release(); grid.release();
        return regions;
    }

    private static Bitmap matRegionToBitmap(Mat bgr, int x, int y, int w, int h) {
        x = Math.max(0, x); y = Math.max(0, y);
        w = Math.min(w, bgr.cols() - x); h = Math.min(h, bgr.rows() - y);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(bgr.submat(y, y + h, x, x + w), bmp);
        return bmp;
    }

    public static Bitmap bitmapToBitmap8888(Bitmap src, int maxSide) {
        int s = Math.max(src.getWidth(), src.getHeight());
        if (s > maxSide) {
            float k = (float) maxSide / s;
            src = Bitmap.createScaledBitmap(src, (int) (src.getWidth() * k), (int) (src.getHeight() * k), true);
        }
        return src.copy(Bitmap.Config.ARGB_8888, false);
    }

    public static Bitmap toBitmap(Mat mat) {
        Bitmap b = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, b);
        return b;
    }

    public static byte[] bitmapPng(Bitmap bmp) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }
}
