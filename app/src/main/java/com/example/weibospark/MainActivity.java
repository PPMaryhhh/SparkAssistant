package com.example.weibospark;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFS = "spark_preferences";
    static final String KEY_MESSAGE = "spark_message";
    static final String KEY_COMMENT = "spark_comment";
    static final String KEY_DELAY = "step_delay_seconds";
    static final String KEY_LIMIT = "mutual_session_limit";
    static final String KEY_PROCESSED = "processed_users";
    static final String KEY_CALIBRATED = "workflow_calibrated";
    static final String KEY_SPARK_MARKER_ID = "learned_spark_marker_id";
    static final String KEY_MESSAGE_INPUT_ID = "learned_message_input_id";
    static final String KEY_MESSAGE_SEND_ID = "learned_message_send_id";
    static final String KEY_PROFILE_ENTRY_ID = "learned_profile_entry_id";
    static final String KEY_COMMENT_ENTRY_ID = "learned_comment_entry_id";
    static final String KEY_COMMENT_INPUT_ID = "learned_comment_input_id";
    static final String KEY_COMMENT_SEND_ID = "learned_comment_send_id";
    static final String KEY_LAST_ERROR = "last_service_error";
    static final String KEY_DISCOVERED_TARGETS = "discovered_mutual_targets";
    static final String KEY_SELECTED_TARGETS = "selected_mutual_targets";
    static final String KEY_SCAN_MODE = "scan_mutual_list_mode";

    private SharedPreferences preferences;
    private EditText messageInput;
    private EditText commentInput;
    private EditText delayInput;
    private EditText limitInput;
    private TextView serviceStatus;
    private TextView learningStatus;
    private TextView errorStatus;
    private TextView targetCountStatus;
    private LinearLayout targetContainer;

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
        renderTargetList();
    }

    private View buildContent() {
        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(248, 248, 248));

        root.addView(text("微博互关自动助手", 27, Color.rgb(28, 28, 30)));
        TextView subtitle = text(
                "从“互相关注”列表逐人进入主页，自动私信、评论、返回列表并继续下滑。",
                15, Color.DKGRAY);
        subtitle.setPadding(0, dp(6), 0, dp(12));
        root.addView(subtitle);

        LinearLayout statusCard = card();
        serviceStatus = text("", 15, Color.DKGRAY);
        learningStatus = text("", 15, Color.DKGRAY);
        statusCard.addView(serviceStatus);
        statusCard.addView(learningStatus);
        errorStatus = text("", 13, Color.rgb(180, 40, 40));
        errorStatus.setTextIsSelectable(true);
        statusCard.addView(errorStatus);
        root.addView(statusCard);

        messageInput = setting(root,
                "① 自动私信内容",
                "进入每位互关好友的私信页后自动发送。",
                preferences.getString(KEY_MESSAGE, "续个火花✨"), false);
        commentInput = setting(root,
                "② 主页评论内容",
                "私信完成后，在对方主页第一条可评论微博下发送。",
                preferences.getString(KEY_COMMENT, "踩踩宝贝"), false);
        delayInput = setting(root,
                "③ 每步等待时间（秒）",
                "想提速可设为 1～3 秒；页面加载慢或误点时再调高。",
                String.valueOf(preferences.getInt(KEY_DELAY, 3)), true);
        limitInput = setting(root,
                "④ 本轮最多处理人数（0 表示全部）",
                "填 0 会一直处理到列表底部；当天已完成好友会自动跳过。",
                String.valueOf(preferences.getInt(KEY_LIMIT, 0)), true);

        root.addView(primaryButton("保存以上设置", v -> saveSettings()));
        root.addView(button("开启/检查无障碍服务", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("打开微博（然后进入“互相关注”列表）", v -> openWeibo()));

        LinearLayout targetCard = card();
        targetCard.addView(text("⑤ 选择需要打卡的互关好友", 17, Color.rgb(35, 35, 35)));
        TextView targetHelp = text(
                "先扫描互关列表，再勾选需要私信和评论的人。正式运行只处理已勾选对象。",
                13, Color.GRAY);
        targetHelp.setPadding(0, dp(3), 0, dp(6));
        targetCard.addView(targetHelp);
        targetCountStatus = text("", 14, Color.rgb(0, 110, 150));
        targetCard.addView(targetCountStatus);
        targetCard.addView(button("扫描/刷新互关名单", v -> startTargetScan()));
        LinearLayout targetButtons = new LinearLayout(this);
        targetButtons.setOrientation(LinearLayout.HORIZONTAL);
        targetButtons.addView(smallActionButton("全选", v -> setAllTargets(true)));
        targetButtons.addView(smallActionButton("全不选", v -> setAllTargets(false)));
        targetButtons.addView(smallActionButton("保存选择", v -> saveTargetSelection()));
        targetCard.addView(targetButtons);
        targetContainer = new LinearLayout(this);
        targetContainer.setOrientation(LinearLayout.VERTICAL);
        targetCard.addView(targetContainer);
        root.addView(targetCard);

        root.addView(button("重置微博页面控件识别", v -> resetLearning()));
        root.addView(button("清空今天的已处理记录", v -> {
            preferences.edit().remove(KEY_PROCESSED).apply();
            Toast.makeText(this, "今天的记录已清空", Toast.LENGTH_SHORT).show();
        }));

        TextView guide = text(
                "使用方法\n\n" +
                "1. 保存设置并开启无障碍服务。\n" +
                "2. 在微博进入“互相关注”好友列表。\n" +
                "3. 第一次先点“扫描/刷新互关名单”，再在浮窗点“开始扫描”；扫描完回到本页勾选并保存。\n" +
                "4. 再回互关列表点浮窗“开始”，只对已勾选好友自动私信和评论。\n" +
                "5. 当前屏好友处理完后自动下滑，直到列表底部或达到人数上限。\n\n" +
                "浮窗可随时暂停、跳过当前好友或停止。已完成好友只在当天去重；第二天会自动重新开始全部好友。",
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

    private Button smallActionButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        return button;
    }

    private void saveSettings() {
        String message = messageInput.getText().toString().trim();
        String comment = commentInput.getText().toString().trim();
        if (message.isEmpty() || comment.isEmpty()) {
            Toast.makeText(this, "消息和主页留言都不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        int delay = clamp(parseInt(delayInput.getText().toString(), 3), 1, 15);
        int limit = clamp(parseInt(limitInput.getText().toString(), 0), 0, 500);
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
                .remove(KEY_LAST_ERROR)
                .apply();
        updateStatus();
        Toast.makeText(this, "控件识别记录已重置", Toast.LENGTH_SHORT).show();
    }

    private void openWeibo() {
        saveSettings();
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.sina.weibo");
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, "未找到官方微博 App，不会跳转网页版", Toast.LENGTH_LONG).show();
    }

    private void startTargetScan() {
        saveSettings();
        preferences.edit()
                .putBoolean(KEY_SCAN_MODE, true)
                .remove(KEY_DISCOVERED_TARGETS)
                .apply();
        Toast.makeText(this, "扫描模式已开启：请回到刚才打开的微博互关界面，再点浮窗“开始扫描”",
                Toast.LENGTH_LONG).show();
        // 不重新启动微博，也不打开网页；将本 App 放到后台，露出用户原先保持的微博页面。
        moveTaskToBack(true);
    }

    private void renderTargetList() {
        if (targetContainer == null) return;
        targetContainer.removeAllViews();
        Set<String> discovered = preferences.getStringSet(KEY_DISCOVERED_TARGETS, new HashSet<>());
        Set<String> selected = preferences.getStringSet(KEY_SELECTED_TARGETS, new HashSet<>());
        List<String> entries = new ArrayList<>(discovered);
        Collections.sort(entries);
        targetCountStatus.setText("已扫描 " + entries.size() + " 人，已选择 " + selected.size() + " 人");
        if (entries.isEmpty()) {
            TextView empty = text("尚未扫描到名单", 14, Color.GRAY);
            empty.setPadding(0, dp(8), 0, dp(4));
            targetContainer.addView(empty);
            return;
        }
        for (String entry : entries) {
            String[] parts = entry.split("\\t", 2);
            String name = parts[0];
            String id = parts.length > 1 ? parts[1] : name;
            CheckBox check = new CheckBox(this);
            check.setText(name + "    ID：" + id);
            check.setTextSize(15);
            check.setTag(entry);
            check.setChecked(selected.contains(entry));
            targetContainer.addView(check);
        }
    }

    private void setAllTargets(boolean checked) {
        for (int i = 0; i < targetContainer.getChildCount(); i++) {
            View child = targetContainer.getChildAt(i);
            if (child instanceof CheckBox) ((CheckBox) child).setChecked(checked);
        }
    }

    private void saveTargetSelection() {
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < targetContainer.getChildCount(); i++) {
            View child = targetContainer.getChildAt(i);
            if (child instanceof CheckBox && ((CheckBox) child).isChecked()) {
                selected.add(String.valueOf(child.getTag()));
            }
        }
        preferences.edit().putStringSet(KEY_SELECTED_TARGETS, selected).apply();
        targetCountStatus.setText("已扫描 " + countDiscovered() + " 人，已选择 " + selected.size() + " 人");
        Toast.makeText(this, "已保存 " + selected.size() + " 位打卡好友", Toast.LENGTH_SHORT).show();
    }

    private int countDiscovered() {
        return preferences.getStringSet(KEY_DISCOVERED_TARGETS, new HashSet<>()).size();
    }

    private void updateStatus() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean on = enabled != null && enabled.toLowerCase().contains(getPackageName().toLowerCase());
        serviceStatus.setText(on ? "● 无障碍服务：已开启" : "● 无障碍服务：未开启");
        serviceStatus.setTextColor(on ? Color.rgb(0, 130, 70) : Color.rgb(190, 60, 0));
        learningStatus.setText("● 模式：互关列表全自动；仅当天去重");
        learningStatus.setTextColor(Color.rgb(0, 110, 150));
        String lastError = preferences.getString(KEY_LAST_ERROR, "").trim();
        if (lastError.isEmpty()) {
            errorStatus.setVisibility(View.GONE);
        } else {
            errorStatus.setVisibility(View.VISIBLE);
            errorStatus.setText("\n最近一次服务错误（可长按复制）：\n" + lastError);
        }
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
