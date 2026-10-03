package com.drok.exam2doc;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static android.provider.MediaStore.MediaColumns.DISPLAY_NAME;
import static android.provider.MediaStore.MediaColumns.IS_PENDING;
import static android.provider.MediaStore.MediaColumns.MIME_TYPE;

/** 本机识别：照片在手机上直接完成 OCR（PP-OCRv6 ONNX），可校对后保存 Word。 */
public class LocalOcrActivity extends Activity {

    private static final int REQ_CAMERA = 2001;
    private static final int REQ_PICK = 2002;
    private static final int MAX_SIDE = 2400;

    private Uri cameraUri;
    private LinearLayout resultsBox;
    private Button saveBtn, againBtn;
    private List<OcrPipeline.Item> items;
    private android.widget.CheckBox gpuToggle;

    // 引擎懒加载单例
    private static OcrPipeline pipeline;
    private static boolean pipelineNnapi;

    private static synchronized OcrPipeline getPipeline(boolean useNnapi) throws Throwable {
        if (pipeline == null || pipelineNnapi != useNnapi) {
            if (!OpenCVLoader.initLocal()) {
                throw new IllegalStateException("OpenCV 原生库加载失败，请反馈此问题");
            }
            ai.onnxruntime.OrtEnvironment env = ai.onnxruntime.OrtEnvironment.getEnvironment();
            byte[] det = readAsset("PP-OCRv6_det_small.onnx");
            byte[] rec = readAsset("PP-OCRv6_rec_small.onnx");
            pipeline = new OcrPipeline(new DetEngine(env, det, useNnapi),
                    new RecEngine(env, rec, useNnapi));
            pipelineNnapi = useNnapi;
        }
        return pipeline;
    }

