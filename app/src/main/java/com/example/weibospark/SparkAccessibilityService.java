package com.example.weibospark;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Path;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SparkAccessibilityService extends AccessibilityService {
    private static final String TAG = "WeiboMutualService";

    private enum State {
        IDLE, SCAN_LIST, FIND_TARGET, WAIT_PROFILE, WAIT_CHAT, SEND_MESSAGE, VERIFY_MESSAGE_TEXT, CLICK_MESSAGE_SEND,
        CLOSE_KEYBOARD, BACK_TO_PROFILE, OPEN_COMMENT, OPEN_COMMENT_INPUT,
        WAIT_COMMENT_INPUT, SEND_COMMENT, VERIFY_COMMENT_TEXT, CLICK_COMMENT_SEND,
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
    private int profileScrollAttempts;
    private int unchangedProfileScrolls;
    private String lastProfileFingerprint = "";
    private boolean messagePasteTried;
    private boolean commentPasteTried;
    private int unchangedScanScrolls;
    private String lastScanFingerprint = "";

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
        // 每个动作都会安排下一步。这里不因页面刷新事件提前执行，避免输入文字后
        // “发送/评论”按钮尚未生成就立即查找并误报失败。
        if (state == State.IDLE && primary != null) {
            boolean scanMode = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                    .getBoolean(MainActivity.KEY_SCAN_MODE, false);
            primary.setText(scanMode ? "开始扫描" : "开始");
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
            profileScrollAttempts = 0;
            unchangedProfileScrolls = 0;
            lastProfileFingerprint = "";
            currentUser = "";
            messagePasteTried = false;
            commentPasteTried = false;
            skippedThisSession.clear();
            boolean scanMode = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                    .getBoolean(MainActivity.KEY_SCAN_MODE, false);
            unchangedScanScrolls = 0;
            lastScanFingerprint = "";
            state = scanMode ? State.SCAN_LIST : State.FIND_TARGET;
            primary.setText("暂停");
            setStatus(scanMode
                    ? "扫描模式：正在收集互关昵称和可见 ID，不会发送消息"
                    : "全自动模式：只处理已勾选且当天未完成的好友…");
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
        if (looksLikeMutualList(getRootInActiveWindow())) {
            currentUser = "";
            state = State.FIND_TARGET;
            setStatus("本轮已跳过，继续下一位");
        } else {
            state = State.RETURN_TO_LIST;
            backAttempts = 0;
            setStatus("本轮跳过：“" + currentUser + "”，正在返回互关列表");
        }
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
            case SCAN_LIST:
                scanMutualList();
                break;
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
                fillMessage();
                break;
            case VERIFY_MESSAGE_TEXT:
                verifyMessageText();
                break;
            case CLICK_MESSAGE_SEND:
                clickMessageSend();
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
            case OPEN_COMMENT_INPUT:
                openBottomCommentInput();
                break;
            case WAIT_COMMENT_INPUT:
                waitForCommentInput();
                break;
            case SEND_COMMENT:
                fillComment();
                break;
            case VERIFY_COMMENT_TEXT:
                verifyCommentText();
                break;
            case CLICK_COMMENT_SEND:
                clickCommentSend();
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
        if (getSelectedTargets().isEmpty()) {
            stopTask("尚未选择需要打卡的好友，请先在 App 中扫描并勾选名单");
            return;
        }
        List<AccessibilityNodeInfo> markers = findTargetMarkers(root);
        int identifiedRows = 0;
        for (AccessibilityNodeInfo marker : markers) {
            AccessibilityNodeInfo row = findLikelyRow(marker);
            String user = extractUserLabel(row);
            if (user.isEmpty()) continue;
            identifiedRows++;
            if (!isSelectedUser(user)) continue;
            if (processed.contains(todayKey(user)) || skippedThisSession.contains(user)) continue;
            AccessibilityNodeInfo target = findProfileClickTarget(row, marker);
            if (target != null && clickNode(target)) {
                currentUser = user;
                profileScrollAttempts = 0;
                unchangedProfileScrolls = 0;
                lastProfileFingerprint = "";
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

    private void scanMutualList() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !looksLikeMutualList(root)) {
            pauseWithNext("扫描模式：请停在微博“互相关注”列表后继续", State.SCAN_LIST);
            return;
        }
        Set<String> discovered = new HashSet<>(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getStringSet(MainActivity.KEY_DISCOVERED_TARGETS, new HashSet<>()));
        for (AccessibilityNodeInfo marker : findTargetMarkers(root)) {
            AccessibilityNodeInfo row = findLikelyRow(marker);
            String name = extractUserLabel(row);
            if (!name.isEmpty()) discovered.add(name + "\t" + extractVisibleId(row, name));
        }
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putStringSet(MainActivity.KEY_DISCOVERED_TARGETS, discovered).apply();

        String fingerprint = pageFingerprint(root);
        if (!lastScanFingerprint.isEmpty() && lastScanFingerprint.equals(fingerprint)) {
            unchangedScanScrolls++;
        } else {
            unchangedScanScrolls = 0;
        }
        lastScanFingerprint = fingerprint;
        if (scannedPages < 100 && unchangedScanScrolls < 2 && swipeListFromCenter()) {
            scannedPages++;
            setStatus("已收集 " + discovered.size() + " 人，继续扫描列表…");
            scheduleStep(Math.max(800L, delayMs()));
        } else {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(MainActivity.KEY_SCAN_MODE, false).apply();
            stopTask("扫描完成，共获取 " + discovered.size() + " 人；请回 App 勾选并保存");
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

    private void fillMessage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_MESSAGE_INPUT_ID);
        String expected = prefString(MainActivity.KEY_MESSAGE, "续个火花✨");
        if (edit == null || !focusAndSetText(edit, expected)) {
            pauseWithNext("无法聚焦并填写私信输入框，请调整页面后继续", State.SEND_MESSAGE);
            return;
        }
        rememberNodeId(MainActivity.KEY_MESSAGE_INPUT_ID, edit);
        messagePasteTried = false;
        state = State.VERIFY_MESSAGE_TEXT;
        setStatus("已请求输入私信，正在确认文字是否真正显示…");
        scheduleStep(Math.max(1500L, delayMs() / 2));
    }

    private void verifyMessageText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String expected = prefString(MainActivity.KEY_MESSAGE, "续个火花✨");
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_MESSAGE_INPUT_ID);
        if (textIsVisible(root, edit, expected)) {
            state = State.CLICK_MESSAGE_SEND;
            setStatus("已确认私信文字显示，等待“发送”按钮出现…");
            scheduleStep(800);
            return;
        }
        if (!messagePasteTried && edit != null && pasteText(edit, expected)) {
            messagePasteTried = true;
            setStatus("直接输入未生效，已改用粘贴，正在再次确认…");
            scheduleStep(1500);
            return;
        }
        pauseWithNext("私信文字没有真正进入输入框，请点一下输入框后继续", State.SEND_MESSAGE);
    }

    private void clickMessageSend() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (!clickMessageSendButton(root)) {
            pauseWithNext("输入完成后仍没找到私信“发送”按钮，请调整页面后继续", State.CLICK_MESSAGE_SEND);
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
            state = State.OPEN_COMMENT_INPUT;
            setStatus("微博已打开，下一步点击屏幕底部的消息/评论图标");
            scheduleStep(delayMs());
        } else {
            String fingerprint = pageFingerprint(root);
            if (!lastProfileFingerprint.isEmpty() && lastProfileFingerprint.equals(fingerprint)) {
                unchangedProfileScrolls++;
            } else {
                unchangedProfileScrolls = 0;
            }
            lastProfileFingerprint = fingerprint;
            if (profileScrollAttempts < 40 && unchangedProfileScrolls < 2
                    && swipeProfileFromCenter()) {
                profileScrollAttempts++;
                setStatus("当前屏没有可评论微博，正在从屏幕中部纵向滑动查找（第 "
                        + profileScrollAttempts + " 次）…");
                scheduleStep(delayMs());
            } else {
                // 私信已经发送成功。主页到底仍不可评论时记录当天已处理，避免重复私信。
                rememberProcessedToday(currentUser);
                sessionCount++;
                state = State.RETURN_TO_LIST;
                backAttempts = 0;
                setStatus("“" + currentUser + "”主页已滑到底，未找到可评论微博；"
                        + "已记录私信并返回互关列表");
                scheduleStep(delayMs());
            }
        }
    }

    private void openBottomCommentInput() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo existing = findEditableLearned(root, MainActivity.KEY_COMMENT_INPUT_ID);
        if (existing != null) {
            state = State.SEND_COMMENT;
            scheduleStep(200);
            return;
        }
        AccessibilityNodeInfo trigger = findBottomCommentInputTrigger(root);
        boolean clicked = trigger != null && clickNode(trigger);
        if (!clicked) clicked = tapBottomCommentFallback();
        if (clicked) {
            state = State.WAIT_COMMENT_INPUT;
            setStatus(trigger != null
                    ? "已点击底部消息/评论图标，等待评论输入框出现…"
                    : "图标无文字描述，已点击底部中央位置，等待输入框出现…");
            scheduleStep(delayMs());
        } else {
            pauseWithNext("没找到屏幕底部的消息/评论图标，请调整页面后继续", State.OPEN_COMMENT_INPUT);
        }
    }

    private void waitForCommentInput() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_COMMENT_INPUT_ID);
        if (edit != null) {
            state = State.SEND_COMMENT;
            scheduleStep(200);
        } else {
            pauseWithNext("点击底部图标后仍未出现评论输入框，请调整页面后继续", State.OPEN_COMMENT_INPUT);
        }
    }

    private void fillComment() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_COMMENT_INPUT_ID);
        String expected = prefString(MainActivity.KEY_COMMENT, "踩踩宝贝");
        if (edit == null || !focusAndSetText(edit, expected)) {
            pauseWithNext("无法聚焦并填写评论输入框，请点输入框后继续", State.SEND_COMMENT);
            return;
        }
        rememberNodeId(MainActivity.KEY_COMMENT_INPUT_ID, edit);
        commentPasteTried = false;
        state = State.VERIFY_COMMENT_TEXT;
        setStatus("已请求输入评论，正在确认文字是否真正显示…");
        scheduleStep(Math.max(1500L, delayMs() / 2));
    }

    private void verifyCommentText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String expected = prefString(MainActivity.KEY_COMMENT, "踩踩宝贝");
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_COMMENT_INPUT_ID);
        if (textIsVisible(root, edit, expected)) {
            state = State.CLICK_COMMENT_SEND;
            setStatus("已确认评论文字显示，等待“评论”按钮出现…");
            scheduleStep(800);
            return;
        }
        if (!commentPasteTried && edit != null && pasteText(edit, expected)) {
            commentPasteTried = true;
            setStatus("直接输入未生效，已改用粘贴，正在再次确认…");
            scheduleStep(1500);
            return;
        }
        pauseWithNext("评论文字没有真正进入输入框，请点一下输入框后继续", State.SEND_COMMENT);
    }

    private void clickCommentSend() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_COMMENT_INPUT_ID);
        if (!clickCommentSendButton(root, edit)) {
            pauseWithNext("输入完成后没找到提交评论的“评论”按钮，请调整页面后继续", State.CLICK_COMMENT_SEND);
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
        // 评论提交后至少返回一次，避免把好友主页上的关系按钮误判成列表。
        if (backAttempts > 0 && looksLikeMutualList(root)) {
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

    private String extractVisibleId(AccessibilityNodeInfo row, String fallbackName) {
        List<String> values = new ArrayList<>();
        collectText(row, values, 0);
        Pattern idPattern = Pattern.compile("(?i)(?:微博)?ID\\s*[:：]\\s*([A-Za-z0-9_.-]+)");
        for (String value : values) {
            Matcher matcher = idPattern.matcher(value);
            if (matcher.find()) return matcher.group(1);
            String clean = value.trim();
            if (clean.startsWith("@") && clean.length() > 1 && !clean.contains(" ")) {
                return clean.substring(1);
            }
        }
        // 互关列表通常不暴露数字 UID；此时使用昵称作为稳定选择标识。
        return fallbackName;
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

    private AccessibilityNodeInfo findBottomCommentInputTrigger(AccessibilityNodeInfo root) {
        if (root == null) return null;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String label : new String[]{"写评论", "说点什么", "评论", "消息"}) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                AccessibilityNodeInfo clickable = findClickableAncestor(node, 4);
                if (clickable == null || !clickable.isVisibleToUser()) continue;
                Rect bounds = new Rect();
                clickable.getBoundsInScreen(bounds);
                if (bounds.centerY() < screenHeight * 0.62) continue;
                int score = 0;
                String text = nodeText(node).trim();
                if (text.contains("写评论") || text.contains("说点什么")) score += 10;
                if (text.contains("评论")) score += 7;
                if (text.contains("消息")) score += 5;
                if (bounds.centerX() > screenWidth * 0.18
                        && bounds.centerX() < screenWidth * 0.82) score += 3;
                if (bounds.centerY() > screenHeight * 0.78) score += 3;
                if (score > bestScore) {
                    bestScore = score;
                    best = node;
                }
            }
        }
        if (best != null) return best;

        // 图标可能没有文字或内容描述：从底部工具栏的可点击小控件中选择最靠近中间的一个。
        List<AccessibilityNodeInfo> clickable = new ArrayList<>();
        collectClickable(root, clickable, 0);
        for (AccessibilityNodeInfo node : clickable) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.centerY() < screenHeight * 0.70) continue;
            if (bounds.width() > screenWidth * 0.42 || bounds.height() > dp(120)) continue;
            String text = nodeText(node);
            if (text.contains("转发") || text.contains("赞") || text.contains("收藏")) continue;
            int score = 10 - (int) (Math.abs(bounds.centerX() - screenWidth * 0.50)
                    / Math.max(1, screenWidth * 0.08));
            if (bounds.centerY() > screenHeight * 0.82) score += 4;
            if (score > bestScore) {
                bestScore = score;
                best = node;
            }
        }
        return best;
    }

    private boolean tapBottomCommentFallback() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        // 88% 在部分小米机型上仍位于内容/广告区域；下移到约 95%，但保留
        // 至少 36dp 的系统导航安全距离。
        float targetY = Math.min(height * 0.95f, height - dp(36));
        Path path = new Path();
        path.moveTo(width * 0.50f, targetY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 80);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
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

    private boolean swipeProfileFromCenter() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        // 手指从内容区中下部向上划，页面向下浏览；不再调用相册等子控件的滚动动作。
        path.moveTo(width * 0.50f, height * 0.68f);
        path.lineTo(width * 0.50f, height * 0.30f);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 450);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private boolean swipeListFromCenter() {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(width * 0.50f, height * 0.76f);
        path.lineTo(width * 0.50f, height * 0.32f);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 420);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private String pageFingerprint(AccessibilityNodeInfo root) {
        StringBuilder value = new StringBuilder();
        int[] count = new int[]{0};
        collectFingerprint(root, value, count, 0);
        return Integer.toHexString(value.toString().hashCode());
    }

    private void collectFingerprint(AccessibilityNodeInfo node, StringBuilder value,
                                    int[] count, int depth) {
        if (node == null || depth > 16 || count[0] >= 120) return;
        if (node.isVisibleToUser()) {
            String text = nodeText(node).trim();
            if (!text.isEmpty()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                value.append(text).append('@').append(bounds.top).append(':').append(bounds.bottom).append('|');
                count[0]++;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectFingerprint(node.getChild(i), value, count, depth + 1);
        }
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

    private boolean focusAndSetText(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return setText(node, value);
    }

    private boolean pasteText(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("微博助手输入", value));
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    private boolean textIsVisible(AccessibilityNodeInfo root,
                                  AccessibilityNodeInfo edit, String expected) {
        if (expected == null || expected.isEmpty()) return false;
        if (edit != null && edit.getText() != null
                && edit.getText().toString().contains(expected)) return true;
        if (root == null) return false;
        try {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(expected)) {
                if (node.isVisibleToUser()) return true;
            }
        } catch (Throwable ignored) { }
        return false;
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
        int rightSideBadgeCount = 0;
        for (String title : new String[]{"互相关注", "互关好友", "互关"}) {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(title)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!node.isVisibleToUser() || !nodeText(node).trim().contains(title)) continue;
                // 兼容两种页面：顶部标题写“互相关注”，或每个好友右侧显示“互相关注”标识。
                boolean topTitle = bounds.centerY() < dp(190)
                        && bounds.centerX() > screenWidth * 0.20
                        && bounds.centerX() < screenWidth * 0.80;
                boolean rightSideBadge = bounds.centerY() > dp(130)
                        && bounds.centerX() > screenWidth * 0.55;
                if (topTitle) return true;
                if (rightSideBadge) rightSideBadgeCount++;
            }
        }
        // 好友主页通常只有一个关系按钮；列表同屏通常会出现多个右侧标识。
        return rightSideBadgeCount >= 2;
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

    private Set<String> getSelectedTargets() {
        return new HashSet<>(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getStringSet(MainActivity.KEY_SELECTED_TARGETS, new HashSet<>()));
    }

    private boolean isSelectedUser(String user) {
        for (String entry : getSelectedTargets()) {
            String[] parts = entry.split("\\t", 2);
            if (parts.length > 0 && parts[0].equals(user)) return true;
            if (parts.length > 1 && parts[1].equals(user)) return true;
        }
        return false;
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
                .getInt(MainActivity.KEY_DELAY, 3);
        return Math.max(1, Math.min(15, seconds)) * 1000L;
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
        boolean wasScanning = state == State.SCAN_LIST
                || (state == State.PAUSED && stateBeforePause == State.SCAN_LIST);
        state = State.IDLE;
        handler.removeCallbacks(stepRunnable);
        currentUser = "";
        if (wasScanning) {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(MainActivity.KEY_SCAN_MODE, false).apply();
        }
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
            case SCAN_LIST: return "扫描互关名单";
            case FIND_TARGET: return "寻找下一位互关好友";
            case WAIT_PROFILE: return "打开好友主页和私信";
            case WAIT_CHAT: return "等待私信页";
            case SEND_MESSAGE: return "填写私信";
            case VERIFY_MESSAGE_TEXT: return "确认私信文字已显示";
            case CLICK_MESSAGE_SEND: return "点击私信发送按钮";
            case CLOSE_KEYBOARD: return "关闭输入状态";
            case BACK_TO_PROFILE: return "返回好友主页";
            case OPEN_COMMENT: return "打开微博评论";
            case OPEN_COMMENT_INPUT: return "点击底部评论图标";
            case WAIT_COMMENT_INPUT: return "等待评论输入框";
            case SEND_COMMENT: return "填写评论";
            case VERIFY_COMMENT_TEXT: return "确认评论文字已显示";
            case CLICK_COMMENT_SEND: return "点击评论按钮";
            case RETURN_TO_LIST: return "返回互关列表";
            default: return "继续执行";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
