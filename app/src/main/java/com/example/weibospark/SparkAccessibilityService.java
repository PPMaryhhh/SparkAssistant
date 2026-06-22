package com.example.weibospark;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SparkAccessibilityService extends AccessibilityService {
    private enum State {
        IDLE, FIND_TARGET, OPEN_PROFILE, OPEN_CHAT, WAIT_MESSAGE_CONFIRM,
        BACK_TO_PROFILE, OPEN_COMMENT, WAIT_COMMENT_CONFIRM, RETURN_TO_LIST, PAUSED
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepRunnable = this::runStep;
    private State state = State.IDLE;
    private State stateBeforePause = State.FIND_TARGET;
    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView status;
    private Button primary;
    private Button confirm;
    private String currentUser = "";
    private int sessionCount = 0;
    private int backAttempts = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        showOverlay();
        setStatus("请进入微博互相关注列表，再点开始");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (state != State.IDLE && state != State.PAUSED
                && state != State.WAIT_MESSAGE_CONFIRM && state != State.WAIT_COMMENT_CONFIRM) {
            scheduleStep(500);
        }
    }

    @Override
    public void onInterrupt() {
        pause("系统中断，已暂停");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (windowManager != null && overlay != null) windowManager.removeView(overlay);
        super.onDestroy();
    }

    private void showOverlay() {
        if (overlay != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(10), dp(8), dp(10), dp(8));
        overlay.setBackgroundColor(Color.argb(232, 35, 35, 35));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setMaxLines(3);
        overlay.addView(status, new LinearLayout.LayoutParams(dp(245), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        primary = smallButton("开始");
        primary.setOnClickListener(v -> onPrimary());
        confirm = smallButton("确认发送");
        confirm.setVisibility(View.GONE);
        confirm.setOnClickListener(v -> onConfirm());
        Button stop = smallButton("停止");
        stop.setOnClickListener(v -> stopTask("任务已停止"));
        buttons.addView(primary);
        buttons.addView(confirm);
        buttons.addView(stop);
        overlay.addView(buttons);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(8);
        params.y = dp(72);
        windowManager.addView(overlay, params);
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(42), 1));
        return button;
    }

    private void onPrimary() {
        if (state == State.IDLE) {
            sessionCount = 0;
            currentUser = "";
            state = State.FIND_TARGET;
            primary.setText("暂停");
            setStatus("正在寻找未处理的互关用户…");
            scheduleStep(200);
        } else if (state == State.PAUSED) {
            state = stateBeforePause;
            primary.setText("暂停");
            setStatus("继续：" + stateLabel(state));
            scheduleStep(200);
        } else {
            pause("已暂停，可调整微博页面后继续");
        }
    }

    private void pause(String reason) {
        if (state != State.IDLE && state != State.PAUSED) stateBeforePause = state;
        state = State.PAUSED;
        handler.removeCallbacks(stepRunnable);
        if (primary != null) primary.setText("继续/重试");
        if (confirm != null) confirm.setVisibility(View.GONE);
        setStatus(reason);
    }

    private void stopTask(String reason) {
        state = State.IDLE;
        handler.removeCallbacks(stepRunnable);
        currentUser = "";
        if (primary != null) primary.setText("开始");
        if (confirm != null) confirm.setVisibility(View.GONE);
        setStatus(reason);
    }

    private void onConfirm() {
        confirm.setVisibility(View.GONE);
        if (state == State.WAIT_MESSAGE_CONFIRM) {
            if (!clickByAnyText("发送", "Send")) {
                pause("没找到私信发送按钮；请手动发送后点继续/重试");
                stateBeforePause = State.BACK_TO_PROFILE;
                return;
            }
            state = State.BACK_TO_PROFILE;
            setStatus("私信已发送，准备返回主页");
            scheduleStep(delayMs());
        } else if (state == State.WAIT_COMMENT_CONFIRM) {
            if (!clickByAnyText("发送", "评论", "发布")) {
                pause("没找到评论发送按钮；请手动发送后点继续/重试");
                stateBeforePause = State.RETURN_TO_LIST;
                return;
            }
            rememberProcessed(currentUser);
            sessionCount++;
            state = State.RETURN_TO_LIST;
            backAttempts = 0;
            setStatus("已完成 " + currentUser + "（本次 " + sessionCount + " 人）");
            scheduleStep(delayMs());
        }
    }

    private void runStep() {
        if (state == State.IDLE || state == State.PAUSED
                || state == State.WAIT_MESSAGE_CONFIRM || state == State.WAIT_COMMENT_CONFIRM) return;
        if (!isWeiboForeground()) {
            pause("请先切回微博，再点继续/重试");
            return;
        }
        if (sessionCount >= sessionLimit()) {
            stopTask("已达到本次上限 " + sessionCount + " 人");
            return;
        }

        switch (state) {
            case FIND_TARGET:
                findAndOpenTarget();
                break;
            case OPEN_PROFILE:
                openChatFromProfile();
                break;
            case OPEN_CHAT:
                fillMessage();
                break;
            case BACK_TO_PROFILE:
                performGlobalAction(GLOBAL_ACTION_BACK);
                state = State.OPEN_COMMENT;
                setStatus("已返回主页，寻找第一条可评论微博…");
                scheduleStep(delayMs());
                break;
            case OPEN_COMMENT:
                openCommentComposer();
                break;
            case RETURN_TO_LIST:
                returnToList();
                break;
            default:
                break;
        }
    }

    private void findAndOpenTarget() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            pause("暂时读不到微博页面，请点重试");
            return;
        }
        Set<String> processed = getProcessed();
        List<AccessibilityNodeInfo> markers = findNodes(root, "私信", "互相关注", "已关注");
        for (AccessibilityNodeInfo marker : markers) {
            AccessibilityNodeInfo row = findLikelyRow(marker);
            String label = extractUserLabel(row);
            if (label.isEmpty() || processed.contains(label)) continue;
            AccessibilityNodeInfo target = findProfileClickTarget(row, marker);
            if (target != null && clickNode(target)) {
                currentUser = label;
                state = State.OPEN_PROFILE;
                setStatus("已选择：" + label + "，正在打开主页");
                scheduleStep(delayMs());
                return;
            }
        }

        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            setStatus("当前屏没有新用户，继续向下查找…");
            scheduleStep(delayMs());
        } else {
            stopTask("列表已到底，或未识别到互关用户；共完成 " + sessionCount + " 人");
        }
    }

    private void openChatFromProfile() {
        // 部分微博版本点击互关列表头像后会直接落在聊天页。
        if (findEditable(getRootInActiveWindow()) != null) {
            state = State.OPEN_CHAT;
            fillMessage();
            return;
        }
        if (clickByAnyText("私信", "聊天")) {
            state = State.OPEN_CHAT;
            setStatus("正在打开与 " + currentUser + " 的私信");
            scheduleStep(delayMs());
        } else {
            pause("主页没找到“私信”按钮，请确认页面后重试");
        }
    }

    private void fillMessage() {
        AccessibilityNodeInfo edit = findEditable(getRootInActiveWindow());
        if (edit == null || !setText(edit, prefString(MainActivity.KEY_MESSAGE, "续个火花✨"))) {
            pause("没有识别到私信输入框，请点一下输入框后重试");
            return;
        }
        state = State.WAIT_MESSAGE_CONFIRM;
        confirm.setText("确认发私信");
        confirm.setVisibility(View.VISIBLE);
        setStatus("请核对：给 “" + currentUser + "” 发送续火花私信？");
    }

    private void openCommentComposer() {
        if (!clickFirstCommentAction()) {
            pause("主页没有识别到评论入口；请滚动到任意微博后重试");
            return;
        }
        handler.postDelayed(() -> {
            AccessibilityNodeInfo edit = findEditable(getRootInActiveWindow());
            if (edit == null || !setText(edit, prefString(MainActivity.KEY_COMMENT, "踩踩宝贝"))) {
                pause("评论入口已打开，但没找到输入框，请点输入框后重试");
                return;
            }
            state = State.WAIT_COMMENT_CONFIRM;
            confirm.setText("确认发评论");
            confirm.setVisibility(View.VISIBLE);
            setStatus("请核对：在 “" + currentUser + "” 主页发送评论？");
        }, delayMs());
    }

    private void returnToList() {
        if (looksLikeMutualList()) {
            state = State.FIND_TARGET;
            backAttempts = 0;
            setStatus("已回互关列表，寻找下一位…");
            scheduleStep(delayMs());
            return;
        }
        if (backAttempts >= 3) {
            pause("没能自动回到互关列表，请手动返回列表后继续");
            stateBeforePause = State.FIND_TARGET;
            return;
        }
        backAttempts++;
        performGlobalAction(GLOBAL_ACTION_BACK);
        scheduleStep(delayMs());
    }

    private boolean looksLikeMutualList() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return hasAnyText(root, "互相关注", "互关", "关注的人");
    }

    private AccessibilityNodeInfo findLikelyRow(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        Rect bounds = new Rect();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        for (int i = 0; i < 5 && current != null; i++) {
            current.getBoundsInScreen(bounds);
            if (bounds.width() > screenWidth * 0.65 && bounds.height() >= dp(48) && bounds.height() <= dp(180)) {
                return current;
            }
            current = current.getParent();
        }
        return node.getParent() != null ? node.getParent() : node;
    }

    private String extractUserLabel(AccessibilityNodeInfo row) {
        List<String> values = new ArrayList<>();
        collectText(row, values, 0);
        for (String value : values) {
            String v = value.trim();
            if (v.length() < 1 || v.length() > 40 || isCommonLabel(v)) continue;
            return v;
        }
        Rect rect = new Rect();
        row.getBoundsInScreen(rect);
        return "用户@" + rect.centerY();
    }

    private void collectText(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 5) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && !text.toString().trim().isEmpty()) out.add(text.toString());
        if (desc != null && !desc.toString().trim().isEmpty()) out.add(desc.toString());
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out, depth + 1);
    }

    private boolean isCommonLabel(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("私信") || v.equals("互相关注") || v.equals("已关注")
                || v.equals("关注") || v.equals("粉丝") || v.equals("微博")
                || v.contains("会员") || v.matches("[0-9.万w]+粉丝");
    }

    private AccessibilityNodeInfo findProfileClickTarget(AccessibilityNodeInfo row, AccessibilityNodeInfo marker) {
        List<AccessibilityNodeInfo> clickable = new ArrayList<>();
        collectClickable(row, clickable, 0);
        for (AccessibilityNodeInfo node : clickable) {
            String text = nodeText(node);
            if (!text.contains("私信") && !text.contains("关注")) return node;
        }
        AccessibilityNodeInfo current = row;
        for (int i = 0; i < 4 && current != null; i++) {
            if (current.isClickable() && current != marker) return current;
            current = current.getParent();
        }
        return null;
    }

    private void collectClickable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 5) return;
        if (node.isClickable()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectClickable(node.getChild(i), out, depth + 1);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() || "android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findScrollable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private boolean clickByAnyText(String... labels) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isVisibleToUser() && clickNode(node)) return true;
            }
        }
        return false;
    }

    private boolean clickFirstCommentAction() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        int minY = getResources().getDisplayMetrics().heightPixels / 3;
        for (String label : new String[]{"评论", "Comment"}) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            for (AccessibilityNodeInfo node : nodes) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                // 跳过主页顶部的“评论”标签，只选择微博卡片区域内的评论入口。
                if (node.isVisibleToUser() && bounds.centerY() > minY && clickNode(node)) return true;
            }
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return false;
    }

    private List<AccessibilityNodeInfo> findNodes(AccessibilityNodeInfo root, String... labels) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        for (String label : labels) all.addAll(root.findAccessibilityNodeInfosByText(label));
        return all;
    }

    private boolean hasAnyText(AccessibilityNodeInfo root, String... labels) {
        if (root == null) return false;
        for (String label : labels) {
            if (!root.findAccessibilityNodeInfosByText(label).isEmpty()) return true;
        }
        return false;
    }

    private String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder b = new StringBuilder();
        if (node.getText() != null) b.append(node.getText());
        if (node.getContentDescription() != null) b.append(' ').append(node.getContentDescription());
        return b.toString();
    }

    private boolean isWeiboForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null
                && "com.sina.weibo".contentEquals(root.getPackageName());
    }

    private void rememberProcessed(String user) {
        if (user == null || user.isEmpty()) return;
        Set<String> users = getProcessed();
        users.add(user);
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putStringSet(MainActivity.KEY_PROCESSED, users).apply();
    }

    private Set<String> getProcessed() {
        Set<String> saved = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getStringSet(MainActivity.KEY_PROCESSED, new HashSet<>());
        return new HashSet<>(saved);
    }

    private String prefString(String key, String fallback) {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString(key, fallback);
    }

    private int sessionLimit() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(MainActivity.KEY_LIMIT, 20);
    }

    private long delayMs() {
        int seconds = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getInt(MainActivity.KEY_DELAY, 4);
        return Math.max(2, Math.min(15, seconds)) * 1000L;
    }

    private void scheduleStep(long delay) {
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, delay);
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private String stateLabel(State value) {
        switch (value) {
            case FIND_TARGET: return "寻找下一位互关用户";
            case OPEN_PROFILE: return "打开用户主页";
            case OPEN_CHAT: return "填写私信";
            case BACK_TO_PROFILE: return "返回用户主页";
            case OPEN_COMMENT: return "打开评论";
            case RETURN_TO_LIST: return "返回互关列表";
            default: return "继续处理";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
