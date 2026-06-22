package com.example.weibospark;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String PREFS = "spark_preferences";
    static final String KEY_MESSAGE = "spark_message";
    static final String KEY_COMMENT = "spark_comment";
    static final String KEY_DELAY = "step_delay_seconds";
    static final String KEY_LIMIT = "session_limit";
    static final String KEY_PROCESSED = "processed_users";

    private SharedPreferences preferences;
    private EditText messageInput;
    private EditText commentInput;
    private EditText delayInput;
    private EditText limitInput;
    private TextView serviceStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
    }

    private View buildContent() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("微博火花助手", 26, Color.rgb(30, 30, 30));
        root.addView(title);
        root.addView(text(
                "半自动续火花：逐个识别互关用户，填写私信和评论；每一次真正发送都需要你在浮窗确认。",
                15, Color.DKGRAY));

        serviceStatus = text("", 15, Color.rgb(180, 70, 0));
        serviceStatus.setPadding(0, dp(14), 0, dp(8));
        root.addView(serviceStatus);

        messageInput = field("私信内容", preferences.getString(KEY_MESSAGE, "续个火花✨"), false);
        commentInput = field("主页评论内容", preferences.getString(KEY_COMMENT, "踩踩宝贝"), false);
        delayInput = field("步骤等待秒数（建议 3～8）", String.valueOf(preferences.getInt(KEY_DELAY, 4)), true);
        limitInput = field("单次最多处理人数（1～50）", String.valueOf(preferences.getInt(KEY_LIMIT, 20)), true);
        root.addView(messageInput);
        root.addView(commentInput);
        root.addView(delayInput);
        root.addView(limitInput);

        Button save = button("保存设置", v -> saveSettings());
        Button accessibility = button("1. 开启无障碍服务", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button openWeibo = button("2. 打开微博", v -> openWeibo());
        Button clear = button("清空已处理记录", v -> {
            preferences.edit().remove(KEY_PROCESSED).apply();
            Toast.makeText(this, "已清空，可重新处理", Toast.LENGTH_SHORT).show();
        });
        root.addView(save);
        root.addView(accessibility);
        root.addView(openWeibo);
        root.addView(clear);

        TextView guide = text(
                "使用方法\n\n" +
                "1. 保存设置并开启“微博火花助手”无障碍服务。\n" +
                "2. 在微博进入“关注 → 互相关注”列表。\n" +
                "3. 点浮窗“开始”，助手会选择一个未处理用户。\n" +
                "4. 浮窗显示确认时，检查对象和文案后再发送。\n" +
                "5. 完成评论后自动返回列表继续；任何时候都可暂停或停止。\n\n" +
                "小米提示：如果服务经常被关闭，请在系统设置中允许自启动，并把本应用省电策略改为“不限制”。微博升级后若按钮识别失败，停在对应页面再点浮窗“重试”。",
                15, Color.DKGRAY);
        guide.setPadding(0, dp(18), 0, dp(32));
        root.addView(guide);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private EditText field(String hint, String value, boolean number) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setText(value);
        edit.setTextSize(16);
        edit.setSingleLine(true);
        edit.setPadding(dp(12), dp(8), dp(12), dp(8));
        if (number) edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        edit.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        params.topMargin = dp(8);
        b.setLayoutParams(params);
        return b;
    }

    private void saveSettings() {
        String message = messageInput.getText().toString().trim();
        String comment = commentInput.getText().toString().trim();
        if (message.isEmpty() || comment.isEmpty()) {
            Toast.makeText(this, "两段文案都不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        int delay = clamp(parseInt(delayInput.getText().toString(), 4), 2, 15);
        int limit = clamp(parseInt(limitInput.getText().toString(), 20), 1, 50);
        preferences.edit()
                .putString(KEY_MESSAGE, message)
                .putString(KEY_COMMENT, comment)
                .putInt(KEY_DELAY, delay)
                .putInt(KEY_LIMIT, limit)
                .apply();
        delayInput.setText(String.valueOf(delay));
        limitInput.setText(String.valueOf(limit));
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
    }

    private void openWeibo() {
        saveSettings();
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.sina.weibo");
        if (launch != null) {
            startActivity(launch);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://weibo.com")));
        }
    }

    private void updateServiceStatus() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
        serviceStatus.setText(on ? "无障碍服务：已开启" : "无障碍服务：未开启");
        serviceStatus.setTextColor(on ? Color.rgb(0, 125, 60) : Color.rgb(190, 60, 0));
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
