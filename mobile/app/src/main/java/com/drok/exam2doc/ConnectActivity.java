package com.drok.exam2doc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Set;

/** WebView 壳：连接电脑端 exam2doc 服务（mDNS 自动发现 / 手动输入），应用内检查更新。 */
public class ConnectActivity extends Activity {

    private static final String SERVICE_TYPE = "_exam2doc._tcp.";
    private static final int REQ_FILE = 1001;

    private SharedPreferences prefs;
    private WebView web;
    private LinearLayout connectScreen;
    private ValueCallback<Uri[]> fileCallback;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Set<String> found = new LinkedHashSet<>();
    private TextView discoverStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("exam2doc", MODE_PRIVATE);
        String saved = prefs.getString("server", null);
        if (saved != null) {
            openWebView(saved);
        } else {
            showConnectScreen();
        }
    }

    // ---------- 连接页：mDNS 发现 + 手动输入 ----------

    private void showConnectScreen() {
        connectScreen = new LinearLayout(this);
        connectScreen.setOrientation(LinearLayout.VERTICAL);
        connectScreen.setBackgroundColor(Color.parseColor("#f5f7fa"));
        connectScreen.setPadding(48, 64, 48, 48);

        TextView title = new TextView(this);
        title.setText("试卷电子化");
        title.setTextSize(26);
        title.setTextColor(Color.parseColor("#1f2937"));
        connectScreen.addView(title);

        TextView hint = new TextView(this);
        hint.setText("请确保手机与电脑连同一个 WiFi，且电脑端 exam2doc 正在运行");
        hint.setTextSize(14);
        hint.setTextColor(Color.parseColor("#6b7280"));
        hint.setPadding(0, 16, 0, 32);
        connectScreen.addView(hint);

        discoverStatus = new TextView(this);
        discoverStatus.setText("正在搜索局域网内的电脑…");
        discoverStatus.setTextSize(15);
        discoverStatus.setPadding(0, 0, 0, 16);
        connectScreen.addView(discoverStatus);

        LinearLayout foundBox = new LinearLayout(this);
        foundBox.setOrientation(LinearLayout.VERTICAL);
        foundBox.setId(ViewCustom.generateViewId());
        connectScreen.addView(foundBox);

        TextView manual = new TextView(this);
        manual.setText("或手动输入电脑地址");
        manual.setTextSize(14);
        manual.setTextColor(Color.parseColor("#6b7280"));
        manual.setPadding(0, 40, 0, 8);
        connectScreen.addView(manual);

        final EditText input = new EditText(this);
        input.setHint("例如 192.168.1.100:8484");
        input.setTextSize(16);
        connectScreen.addView(input);

        Button go = new Button(this);
        go.setText("连接");
        go.setTextColor(Color.WHITE);
        go.setBackgroundColor(Color.parseColor("#2f6fed"));
        go.setOnClickListener(v -> {
            String t = input.getText().toString().trim().replaceFirst("^https?://", "");
            if (!t.isEmpty()) openWebView(t);
        });
        connectScreen.addView(go);

        setContentView(connectScreen);
        startDiscovery(foundBox);
    }

    private void startDiscovery(final LinearLayout foundBox) {
        nsd = (NsdManager) getSystemService(NSD_SERVICE);
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int errorCode) {
                discoverStatus.setText("自动搜索不可用，请手动输入地址");
            }
            @Override public void onStopDiscoveryFailed(String type, int errorCode) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) { }
                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        InetAddress host = info.getHost();
                        if (host == null) return;
                        final String addr = host.getHostAddress() + ":" + info.getPort();
                        runOnUiThread(() -> {
                            if (!found.add(addr)) return;
                            discoverStatus.setText("找到电脑：");
                            Button b = new Button(ConnectActivity.this);
                            b.setText(addr);
                            b.setTextColor(Color.WHITE);
                            b.setBackgroundColor(Color.parseColor("#18a058"));
                            b.setOnClickListener(v -> openWebView(addr));
                            foundBox.addView(b);
                        });
                    }
                });
            }
        };
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    // ---------- WebView 模式 ----------

    private void openWebView(String addr) {
        prefs.edit().putString("server", addr).apply();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#2f6fed"));
        bar.setPadding(24, 12, 24, 12);

        TextView name = new TextView(this);
        name.setText("试卷电子化  ·  " + addr);
        name.setTextColor(Color.WHITE);
        name.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(name, lp);

        Button sw = new Button(this);
        sw.setText("换电脑");
        sw.setTextSize(12);
        sw.setTextColor(Color.WHITE);
        sw.setBackgroundColor(Color.parseColor("#1e5bd6"));
        sw.setOnClickListener(v -> {
            if (web != null) web.loadUrl("about:blank");
            found.clear();
            showConnectScreen();
        });
        bar.addView(sw);

        Button upd = new Button(this);
        upd.setText("检查更新");
        upd.setTextSize(12);
        upd.setTextColor(Color.WHITE);
        upd.setBackgroundColor(Color.parseColor("#1e5bd6"));
        upd.setOnClickListener(v -> checkUpdate(addr));
        bar.addView(upd);

        root.addView(bar);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        web.loadUrl("http://" + addr + "/");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (nsd != null && discoveryListener != null) {
            try { nsd.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    // ---------- JS 桥：保存 docx ----------

    private class Bridge {
        @JavascriptInterface
        public void saveDocx(String filename, String base64) {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            try {
                ContentValues cv = new ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("insert failed");
                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(data);
                os.close();
                cv.clear();
                cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
                runOnUiThread(() -> Toast.makeText(ConnectActivity.this,
                        "已保存到手机「下载」：" + filename, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(ConnectActivity.this,
                        "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }

    // ---------- 应用内更新 ----------

    private void checkUpdate(String addr) {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Void, String[]>() {
            @Override protected String[] doInBackground(Void... v) {
                try {
                    HttpURLConnection c = (HttpURLConnection)
                            new URL("http://" + addr + "/api/app-version").openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(4000);
                    InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    // 返回 [最新版本, apk地址]
                    org.json.JSONObject o = new org.json.JSONObject(bo.toString("UTF-8"));
                    return new String[]{o.optString("version", ""),
                            o.optString("apk", "/download/apk")};
                } catch (Exception e) {
                    return null;
                }
            }
            @Override protected void onPostExecute(String[] r) {
                if (r == null) {
                    Toast.makeText(ConnectActivity.this, "无法连接电脑，请确认电脑端已启动",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (isNewer(r[0], BuildConfig.VERSION_NAME)) {
                    new AlertDialog.Builder(ConnectActivity.this)
                            .setTitle("发现新版本 " + r[0])
                            .setMessage("当前版本 " + BuildConfig.VERSION_NAME
                                    + "，是否从电脑下载并安装？")
                            .setPositiveButton("下载安装", (d, w) ->
                                    downloadApk(addr + r[1]))
                            .setNegativeButton("取消", null).show();
                } else {
                    Toast.makeText(ConnectActivity.this, "已是最新版本 " + BuildConfig.VERSION_NAME,
                            Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    private void downloadApk(String url) {
        Toast.makeText(this, "开始下载…", Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Void, Uri>() {
            @Override protected Uri doInBackground(Void... v) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(30000);
                    InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();

                    ContentValues cv = new ContentValues();
                    cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,
                            "exam2doc-update-" + BuildConfig.VERSION_NAME + ".apk");
                    cv.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                            "application/vnd.android.package-archive");
                    cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    bo.writeTo(os);
                    os.close();
                    cv.clear();
                    cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                    return uri;
                } catch (Exception e) {
                    return null;
                }
            }
            @Override protected void onPostExecute(Uri uri) {
                if (uri == null) {
                    Toast.makeText(ConnectActivity.this, "下载失败", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(ConnectActivity.this,
                            "已下载到「下载」目录，请手动安装", Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    private static boolean isNewer(String a, String b) {
        try {
            String[] pa = a.split("\\."), pb = b.split("\\.");
            int n = Math.max(pa.length, pb.length);
            for (int i = 0; i < n; i++) {
                int x = i < pa.length ? Integer.parseInt(pa[i]) : 0;
                int y = i < pb.length ? Integer.parseInt(pb[i]) : 0;
                if (x != y) return x > y;
            }
            return false;
        } catch (Exception e) {
            return !a.equals(b);
        }
    }

    /** 兼容无 androidx 的 view id 生成。 */
    private static final class ViewCustom {
        static int generateViewId() { return android.view.View.generateViewId(); }
    }
}
