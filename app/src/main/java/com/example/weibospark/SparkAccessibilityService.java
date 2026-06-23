package com.example.weibospark;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SparkAccessibilityService extends AccessibilityService {
    private static final String TAG = "WeiboMutualService";

    private enum State {
        IDLE, FIND_TARGET, WAIT_PROFILE, WAIT_CHAT, SEND_MESSAGE,
        CLOSE_KEYBOARD, BACK_TO_PROFILE, OPEN_COMMENT, SEND_COMMENT,
        RETURN_TO_LIST, PAUSED
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepRunnable = this::runStep;
    private final Set<String> skippedThisSession = new HashSet<>();
    private State state = State.IDLE;
    private State stateBeforePause = State.FIND_TARGET;
    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView status;
    private Button primary;
    private String currentUser = "";
    private int sessionCount;
    private int backAttempts;
    private int scannedPages;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
            showOverlay();
            setStatus("请进入微博“互相关注”列表后点开始");
        } catch (Throwable error) {
            handleFatalError("启动无障碍服务", error);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (state != State.IDLE && state != State.PAUSED) scheduleStep(450);
        } catch (Throwable error) {
            handleFatalError("接收微博页面事件", error);
        }
    }

    @Override
    public void onInterrupt() {
        pause("系统中断，已暂停");
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            if (windowManager != null && overlay != null) windowManager.removeView(overlay);
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private void showOverlay() {
        if (overlay != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(10), dp(8), dp(10), dp(8));
        overlay.setBackgroundColor(Color.argb(235, 35, 35, 35));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setMaxLines(4);
        overlay.addView(status, new LinearLayout.LayoutParams(
                dp(270), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        primary = smallButton("开始");
        primary.setOnClickListener(v -> onPrimary());
        Button skip = smallButton("跳过此人");
        skip.setOnClickListener(v -> skipCurrentUser());
        Button stop = smallButton("停止");
        stop.setOnClickListener(v -> stopTask("任务已停止"));
        buttons.addView(primary);
        buttons.addView(skip);
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
        try {
            windowManager.addView(overlay, params);
        } catch (Throwable error) {
            overlay = null;
            handleFatalError("显示控制浮窗", error);
        }
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(42), 1));
        return button;
    }

    private void onPrimary() {
        if (state == State.IDLE) {
            sessionCount = 0;
            backAttempts = 0;
            scannedPages = 0;
            currentUser = "";
            skippedThisSession.clear();
            state = State.FIND_TARGET;
            primary.setText("暂停");
            setStatus("全自动模式：正在寻找当天未处理的互关好友…");
            scheduleStep(250);
        } else if (state == State.PAUSED) {
            state = stateBeforePause;
            primary.setText("暂停");
            setStatus("继续：" + stateLabel(state));
            scheduleStep(250);
        } else {
            pause("已暂停；调整页面后点继续");
        }
    }

    private void skipCurrentUser() {
        if (currentUser.isEmpty()) {
            setStatus("当前还没有打开好友，无法跳过");
            return;
        }
        skippedThisSession.add(currentUser);
        state = State.RETURN_TO_LIST;
        backAttempts = 0;
        setStatus("本轮跳过：“" + currentUser + "”，正在返回互关列表");
        if (!looksLikeMutualList(getRootInActiveWindow())) performGlobalAction(GLOBAL_ACTION_BACK);
        scheduleStep(delayMs());
    }

    private void runStep() {
        try {
            runStepSafely();
        } catch (Throwable error) {
            handleFatalError("执行步骤 " + stateLabel(state), error);
        }
    }

    private void runStepSafely() {
        if (state == State.IDLE || state == State.PAUSED) return;
        if (!isWeiboForeground()) {
            pause("请切回微博，再点继续");
            return;
        }
        int limit = sessionLimit();
        if (limit > 0 && sessionCount >= limit) {
            stopTask("已达到本轮上限 " + sessionCount + " 人");
            return;
        }
        switch (state) {
            case FIND_TARGET:
                findAndOpenTarget();
                break;
            case WAIT_PROFILE:
                openPrivateChat();
                break;
            case WAIT_CHAT:
                waitForChatInput();
                break;
            case SEND_MESSAGE:
                fillAndSendMessage();
                break;
            case CLOSE_KEYBOARD:
                closeMessageKeyboard();
                break;
            case BACK_TO_PROFILE:
                backToProfile();
                break;
            case OPEN_COMMENT:
                openCommentComposer();
                break;
            case SEND_COMMENT:
                fillAndSendComment();
                break;
            case RETURN_TO_LIST:
                returnToMutualList();
                break;
            default:
                break;
        }
    }

    private void findAndOpenTarget() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            pause("暂时读不到微博页面，请重试");
            return;
        }
        if (!looksLikeMutualList(root)) {
            pause("当前不是“互相关注”列表，请进入列表后继续");
            return;
        }

        Set<String> processed = getProcessedToday();
        List<AccessibilityNodeInfo> markers = findTargetMarkers(root);
        int identifiedRows = 0;
        for (AccessibilityNodeInfo marker : markers) {
            AccessibilityNodeInfo row = findLikelyRow(marker);
            String user = extractUserLabel(row);
            if (user.isEmpty()) continue;
            identifiedRows++;
            if (processed.contains(todayKey(user)) || skippedThisSession.contains(user)) continue;
            AccessibilityNodeInfo target = findProfileClickTarget(row, marker);
            if (target != null && clickNode(target)) {
                currentUser = user;
                state = State.WAIT_PROFILE;
                setStatus("正在处理：“" + user + "” → 打开主页");
                scheduleStep(delayMs());
                return;
            }
        }

        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null && scannedPages < 60
                && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            scannedPages++;
            setStatus("本屏好友已处理，自动下滑继续查找…");
            scheduleStep(delayMs());
        } else if (!markers.isEmpty() && identifiedRows == 0) {
            pause("看到互关列表，但无法读取好友昵称；请把页面截图发来适配");
        } else {
            stopTask("互关列表已处理完成，本次成功 " + sessionCount + " 人");
        }
    }

    private void openPrivateChat() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo button = findNodeByAnyText(root, "私信", "发私信", "聊天");
        if (button != null && clickNode(button)) {
            state = State.WAIT_CHAT;
            setStatus("“" + currentUser + "”主页已打开，正在进入私信");
            scheduleStep(delayMs());
        } else {
            pauseWithNext("主页没找到“私信”按钮，请调整页面后继续", State.WAIT_PROFILE);
        }
    }

    private void waitForChatInput() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_MESSAGE_INPUT_ID);
        if (edit != null) {
            state = State.SEND_MESSAGE;
            scheduleStep(200);
        } else {
            pauseWithNext("私信页没找到输入框，请点一下输入区域后继续", State.WAIT_CHAT);
        }
    }

    private void fillAndSendMessage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_MESSAGE_INPUT_ID);
        if (edit == null || !setText(edit, prefString(MainActivity.KEY_MESSAGE, "续个火花✨"))) {
            pauseWithNext("无法填写私信输入框，请调整页面后继续", State.SEND_MESSAGE);
            return;
        }
        rememberNodeId(MainActivity.KEY_MESSAGE_INPUT_ID, edit);
        if (!clickMessageSendButton(root)) {
            pauseWithNext("没找到私信“发送”按钮，请调整页面后继续", State.SEND_MESSAGE);
            return;
        }
        state = State.CLOSE_KEYBOARD;
        setStatus("私信已发送，正在关闭输入状态并返回主页");
        scheduleStep(delayMs());
    }

    private void closeMessageKeyboard() {
        if (isKeyboardVisible()) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            setStatus("已关闭输入状态，下一步返回好友主页");
            scheduleStep(delayMs());
            return;
        }
        state = State.BACK_TO_PROFILE;
        scheduleStep(250);
    }

    private void backToProfile() {
        if (isKeyboardVisible()) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            scheduleStep(delayMs());
            return;
        }
        performGlobalAction(GLOBAL_ACTION_BACK);
        state = State.OPEN_COMMENT;
        setStatus("已退出私信，正在主页寻找可评论微博");
        scheduleStep(delayMs());
    }

    private void openCommentComposer() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (findEditableLearned(root, MainActivity.KEY_MESSAGE_INPUT_ID) != null) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            setStatus("仍在私信页，再返回一次主页…");
            scheduleStep(delayMs());
            return;
        }
        AccessibilityNodeInfo entry = findByLearnedId(root, MainActivity.KEY_COMMENT_ENTRY_ID);
        if (entry == null) entry = findCommentAction(root);
        if (entry != null && clickNode(entry)) {
            rememberNodeId(MainActivity.KEY_COMMENT_ENTRY_ID, entry);
            state = State.SEND_COMMENT;
            setStatus("评论入口已打开，正在填写评论");
            scheduleStep(delayMs());
        } else {
            pauseWithNext("主页没有识别到可评论微博，请滚动主页后继续", State.OPEN_COMMENT);
        }
    }

    private void fillAndSendComment() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_COMMENT_INPUT_ID);
        if (edit == null || !setText(edit, prefString(MainActivity.KEY_COMMENT, "踩踩宝贝"))) {
            pauseWithNext("没有识别到评论输入框，请点输入框后继续", State.SEND_COMMENT);
            return;
        }
        rememberNodeId(MainActivity.KEY_COMMENT_INPUT_ID, edit);
        if (!clickCommentSendButton(root, edit)) {
            pauseWithNext("没找到提交评论的“评论”按钮，请调整页面后继续", State.SEND_COMMENT);
            return;
        }
        rememberProcessedToday(currentUser);
        sessionCount++;
        state = State.RETURN_TO_LIST;
        backAttempts = 0;
        setStatus("已完成：“" + currentUser + "”（本次 " + sessionCount + " 人），正在返回互关列表");
        scheduleStep(delayMs());
    }

    private void returnToMutualList() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (looksLikeMutualList(root)) {
            currentUser = "";
            state = State.FIND_TARGET;
            backAttempts = 0;
            setStatus("已回到互关列表，继续下一位…");
            scheduleStep(delayMs());
            return;
        }
        if (backAttempts >= 7) {
            pauseWithNext("无法自动返回互关列表，请手动返回后继续", State.FIND_TARGET);
            return;
        }
        backAttempts++;
        performGlobalAction(GLOBAL_ACTION_BACK);
        scheduleStep(delayMs());
    }

    private List<AccessibilityNodeInfo> findTargetMarkers(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        for (String label : new String[]{"私信", "已关注", "互相关注"}) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (node.isVisibleToUser() && bounds.centerY() > dp(130)) result.add(node);
            }
        }
        return result;
    }

    private AccessibilityNodeInfo findLikelyRow(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        Rect bounds = new Rect();
        int width = getResources().getDisplayMetrics().widthPixels;
        for (int i = 0; i < 6 && current != null; i++) {
            current.getBoundsInScreen(bounds);
            if (bounds.width() > width * 0.68 && bounds.height() >= dp(52)
                    && bounds.height() <= dp(190)) return current;
            current = current.getParent();
        }
        return node;
    }

    private String extractUserLabel(AccessibilityNodeInfo row) {
        List<String> values = new ArrayList<>();
        collectText(row, values, 0);
        for (String value : values) {
            String clean = value.trim().replace("的头像", "").replace("头像", "");
            if (clean.length() < 1 || clean.length() > 40 || isCommonListLabel(clean)) continue;
            return clean;
        }
        return "";
    }

    private AccessibilityNodeInfo findProfileClickTarget(AccessibilityNodeInfo row,
                                                          AccessibilityNodeInfo marker) {
        List<AccessibilityNodeInfo> clickable = new ArrayList<>();
        collectClickable(row, clickable, 0);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        for (AccessibilityNodeInfo node : clickable) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String text = nodeText(node);
            if (bounds.centerX() < screenWidth * 0.70
                    && !text.contains("私信") && !text.contains("关注") && node != marker) return node;
        }
        if (row.isClickable() && !nodeText(row).trim().equals("私信")) return row;
        return null;
    }

    private void collectClickable(AccessibilityNodeInfo node,
                                  List<AccessibilityNodeInfo> result, int depth) {
        if (node == null || depth > 6) return;
        if (node.isVisibleToUser() && node.isClickable()) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectClickable(node.getChild(i), result, depth + 1);
        }
    }

    private AccessibilityNodeInfo findCommentAction(AccessibilityNodeInfo root) {
        if (root == null) return null;
        int minY = getResources().getDisplayMetrics().heightPixels / 3;
        for (String label : new String[]{"评论", "Comment"}) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (node.isVisibleToUser() && bounds.centerY() > minY
                        && findClickableAncestor(node, 4) != null) return node;
            }
        }
        return null;
    }

    private boolean clickMessageSendButton(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo button = findByLearnedId(root, MainActivity.KEY_MESSAGE_SEND_ID);
        if (button == null) button = findNodeByAnyText(root, "发送", "Send");
        if (button != null && clickNode(button)) {
            rememberNodeId(MainActivity.KEY_MESSAGE_SEND_ID, button);
            return true;
        }
        return false;
    }

    private boolean clickCommentSendButton(AccessibilityNodeInfo root, AccessibilityNodeInfo edit) {
        AccessibilityNodeInfo button = findByLearnedId(root, MainActivity.KEY_COMMENT_SEND_ID);
        if (button == null) button = findNodeByAnyText(root, "发送评论", "发布评论", "发送", "发布");
        if (button == null) button = findBestCommentButton(root, edit);
        if (button != null && clickNode(button)) {
            rememberNodeId(MainActivity.KEY_COMMENT_SEND_ID, button);
            return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findBestCommentButton(AccessibilityNodeInfo root,
                                                         AccessibilityNodeInfo edit) {
        if (root == null) return null;
        Rect editBounds = new Rect();
        if (edit != null) edit.getBoundsInScreen(editBounds);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("评论")) {
            String label = nodeText(node).trim();
            if (!(label.equals("评论") || label.equals("发送评论") || label.equals("发布评论"))) continue;
            AccessibilityNodeInfo clickable = findClickableAncestor(node, 4);
            if (clickable == null || !clickable.isVisibleToUser()) continue;
            Rect bounds = new Rect();
            clickable.getBoundsInScreen(bounds);
            int score = 0;
            if (bounds.centerX() > screenWidth * 0.60) score += 4;
            if (bounds.centerY() < screenHeight * 0.28) score += 2;
            if (!editBounds.isEmpty()) {
                if (bounds.bottom >= editBounds.top - dp(80)
                        && bounds.top <= editBounds.bottom + dp(80)) score += 8;
                if (bounds.left >= editBounds.centerX()) score += 3;
            }
            if (bounds.height() <= dp(80)) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findEditableLearned(AccessibilityNodeInfo root, String key) {
        AccessibilityNodeInfo learned = findByLearnedId(root, key);
        return learned != null ? learned : findEditable(root);
    }

    private AccessibilityNodeInfo findByLearnedId(AccessibilityNodeInfo root, String key) {
        if (root == null) return null;
        String id = prefString(key, "");
        if (id.isEmpty()) return null;
        try {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(id)) {
                if (node.isVisibleToUser()) return node;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void rememberNodeId(String key, AccessibilityNodeInfo node) {
        if (node == null) return;
        String id = node.getViewIdResourceName();
        if (id == null || id.trim().isEmpty()) {
            AccessibilityNodeInfo clickable = findClickableAncestor(node, 3);
            if (clickable != null) id = clickable.getViewIdResourceName();
        }
        if (id != null && !id.trim().isEmpty()) {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putString(key, id).apply();
        }
    }

    private AccessibilityNodeInfo findNodeByAnyText(AccessibilityNodeInfo root, String... labels) {
        if (root == null) return null;
        for (String label : labels) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                if (node.isVisibleToUser() && findClickableAncestor(node, 4) != null) return node;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser() && (node.isEditable()
                || "android.widget.EditText".contentEquals(node.getClassName()))) return node;
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

    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node, int maxLevels) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i <= maxLevels && current != null; i++) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo clickable = findClickableAncestor(node, 5);
        return clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean setText(AccessibilityNodeInfo node, String value) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private void collectText(AccessibilityNodeInfo node, List<String> result, int depth) {
        if (node == null || depth > 6) return;
        if (node.getText() != null && !node.getText().toString().trim().isEmpty())
            result.add(node.getText().toString());
        if (node.getContentDescription() != null
                && !node.getContentDescription().toString().trim().isEmpty())
            result.add(node.getContentDescription().toString());
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), result, depth + 1);
    }

    private String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder value = new StringBuilder();
        if (node.getText() != null) value.append(node.getText());
        if (node.getContentDescription() != null) value.append(' ').append(node.getContentDescription());
        return value.toString();
    }

    private boolean isCommonListLabel(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("私信") || v.equals("发私信") || v.equals("互相关注")
                || v.equals("已关注") || v.equals("关注") || v.equals("粉丝")
                || v.equals("微博") || v.equals("返回") || v.contains("分钟前")
                || v.contains("小时前") || v.matches("[0-9:：/.-]+") || v.length() > 40;
    }

    private boolean looksLikeMutualList(AccessibilityNodeInfo root) {
        if (root == null) return false;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        for (String title : new String[]{"互相关注", "互关好友", "互关"}) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(title)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!node.isVisibleToUser() || !nodeText(node).trim().contains(title)) continue;
                // 兼容两种页面：顶部标题写“互相关注”，或每个好友右侧显示“互相关注”标识。
                boolean topTitle = bounds.centerY() < dp(190);
                boolean rightSideBadge = bounds.centerY() > dp(130)
                        && bounds.centerX() > screenWidth * 0.55;
                if (topTitle || rightSideBadge) return true;
            }
        }
        return false;
    }

    private boolean isKeyboardVisible() {
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private boolean isWeiboForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null
                && "com.sina.weibo".contentEquals(root.getPackageName());
    }

    private Set<String> getProcessedToday() {
        Set<String> saved = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getStringSet(MainActivity.KEY_PROCESSED, new HashSet<>());
        Set<String> todayValues = new HashSet<>();
        String prefix = today() + "|";
        for (String value : saved) if (value.startsWith(prefix)) todayValues.add(value);
        return todayValues;
    }

    private void rememberProcessedToday(String user) {
        Set<String> saved = new HashSet<>(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getStringSet(MainActivity.KEY_PROCESSED, new HashSet<>()));
        String prefix = today() + "|";
        saved.removeIf(value -> !value.startsWith(prefix));
        saved.add(todayKey(user));
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putStringSet(MainActivity.KEY_PROCESSED, saved).apply();
    }

    private String todayKey(String user) {
        return today() + "|" + user;
    }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    private String prefString(String key, String fallback) {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString(key, fallback);
    }

    private int sessionLimit() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getInt(MainActivity.KEY_LIMIT, 0);
    }

    private long delayMs() {
        int seconds = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getInt(MainActivity.KEY_DELAY, 5);
        return Math.max(3, Math.min(15, seconds)) * 1000L;
    }

    private void pauseWithNext(String reason, State next) {
        stateBeforePause = next;
        state = State.PAUSED;
        handler.removeCallbacks(stepRunnable);
        if (primary != null) primary.setText("继续/重试");
        setStatus(reason);
    }

    private void pause(String reason) {
        State next = (state == State.IDLE || state == State.PAUSED) ? State.FIND_TARGET : state;
        pauseWithNext(reason, next);
    }

    private void stopTask(String reason) {
        state = State.IDLE;
        handler.removeCallbacks(stepRunnable);
        currentUser = "";
        if (primary != null) primary.setText("开始");
        setStatus(reason);
    }

    private void scheduleStep(long delay) {
        handler.removeCallbacks(stepRunnable);
        handler.postDelayed(stepRunnable, delay);
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private void handleFatalError(String stage, Throwable error) {
        Log.e(TAG, stage, error);
        handler.removeCallbacks(stepRunnable);
        stateBeforePause = state == State.IDLE ? State.FIND_TARGET : state;
        state = State.PAUSED;
        StringBuilder detail = new StringBuilder();
        detail.append(stage).append("：").append(error.getClass().getSimpleName());
        if (error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
            detail.append(" - ").append(error.getMessage().trim());
        }
        int added = 0;
        for (StackTraceElement element : error.getStackTrace()) {
            if (element.getClassName().startsWith("com.example.weibospark")) {
                detail.append("\n").append(element.getFileName())
                        .append(":").append(element.getLineNumber());
                if (++added >= 4) break;
            }
        }
        String saved = detail.length() > 1800 ? detail.substring(0, 1800) : detail.toString();
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putString(MainActivity.KEY_LAST_ERROR, saved).apply();
        if (primary != null) primary.setText("继续/重试");
        setStatus("发生错误，已安全暂停。请回助手首页查看详情");
    }

    private String stateLabel(State value) {
        switch (value) {
            case FIND_TARGET: return "寻找下一位互关好友";
            case WAIT_PROFILE: return "打开好友主页和私信";
            case WAIT_CHAT: return "等待私信页";
            case SEND_MESSAGE: return "发送私信";
            case CLOSE_KEYBOARD: return "关闭输入状态";
            case BACK_TO_PROFILE: return "返回好友主页";
            case OPEN_COMMENT: return "打开微博评论";
            case SEND_COMMENT: return "发送评论";
            case RETURN_TO_LIST: return "返回互关列表";
            default: return "继续执行";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