    private static byte[] readAsset(String name) throws Exception {
        try (InputStream in = App.context().getAssets().open(name);
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showEntry();
    }

    private void showEntry() {
        items = null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F7FA);
        root.setPadding(64, 160, 64, 64);

        TextView title = new TextView(this);
        title.setText("本机识别");
        title.setTextSize(26);
        title.setTextColor(0xFF1F2937);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("首次使用需加载模型（约几秒）。识别文字准确率与电脑相同；\n"
                + "表格以图片形式嵌入 Word；公式按普通文本输出。\n"
                + "需要完整表格/公式还原请回主页选「连接电脑」。");
        hint.setTextSize(13);
        hint.setTextColor(0xFF6B7280);
        hint.setPadding(0, 16, 0, 48);
        root.addView(hint);

        Button camera = new Button(this);
        camera.setText("📷 拍照识别");
        camera.setTextSize(17);
        camera.setTextColor(Color.WHITE);
        camera.setBackgroundColor(0xFF2F6FED);
        camera.setPadding(0, 36, 0, 36);
        camera.setOnClickListener(v -> takePhoto());
        root.addView(camera);

        LinearLayout gap = new LinearLayout(this);
        gap.setPadding(0, 20, 0, 0);
        root.addView(gap);

        Button pick = new Button(this);
        pick.setText("🖼 从相册选择");
        pick.setTextSize(17);
        pick.setTextColor(Color.WHITE);
        pick.setBackgroundColor(0xFF5B7FBF);
        pick.setPadding(0, 36, 0, 36);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK);
        });
        root.addView(pick);

        gpuToggle = new android.widget.CheckBox(this);
        gpuToggle.setText("GPU/NPU 加速（实验性：若识别闪退请保持关闭）");
        gpuToggle.setTextSize(13);
        gpuToggle.setTextColor(0xFF6B7280);
        gpuToggle.setChecked(getSharedPreferences("exam2doc", MODE_PRIVATE)
                .getBoolean("nnapi", false));
        gpuToggle.setPadding(0, 24, 0, 0);
        gpuToggle.setOnCheckedChangeListener((b, c) ->
                getSharedPreferences("exam2doc", MODE_PRIVATE)
                        .edit().putBoolean("nnapi", c).apply());
        root.addView(gpuToggle);

        setContentView(root);
    }

    private java.io.File cameraFile;

    private void takePhoto() {
        try {
            java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
            if (dir != null && !dir.exists()) dir.mkdirs();
            cameraFile = new java.io.File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
            if (cameraFile.exists()) cameraFile.delete();
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", cameraFile);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "无法调起相机：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Bitmap bmp = null;

        if (requestCode == REQ_CAMERA) {
            // 部分相机应用返回 RESULT_CANCELED 但实际已写文件，做兜底
            if (cameraFile != null && cameraFile.exists() && cameraFile.length() > 0) {
                bmp = decodeFileScaled(cameraFile, 4096);
            }
            if (bmp == null && resultCode == RESULT_OK) {
                new AlertDialog.Builder(this).setTitle("相机未返回照片")
                        .setMessage("请重试拍照，或使用「从相册选择」。\n"
                                + "若反复出现，请反馈此问题。")
                        .setPositiveButton("知道了", null).show();
                cameraFile = null;
                return;
            }
            cameraFile = null;
        } else if (requestCode == REQ_PICK) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            bmp = decodeScaled(data.getData(), 4096);
        } else {
            return;
        }

        if (bmp == null) {
            new AlertDialog.Builder(this).setTitle("图片解码失败")
                    .setMessage("格式不支持或文件损坏，请换一张图片试试。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        process(bmp);
    }

    /** 两段式解码（文件路径版）：先读尺寸，按比例采样，避免高像素照片 OOM。 */
    private Bitmap decodeFileScaled(java.io.File f, int maxSide) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 两段式解码：先读尺寸，按比例采样，避免高像素照片 OOM。 */
    private Bitmap decodeScaled(Uri uri, int maxSide) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return android.graphics.BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private void process(Bitmap bmp) {
        ProgressDialog dlg = new ProgressDialog(this);
        dlg.setMessage("识别中…（首次需加载模型，约 5~40 秒）");
        dlg.setCancelable(false);
        dlg.show();
        final boolean useNnapi = getSharedPreferences("exam2doc", MODE_PRIVATE)
                .getBoolean("nnapi", false);
        new AsyncTask<Bitmap, Void, Object[]>() {
            @Override protected Object[] doInBackground(Bitmap... b) {
                try {
                    // 先初始化引擎（含 OpenCV 库加载），再使用任何 OpenCV 对象
                    OcrPipeline pipe = getPipeline(useNnapi);
                    Bitmap scaled = OcrPipeline.bitmapToBitmap8888(b[0], MAX_SIDE);
                    if (scaled != b[0]) b[0].recycle();
                    // bitmapToMat 产出 RGBA 四通道，识别模型需要 BGR 三通道，必须转换
                    Mat rgba = new Mat();
                    Utils.bitmapToMat(scaled, rgba);
                    Mat mat = new Mat();
                    org.opencv.imgproc.Imgproc.cvtColor(rgba, mat,
                            org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR);
                    rgba.release();
                    List<OcrPipeline.Item> out = pipe.run(mat);
                    mat.release();
                    return new Object[]{out, null};
                } catch (Throwable e) {
                    return new Object[]{null, e};
                }
            }
            @Override protected void onPostExecute(Object[] r) {
                dlg.dismiss();
                Throwable e = (Throwable) r[1];
                if (e != null) {
                    // 完整错误可视化，便于远程诊断
                    StringBuilder sb = new StringBuilder();
                    sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append('\n');
                    StackTraceElement[] st = e.getStackTrace();
                    for (int i = 0; i < Math.min(6, st.length); i++)
                        sb.append("  at ").append(st[i]).append('\n');
                    new AlertDialog.Builder(LocalOcrActivity.this)
                            .setTitle("识别失败")
                            .setMessage(sb.toString())
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                @SuppressWarnings("unchecked")
                List<OcrPipeline.Item> out = (List<OcrPipeline.Item>) r[0];
                showResults(out);
            }
        }.execute(bmp);
    }

    private void showResults(List<OcrPipeline.Item> out) {
        this.items = out;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF5F7FA);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("识别结果（" + out.size() + " 块，可直接修改）");
        title.setTextSize(16);
        title.setTextColor(0xFF1F2937);
        title.setPadding(0, 0, 0, 20);
        root.addView(title);

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        resultsBox.setPadding(0, 0, 0, 24);
        for (OcrPipeline.Item it : out) {
            if (it.isImage) {
                TextView tag = new TextView(this);
                tag.setText("▍[表格图片：导出 Word 时按原位嵌入]");
                tag.setTextSize(13);
                tag.setTextColor(0xFF6B7280);
                tag.setPadding(0, 12, 0, 12);
                resultsBox.addView(tag);
            } else {
                EditText et = new EditText(this);
                et.setText(it.text);
                et.setTextSize(15);
                et.setBackgroundColor(0xFFFFFFFF);
                et.setPadding(20, 16, 20, 16);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, 12);
                et.setTag(it);
                et.addTextChangedListener(new SimpleTextWatcher(() -> it.text = et.getText().toString()));
                resultsBox.addView(et, lp);
            }
        }
        root.addView(resultsBox);

        saveBtn = new Button(this);
        saveBtn.setText("📤 导出 Word 并分享");
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(0xFF2F6FED);
        saveBtn.setPadding(0, 32, 0, 32);
        saveBtn.setOnClickListener(v -> saveWord());
        root.addView(saveBtn);

        againBtn = new Button(this);
        againBtn.setText("🔁 再来一张");
        againBtn.setTextColor(0xFF2F6FED);
        againBtn.setBackgroundColor(Color.WHITE);
        againBtn.setPadding(0, 32, 0, 32);
        againBtn.setOnClickListener(v -> showEntry());
        root.addView(againBtn);

        setContentView(scroll);
    }

    private void saveWord() {
        try {
            byte[] docx = DocxWriter.write(items);
            String filename = "试卷_" + System.currentTimeMillis() + ".docx";
            ContentValues cv = new ContentValues();
            cv.put(DISPLAY_NAME, filename);
            cv.put(MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            cv.put(IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(docx);
            os.close();
            cv.clear();
            cv.put(IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            shareDocx(uri, filename);
        } catch (Throwable e) {
            new AlertDialog.Builder(this).setTitle("导出失败")
                    .setMessage(e.getClass().getSimpleName() + ": " + e.getMessage())
                    .setPositiveButton("知道了", null).show();
        }
    }

    /** 保存后直接拉起系统分享面板（微信/WPS 等都在面板里），文件同时保留在下载目录。 */
    private void shareDocx(Uri uri, String filename) {
        String mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mime);
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.putExtra(Intent.EXTRA_TITLE, filename);
        share.setClipData(android.content.ClipData.newRawUri(filename, uri));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(share, "分享试卷 Word"));
        } catch (Throwable e) {
            Toast.makeText(this, "已保存到下载目录（无可用的分享应用）", Toast.LENGTH_LONG).show();
        }
    }
}
