package com.drok.exam2doc;

import android.app.Activity;
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

import org.opencv.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 本机识别：照片在手机上直接完成 OCR（PP-OCRv6 ONNX），可校对后保存 Word。 */
public class LocalOcrActivity extends Activity {

    private static final int REQ_CAMERA = 2001;
    private static final int REQ_PICK = 2002;
    private static final int MAX_SIDE = 2400;

    private Uri cameraUri;
    private LinearLayout resultsBox;
    private Button saveBtn, againBtn;
    private List<OcrPipeline.Item> items;

    // 引擎懒加载单例
    private static OcrPipeline pipeline;

    private static synchronized OcrPipeline getPipeline() throws Exception {
        if (pipeline == null) {
            OpenCVLoader.initLocal();
            ai.onnxruntime.OrtEnvironment env = ai.onnxruntime.OrtEnvironment.getEnvironment();
            byte[] det = readAsset("PP-OCRv6_det_small.onnx");
            byte[] rec = readAsset("PP-OCRv6_rec_small.onnx");
            pipeline = new OcrPipeline(new DetEngine(env, det), new RecEngine(env, rec));
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

        setContentView(root);
    }

    private void takePhoto() {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.DISPLAY_NAME, "exam2doc_cap.jpg");
        cv.put(MediaStore.Images.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.IS_PENDING, 1);
        cameraUri = getContentResolver().insert(MediaStore.Images.EXTERNAL_CONTENT_URI, cv);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "无法调起相机：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        Uri uri = null;
        if (requestCode == REQ_CAMERA && cameraUri != null) uri = cameraUri;
        else if (requestCode == REQ_PICK && data != null && data.getData() != null) uri = data.getData();
        if (uri == null) return;

        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bmp = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Toast.makeText(this, "读取图片失败", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == REQ_CAMERA && cameraUri != null) {
            // 用完即删，不污染相册
            try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { }
        }
        if (bmp == null) {
            Toast.makeText(this, "图片解码失败", Toast.LENGTH_LONG).show();
            return;
        }
        process(bmp);
    }

    private void process(Bitmap bmp) {
        ProgressDialog dlg = new ProgressDialog(this);
        dlg.setMessage("识别中…（手机 CPU 推理，约 10~40 秒）");
        dlg.setCancelable(false);
        dlg.show();
        new AsyncTask<Bitmap, Void, Object[]>() {
            @Override protected Object[] doInBackground(Bitmap... b) {
                try {
                    Bitmap scaled = OcrPipeline.bitmapToBitmap8888(b[0], MAX_SIDE);
                    if (scaled != b[0]) b[0].recycle();
                    Mat mat = new Mat();
                    Utils.bitmapToMat(scaled, mat);
                    List<OcrPipeline.Item> out = getPipeline().run(mat);
                    mat.release();
                    return new Object[]{out, null};
                } catch (Exception e) {
                    return new Object[]{null, e};
                }
            }
            @Override protected void onPostExecute(Object[] r) {
                dlg.dismiss();
                Exception e = (Exception) r[1];
                if (e != null) {
                    Toast.makeText(LocalOcrActivity.this, "识别失败：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
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
        saveBtn.setText("💾 保存 Word 到手机");
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
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME,
                    "试卷_" + System.currentTimeMillis() + ".docx");
            cv.put(MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            OutputStream os = getContentResolver().openOutputStream(uri);
            os.write(docx);
            os.close();
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
            Toast.makeText(this, "已保存到手机「下载」目录", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
