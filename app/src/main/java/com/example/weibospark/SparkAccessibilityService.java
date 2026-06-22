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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SparkAccessibilityService extends AccessibilityService {
    private enum State {
        IDLE, FIND_SPARK_CHAT, WAIT_CHAT_OPEN, SEND_MESSAGE,
        WAIT_FIRST_MESSAGE_CONFIRM, OPEN_PROFILE, OPEN_COMMENT, SEND_COMMENT,
        WAIT_FIRST_COMMENT_CONFIRM, RETURN_MESSAGES, PAUSED
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepRunnable = this::runStep;
    private State state = State.IDLE;
    private State stateBeforePause = State.FIND_SPARK_CHAT;
    private WindowManager windowManager;
    private LinearLayout overlay;
    private TextView status;
    private Button primary;
    private Button confirm;
    private String currentUser = "";
    private int sessionCount;
    private int backAttempts;
    private int scannedPages;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        showOverlay();
        updateIdleText();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (isRunningStep()) scheduleStep(450);
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

    private boolean isRunningStep() {
        return state != State.IDLE && state != State.PAUSED
                && state != State.WAIT_FIRST_MESSAGE_CONFIRM
                && state != State.WAIT_FIRST_COMMENT_CONFIRM;
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
        overlay.addView(status, new LinearLayout.LayoutParams(dp(260),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        primary = smallButton("开始");
        primary.setOnClickListener(v -> onPrimary());
        confirm = smallButton("首次确认");
        confirm.setVisibility(View.GONE);
        confirm.setOnClickListener(v -> onFirstConfirm());
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
            state = State.FIND_SPARK_CHAT;
            primary.setText("暂停");
            setStatus(isCalibrated()
                    ? "自动模式：正在消息页寻找火花会话…"
                    : "学习模式：请选择消息页，首次流程只确认两次");
            scheduleStep(250);
        } else if (state == State.PAUSED) {
            state = stateBeforePause;
            primary.setText("暂停");
            setStatus("继续：" + stateLabel(state));
            scheduleStep(250);
        } else {
            pause("已暂停；调整微博页面后可继续");
        }
    }

    private void onFirstConfirm() {
        confirm.setVisibility(View.GONE);
        if (state == State.WAIT_FIRST_MESSAGE_CONFIRM) {
            if (!clickSendButton(MainActivity.KEY_MESSAGE_SEND_ID)) {
                pauseWithNext("没找到消息发送按钮，请点输入框后重试", State.SEND_MESSAGE);
                return;
            }
            state = State.OPEN_PROFILE;
            setStatus("首次消息已发送，正在学习主页入口…");
            scheduleStep(delayMs());
        } else if (state == State.WAIT_FIRST_COMMENT_CONFIRM) {
            if (!clickSendButton(MainActivity.KEY_COMMENT_SEND_ID)) {
                pauseWithNext("没找到评论发送按钮，请调整页面后重试", State.SEND_COMMENT);
                return;
            }
            finishCurrentUser(true);
        }
    }

    private void runStep() {
        if (!isRunningStep()) return;
        if (!isWeiboForeground()) {
            pause("请切回微博，再点继续");
            return;
        }
        if (sessionCount >= sessionLimit()) {
            stopTask("已完成本轮上限 " + sessionCount + " 人");
            return;
        }
        switch (state) {
            case FIND_SPARK_CHAT:
                findAndOpenSparkChat();
                break;
            case WAIT_CHAT_OPEN:
                waitForChat();
                break;
            case SEND_MESSAGE:
                fillAndSendMessage();
                break;
            case OPEN_PROFILE:
                openProfileFromChat();
                break;
            case OPEN_COMMENT:
                openCommentComposer();
                break;
            case SEND_COMMENT:
                fillAndSendComment();
                break;
            case RETURN_MESSAGES:
                returnToMessages();
                break;
            default:
                break;
        }
    }

    private void findAndOpenSparkChat() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            pause("暂时读不到微博页面，请重试");
            return;
        }

        // 允许用户在图标暂时无法识别时手动进入一个火花会话继续学习。
        if (findEditable(root) != null && !looksLikeMessageList(root)) {
            currentUser = extractChatTitle(root);
            state = State.SEND_MESSAGE;
            setStatus("检测到已打开聊天：" + currentUser);
            scheduleStep(300);
            return;
        }

        List<AccessibilityNodeInfo> markers = findSparkMarkers(root);
        Set<String> processed = getProcessedToday();
        for (AccessibilityNodeInfo marker : markers) {
            AccessibilityNodeInfo row = findLikelyRow(marker);
            String user = extractUserLabel(row);
            if (user.isEmpty() || processed.contains(todayKey(user))) continue;
            AccessibilityNodeInfo clickTarget = findClickableAncestor(row, 5);
            if (clickTarget == null) clickTarget = findClickableAncestor(marker, 6);
            if (clickTarget != null && clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                rememberNodeId(MainActivity.KEY_SPARK_MARKER_ID, marker);
                currentUser = user;
                state = State.WAIT_CHAT_OPEN;
                setStatus("发现火花会话：“" + user + "”，正在打开");
                scheduleStep(delayMs());
                return;
            }
        }

        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null && scannedPages < 20
                && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            scannedPages++;
            setStatus("本屏没有新的火花会话，继续向下检查…");
            scheduleStep(delayMs());
        } else if (markers.isEmpty()) {
            pause("没有读到火花标识。请停在微博消息列表；若图标无文字描述，可先手动打开一个火花聊天再继续");
        } else {
            stopTask("消息列表已检查完成，本次共处理 " + sessionCount + " 人");
        }
    }

    private void waitForChat() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (findEditableLearned(root, MainActivity.KEY_MESSAGE_INPUT_ID) != null) {
            state = State.SEND_MESSAGE;
            scheduleStep(200);
        } else {
            pauseWithNext("聊天页没有识别到输入框，请点一下输入框后继续", State.SEND_MESSAGE);
        }
    }

    private void fillAndSendMessage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo edit = findEditableLearned(root, MainActivity.KEY_MESSAGE_INPUT_ID);
        if (edit == null || !setText(edit, prefString(MainActivity.KEY_MESSAGE, "续个火花✨"))) {
            pauseWithNext("没有识别到消息输入框，请点输入框后继续", State.SEND_MESSAGE);
            return;
        }
        rememberNodeId(MainActivity.KEY_MESSAGE_INPUT_ID, edit);
        if (!isCalibrated()) {
            state = State.WAIT_FIRST_MESSAGE_CONFIRM;
            confirm.setText("首次确认消息");
            confirm.setVisibility(View.VISIBLE);
            setStatus("首次学习：核对给“" + currentUser + "”的消息，然后只确认这一次");
        } else if (clickSendButton(MainActivity.KEY_MESSAGE_SEND_ID)) {
            state = State.OPEN_PROFILE;
            setStatus("消息已自动发送，正在进入“" + currentUser + "”主页");
            scheduleStep(delayMs());
        } else {
            pauseWithNext("没有找到消息发送按钮，请调整页面后继续", State.SEND_MESSAGE);
        }
    }

    private void openProfileFromChat() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo entry = findByLearnedId(root, MainActivity.KEY_PROFILE_ENTRY_ID);
        if (entry == null) entry = findProfileHeader(root);
        if (entry != null && clickNode(entry)) {
            rememberNodeId(MainActivity.KEY_PROFILE_ENTRY_ID, entry);
            state = State.OPEN_COMMENT;
            setStatus("主页已打开，寻找第一条评论入口…");
            scheduleStep(delayMs());
        } else {
            pauseWithNext("没有识别到聊天页顶部头像/昵称，请手动点进主页后继续", State.OPEN_COMMENT);
        }
    }

    private void openCommentComposer() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo entry = findByLearnedId(root, MainActivity.KEY_COMMENT_ENTRY_ID);
        if (entry == null) entry = findCommentAction(root);
        if (entry != null && clickNode(entry)) {
            rememberNodeId(MainActivity.KEY_COMMENT_ENTRY_ID, entry);
            state = State.SEND_COMMENT;
            setStatus("评论入口已打开，正在填写主页留言…");
            scheduleStep(delayMs());
        } else {
            pauseWithNext("主页没有识别到评论入口，请滚到任意微博后继续", State.OPEN_COMMENT);
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
        if (!isCalibrated()) {
            state = State.WAIT_FIRST_COMMENT_CONFIRM;
            confirm.setText("首次确认评论");
            confirm.setVisibility(View.VISIBLE);
            setStatus("首次学习：核对主页留言，然后只确认这一次");
        } else if (clickSendButton(MainActivity.KEY_COMMENT_SEND_ID)) {
            finishCurrentUser(false);
        } else {
            pauseWithNext("没有找到评论发送按钮，请调整页面后继续", State.SEND_COMMENT);
        }
    }

    private void finishCurrentUser(boolean firstLearning) {
        rememberProcessedToday(currentUser);
        sessionCount++;
        if (firstLearning) {
            getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(MainActivity.KEY_CALIBRATED, true).apply();
        }
        state = State.RETURN_MESSAGES;
        backAttempts = 0;
        setStatus((firstLearning ? "学习完成；以后自动执行。" : "已自动完成。")
                + "正在返回消息列表（本次 " + sessionCount + " 人）");
        scheduleStep(delayMs());
    }

    private void returnToMessages() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (looksLikeMessageList(root)) {
            state = State.FIND_SPARK_CHAT;
            backAttempts = 0;
            scannedPages = 0;
            setStatus("已自动回到消息列表，寻找下一位…");
            scheduleStep(delayMs());
            return;
        }
        if (backAttempts >= 6) {
            pauseWithNext("没能自动回到消息列表，请手动返回“消息”后继续", State.FIND_SPARK_CHAT);
            return;
        }
        backAttempts++;
        performGlobalAction(GLOBAL_ACTION_BACK);
        scheduleStep(delayMs());
    }

    private List<AccessibilityNodeInfo> findSparkMarkers(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        String learnedId = prefString(MainActivity.KEY_SPARK_MARKER_ID, "");
        if (!learnedId.isEmpty()) {
            try {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(learnedId)) {
                    // 同一个 View ID 可能被所有消息行复用，仍必须核对火花描述，避免误发。
                    if (containsSparkDescriptor(nodeText(node))) result.add(node);
                }
            }
            catch (Exception ignored) { }
        }
        collectSparkMarkers(root, result, 0);
        return result;
    }

    private void collectSparkMarkers(AccessibilityNodeInfo node,
                                     List<AccessibilityNodeInfo> result, int depth) {
        if (node == null || depth > 18) return;
        if (containsSparkDescriptor(nodeText(node))) {
            if (!result.contains(node)) result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectSparkMarkers(node.getChild(i), result, depth + 1);
        }
    }

    private boolean containsSparkDescriptor(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        return value.contains("火花") || value.contains("🔥") || value.contains("连续聊")
                || value.contains("续火") || value.contains("spark");
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
            String clean = value.trim();
            if (clean.length() < 1 || clean.length() > 40 || isCommonLabel(clean)) continue;
            return clean;
        }
        return "";
    }

    private String extractChatTitle(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> top = new ArrayList<>();
        collectTopNodes(root, top, 0);
        for (AccessibilityNodeInfo node : top) {
            String value = nodeText(node).trim();
            if (!value.isEmpty() && !isCommonLabel(value)) return value;
        }
        return "当前火花联系人";
    }

    private AccessibilityNodeInfo findProfileHeader(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> top = new ArrayList<>();
        collectTopNodes(root, top, 0);
        for (AccessibilityNodeInfo node : top) {
            if (!currentUser.isEmpty() && nodeText(node).contains(currentUser)
                    && findClickableAncestor(node, 3) != null) return node;
        }
        for (AccessibilityNodeInfo node : top) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String value = nodeText(node);
            if (bounds.centerX() > getResources().getDisplayMetrics().widthPixels * 0.22
                    && !value.contains("返回") && findClickableAncestor(node, 3) != null) return node;
        }
        return null;
    }

    private void collectTopNodes(AccessibilityNodeInfo node,
                                 List<AccessibilityNodeInfo> result, int depth) {
        if (node == null || depth > 10) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && bounds.centerY() > dp(25) && bounds.centerY() < dp(180)
                && (!nodeText(node).trim().isEmpty() || node.isClickable())) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectTopNodes(node.getChild(i), result, depth + 1);
    }

    private AccessibilityNodeInfo findCommentAction(AccessibilityNodeInfo root) {
        if (root == null) return null;
        int minY = getResources().getDisplayMetrics().heightPixels / 3;
        for (String label : new String[]{"评论", "Comment"}) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            for (AccessibilityNodeInfo node : nodes) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (node.isVisibleToUser() && bounds.centerY() > minY
                        && findClickableAncestor(node, 4) != null) return node;
            }
        }
        return null;
    }

    private boolean clickSendButton(String learnedKey) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo button = findByLearnedId(root, learnedKey);
        if (button == null) button = findNodeByAnyText(root, "发送", "发布", "Send");
        if (button != null && clickNode(button)) {
            rememberNodeId(learnedKey, button);
            return true;
        }
        return false;
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
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            for (AccessibilityNodeInfo node : nodes) if (node.isVisibleToUser()) return node;
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
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            for (AccessibilityNodeInfo node : nodes) {
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

    private boolean isCommonLabel(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return v.equals("消息") || v.equals("私信") || v.equals("发送") || v.equals("评论")
                || v.equals("返回") || v.equals("未关注人消息") || v.contains("火花")
                || v.contains("连续聊") || v.contains("分钟前") || v.contains("小时前")
                || v.matches("[0-9:：/-]+") || v.length() > 40;
    }

    private boolean looksLikeMessageList(AccessibilityNodeInfo root) {
        if (root == null || findEditable(root) != null) return false;
        boolean hasMessage = !root.findAccessibilityNodeInfosByText("消息").isEmpty();
        return hasMessage && findScrollable(root) != null;
    }

    private boolean isWeiboForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null
                && "com.sina.weibo".contentEquals(root.getPackageName());
    }

    private boolean isCalibrated() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_CALIBRATED, false);
    }

    private Set<String> getProcessedToday() {
        Set<String> saved = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getStringSet(MainActivity.KEY_PROCESSED, new HashSet<>());
        Set<String> today = new HashSet<>();
        String prefix = today() + "|";
        for (String value : saved) if (value.startsWith(prefix)) today.add(value);
        return today;
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
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getInt(MainActivity.KEY_LIMIT, 20);
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
        primary.setText("继续/重试");
        confirm.setVisibility(View.GONE);
        setStatus(reason);
    }

    private void pause(String reason) {
        State next = (state == State.IDLE || state == State.PAUSED)
                ? State.FIND_SPARK_CHAT : state;
        pauseWithNext(reason, next);
    }

    private void stopTask(String reason) {
        state = State.IDLE;
        handler.removeCallbacks(stepRunnable);
        currentUser = "";
        if (primary != null) primary.setText(isCalibrated() ? "开始续火花" : "开始学习");
        if (confirm != null) confirm.setVisibility(View.GONE);
        setStatus(reason);
    }

    private void updateIdleText() {
        if (primary != null) primary.setText(isCalibrated() ? "开始续火花" : "开始学习");
        setStatus(isCalibrated()
                ? "已学习。请进入微博“消息”页后开始"
                : "未学习。第一次成功后将自动记住操作步骤");
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
            case FIND_SPARK_CHAT: return "寻找火花会话";
            case WAIT_CHAT_OPEN: return "等待聊天页";
            case SEND_MESSAGE: return "发送续火花消息";
            case OPEN_PROFILE: return "进入对方主页";
            case OPEN_COMMENT: return "打开评论入口";
            case SEND_COMMENT: return "发送主页留言";
            case RETURN_MESSAGES: return "返回消息列表";
            default: return "继续执行";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
