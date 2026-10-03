package com.drok.exam2doc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 主页：选择「本机识别」（离线独立运行）或「连接电脑」（完整管线）。深色卡片风。 */
public class HomeActivity extends Activity {

    private static final int BG = 0xFF0E1320;
    private static final int CARD = 0xFF182034;
    private static final int ACCENT = 0xFF3D7BFF;
    private static final int GREEN = 0xFF18A058;
    private static final int TEXT = 0xFFE8ECF4;
    private static final int MUTED = 0xFF8A94A8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(72, 180, 72, 72);

        TextView logo = new TextView(this);
        logo.setText("◈");
        logo.setTextSize(40);
        logo.setTextColor(ACCENT);
        root.addView(logo);

        TextView title = new TextView(this);
        title.setText("试卷电子化");
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(TEXT);
        title.setPadding(0, 24, 0, 0);
        title.getPaint().setShader(new LinearGradient(
                0, 0, 400, 0, ACCENT, 0xFF9B6CFF, Shader.TileMode.CLAMP));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("拍照试卷 · 一键生成可编辑 Word");
        sub.setTextSize(15);
        sub.setTextColor(MUTED);
        sub.setPadding(0, 16, 0, 96);
        root.addView(sub);

        root.addView(modeCard("本机识别", "离线独立运行 · 文字识别在本机完成\n表格以图片嵌入，公式按文本输出",
                ACCENT, () -> startActivity(new Intent(this, LocalOcrActivity.class))));

        LinearLayout gap = new LinearLayout(this);
        gap.setPadding(0, 32, 0, 0);
        root.addView(gap);

        root.addView(modeCard("连接电脑", "完整管线 · 表格还原 / 公式转原生公式\n手写擦除，需电脑端程序正在运行",
                GREEN, () -> startActivity(new Intent(this, ConnectActivity.class))));

        TextView tip = new TextView(this);
        tip.setText("两种模式都可校对后保存 Word 到手机；\n「连接电脑」模式下 App 会自动搜索局域网内的电脑。");
        tip.setTextSize(13);
        tip.setTextColor(MUTED);
        tip.setLineSpacing(6, 1);
        tip.setPadding(8, 72, 0, 0);
        root.addView(tip);

        setContentView(root);
    }

    private LinearLayout modeCard(String title, String desc, int color, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(48, 44, 48, 44);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(56);
        bg.setStroke(2, (color & 0x00FFFFFF) | 0x33000000);
        card.setBackground(bg);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(14);
        d.setLineSpacing(6, 1);
        d.setTextColor(MUTED);
        d.setPadding(0, 16, 0, 32);
        card.addView(d);

        Button go = new Button(this);
        go.setText("进入  →");
        go.setTextSize(15);
        go.setTextColor(Color.WHITE);
        go.setAllCaps(false);
        GradientDrawable gbtn = new GradientDrawable();
        gbtn.setColor(color);
        gbtn.setCornerRadius(40);
        go.setBackground(gbtn);
        go.setPadding(0, 8, 0, 8);
        go.setOnClickListener(v -> onClick.run());
        card.addView(go, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return card;
    }
}
