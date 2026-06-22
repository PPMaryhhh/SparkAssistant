package com.example.weibospark;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
    static final String KEY_CALIBRATED = "workflow_calibrated";
    static final String KEY_SPARK_MARKER_ID = "learned_spark_marker_id";
    static final String KEY_MESSAGE_INPUT_ID = "learned_message_input_id";
    static final String KEY_MESSAGE_SEND_ID = "learned_message_send_id";
    static final String KEY_PROFILE_ENTRY_ID = "learned_profile_entry_id";
    static final String KEY_COMMENT_ENTRY_ID = "learned_comment_entry_id";
    static final String KEY_COMMENT_INPUT_ID = "learned_comment_input_id";
    static final String KEY_COMMENT_SEND_ID = "learned_comment_send_id";

    private SharedPreferences preferences;
    private EditText messageInput;
    private EditText commentInput;
    private EditText delayInput;
    private EditText limitInput;
    private TextView serviceStatus;
    private TextView learningStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private View buildContent() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        root.addView(text("微博火花助手", 27, Color.rgb(28, 28, 30)));
        TextView subtitle = text(
                "从微博“消息”页识别带火花标识的会话，自动续消息、进主页留言，再回到消息列表继续。",
                15, Color.DKGRAY);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle);

        LinearLayout statusCard = card();
        serviceStatus = text("", 15, Color.DKGRAY);
        learningStatus = text("", 15, Color.DKGRAY);
        statusCard.addView(serviceStatus);
        statusCard.addView(learningStatus);
        root.addView(statusCard);

        messageInput = setting(root,
                "① 续火花消息",
                "自动发给消息列表中带火花标识的联系人。",
                preferences.getString(KEY_MESSAGE, "续个火花✨"), false);
        commentInput = setting(root,
                "② 主页留言",
                "发完消息后，在对方主页第一条可评论微博下发送。",
                preferences.getString(KEY_COMMENT, "踩踩宝贝"), false);
        delayInput = setting(root,
                "③ 每步等待时间（秒）",
                "给微博页面加载留时间；小米建议 4～8 秒，太短容易点错。",
                String.valueOf(preferences.getInt(KEY_DELAY, 5)), true);
        limitInput = setting(root,
                "④ 本轮最多处理人数",
                "达到数量后自动停止；下次启动会继续处理当天未完成的人。",
                String.valueOf(preferences.getInt(KEY_LIMIT, 20)), true);

        root.addView(primaryButton("保存以上设置", v -> saveSettings()));
        root.addView(button("开启/检查无障碍服务", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("打开微博（然后进入“消息”页）", v -> openWeibo()));
        root.addView(button("重新学习微博操作步骤", v -> resetLearning()));
        root.addView(button("清空今天的已处理记录", v -> {
            preferences.edit().remove(KEY_PROCESSED).apply();
            Toast.makeText(this, "今天的记录已清空", Toast.LENGTH_SHORT).show();
        }));

        TextView guide = text(
                "第一次使用\n\n" +
                "1. 保存设置并开启无障碍服务。\n" +
                "2. 打开微博底部“消息”，保证屏幕上能看到火花会话。\n" +
                "3. 点浮窗“开始学习”。第一次私信和评论各确认一次，用来校准微博当前版本的按钮。\n" +
                "4. 第一个人完成后显示“已学习”，后面的人将自动发送、自动评论、自动返回消息列表。\n\n" +
                "以后使用\n\n" +
                "进入微博消息页，点浮窗“开始续火花”即可。程序按当天去重；当天后来新增的火花联系人，再启动一次仍会被识别。\n\n" +
                "如果微博升级后操作失效，点“重新学习微博操作步骤”再校准一次。",
                15, Color.DKGRAY);
        guide.setPadding(0, dp(20), 0, dp(36));
        root.addView(guide);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private EditText setting(LinearLayout root, String title, String description,
                              String value, boolean number) {
        LinearLayout card = card();
        card.addView(text(title, 17, Color.rgb(35, 35, 35)));
        TextView help = text(description, 13, Color.GRAY);
        help.setPadding(0, dp(3), 0, dp(5));
        card.addView(help);
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setTextSize(16);
        edit.setSingleLine(true);
        edit.setPadding(dp(10), dp(6), dp(10), dp(6));
        if (number) edit.setInputType(InputType.TYPE_CLASS_NUMBER);
        card.addView(edit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(card);
        return edit;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.rgb(230, 230, 230));
        layout.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        layout.setLayoutParams(params);
        return layout;
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
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        params.bottomMargin = dp(7);
        button.setLayoutParams(params);
        return button;
    }

    private Button primaryButton(String label, View.OnClickListener listener) {
        Button button = button(label, listener);
        button.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(255, 130, 0));
        bg.setCornerRadius(dp(10));
        button.setBackground(bg);
        return button;
    }

    private void saveSettings() {
        String message = messageInput.getText().toString().trim();
        String comment = commentInput.getText().toString().trim();
        if (message.isEmpty() || comment.isEmpty()) {
            Toast.makeText(this, "消息和主页留言都不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        int delay = clamp(parseInt(delayInput.getText().toString(), 5), 3, 15);
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

    private void resetLearning() {
        preferences.edit()
                .remove(KEY_CALIBRATED)
                .remove(KEY_SPARK_MARKER_ID)
                .remove(KEY_MESSAGE_INPUT_ID)
                .remove(KEY_MESSAGE_SEND_ID)
                .remove(KEY_PROFILE_ENTRY_ID)
                .remove(KEY_COMMENT_ENTRY_ID)
                .remove(KEY_COMMENT_INPUT_ID)
                .remove(KEY_COMMENT_SEND_ID)
                .apply();
        updateStatus();
        Toast.makeText(this, "已重置；下次启动会重新学习", Toast.LENGTH_SHORT).show();
    }

    private void openWeibo() {
        saveSettings();
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.sina.weibo");
        if (launch != null) startActivity(launch);
        else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://weibo.com")));
    }

    private void updateStatus() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
        serviceStatus.setText(on ? "● 无障碍服务：已开启" : "● 无障碍服务：未开启");
        serviceStatus.setTextColor(on ? Color.rgb(0, 130, 70) : Color.rgb(190, 60, 0));
        boolean learned = preferences.getBoolean(KEY_CALIBRATED, false);
        learningStatus.setText(learned
                ? "● 操作步骤：已学习，后续自动执行"
                : "● 操作步骤：等待首次学习（首次仅确认两次）");
        learningStatus.setTextColor(learned ? Color.rgb(0, 130, 70) : Color.rgb(190, 110, 0));
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
