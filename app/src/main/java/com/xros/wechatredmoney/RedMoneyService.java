package com.xros.wechatredmoney;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RedMoneyService extends AccessibilityService {

    private static final String TAG = "RedMoneyService";

    private enum State { IDLE, WAIT_DIALOG, OPENING }

    private static final long SCAN_INTERVAL = 200;
    private static final long CLICK_COOLDOWN = 3_000;
    private static final long CHAT_LIST_COOLDOWN = 10 * 60_000;
    private static final long STATE_TIMEOUT = 4_000;
    private static final int MAX_OPEN_ATTEMPTS = 8;
    private static final long OPEN_RETRY_INTERVAL = 350;
    private static final long SHOT_MIN_INTERVAL = 340;
    private static final long SHOT_SCAN_INTERVAL = 1_000;
    private static final long WATCHDOG_INTERVAL = 2_000;
    private static final int WATCHDOG_MAX_RESTARTS = 3;
    private static final long RECEIVE_CLOSE_GRACE = 1_000;
    private static final long OPEN_HIT_WAIT = 1_600;
    private static final long LIST_SCAN_INTERVAL = 1_500;
    private static final long LIST_RETURN_TIMEOUT = 8_000;
    private static final long LIST_ENTER_COOLDOWN = 15_000;
    private static final long[] LIST_RESCAN_DELAYS = {600, 1_500, 3_000};
    private static final long LIST_ENTER_CONFIRM = 1_500;
    private static final long LIST_SCAN_MAX_INTERVAL = 6_000;
    // 调试用：输出节点树、逐个空节点等详细日志
    private static final boolean VERBOSE = false;
    private static final long[] SHOT_RESCAN_DELAYS = {600, 1_500};
    private static final long PARTIAL_RESCAN_DELAY = 500;
    private static final long TAP_CONFIRM_TIMEOUT = 1_200;
    private static final long OPEN_FIRST_DELAY = 150;
    // 完整红包气泡约占屏高 0.098，低于此比例视为半露（被输入栏遮挡或仍在滑入）
    private static final float PACKET_MIN_HEIGHT_RATIO = 0.068f;
    // 截图识别失败时，依次尝试的“開”按钮纵向位置（占全屏高度比例）
    private static final float[] OPEN_Y_RATIOS = {0.62f, 0.66f, 0.70f, 0.58f, 0.74f};
    private static final Pattern AMOUNT = Pattern.compile("^(\\d+)\\.(\\d{2})$");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> clicked = new HashMap<>();
    private final Runnable resetState = () -> state = State.IDLE;

    private Config config;
    private FloatBall floatBall;
    private State state = State.IDLE;
    private boolean fromNotification;
    private long lastScan;
    private long lastShot;
    private long lastShotScan;
    private long lastListScan;
    private boolean enteredFromList;
    private ChatListDetector chatListDetector;
    private final Runnable listReturn = this::onListReturnTimeout;
    private final Runnable listRescan = () -> scanChatListByOcr(true);
    private boolean pendingListBack;
    private boolean enterConfirmed;
    private String enteredTitle;
    private int listMissCount;
    private final Runnable enterCheck = this::onEnterCheck;
    private final Runnable shotRescan = () -> scanByScreenshot(true);
    private final Runnable tapConfirm = this::onTapConfirm;
    private boolean dialogSeen;
    private String lastTapKey;
    private final Runnable listBackFallback = () -> checkChatThenBack("fallback");
    private final Runnable listBackVerify = this::verifyBackToList;
    private String currentWindow = "";
    private boolean openFinished;
    private boolean receiveActive;
    private long receiveShownAt;
    private int watchdogRestarts;
    private HandlerThread shotThread;
    private Handler shotHandler;
    private final Runnable watchdog = this::onWatchdog;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (Config.KEY_FLOAT.equals(key)) {
            updateFloatBall();
        } else if (floatBall != null) {
            floatBall.refresh();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        config = new Config(this);
        config.prefs().registerOnSharedPreferenceChangeListener(prefListener);
        if (shotThread == null) {
            shotThread = new HandlerThread("lfg-shot");
            shotThread.start();
            shotHandler = new Handler(shotThread.getLooper());
        }
        if (chatListDetector == null) {
            try {
                chatListDetector = new ChatListDetector();
            } catch (Throwable t) {
                Log.w(TAG, "list-ocr: init failed", t);
            }
        }
        updateFloatBall();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        release();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        state = State.IDLE;
    }

    private void release() {
        handler.removeCallbacksAndMessages(null);
        if (config != null) config.prefs().unregisterOnSharedPreferenceChangeListener(prefListener);
        if (shotThread != null) {
            shotThread.quitSafely();
            shotThread = null;
            shotHandler = null;
        }
        if (chatListDetector != null) {
            chatListDetector.close();
            chatListDetector = null;
        }
        enteredFromList = false;
        if (floatBall != null) {
            floatBall.hide();
            floatBall = null;
        }
    }

    private void updateFloatBall() {
        if (config.floatBall()) {
            if (floatBall == null) floatBall = new FloatBall(this, config);
            floatBall.show();
        } else if (floatBall != null) {
            floatBall.hide();
            floatBall = null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (config == null || !config.isEnabled() || event == null) return;
        try {
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                    handleNotification(event);
                    break;
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    handleWindowChanged(event.getClassName() == null ? "" : event.getClassName().toString());
                    break;
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                    handleContentChanged();
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "handle event failed", e);
        }
    }

    // ---------------- 通知栏 ----------------

    private void handleNotification(AccessibilityEvent event) {
        if (!config.listenNotification()) return;
        StringBuilder all = new StringBuilder("event=").append(TextUtils.join("", event.getText()));
        Parcelable data = event.getParcelableData();
        Notification notification = data instanceof Notification ? (Notification) data : null;
        if (notification != null) {
            Bundle extras = notification.extras;
            if (extras != null) {
                all.append(" | title=").append(extras.getCharSequence(Notification.EXTRA_TITLE))
                        .append(" | text=").append(extras.getCharSequence(Notification.EXTRA_TEXT))
                        .append(" | big=").append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null) all.append(" | lines=").append(TextUtils.join("/", lines));
            }
            all.append(" | ticker=").append(notification.tickerText);
        } else {
            all.append(" | parcelable=").append(data);
        }
        String text = all.toString();
        Log.d(TAG, "notify: " + text);
        if (!text.contains(WeChatUi.NOTIFY_KEYWORD)) return;
        if (isExcluded(text)) {
            Log.d(TAG, "notify: skip, excluded word");
            return;
        }
        if (config.wakeScreen()) wakeUpScreen();
        fromNotification = true;
        handler.postDelayed(() -> fromNotification = false, 10_000);

        PendingIntent intent = notification == null ? null : notification.contentIntent;
        boolean sent = false;
        if (intent != null) {
            try {
                intent.send();
                sent = true;
                Log.d(TAG, "notify: contentIntent sent");
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "notify: contentIntent canceled", e);
            } catch (Exception e) {
                Log.w(TAG, "notify: contentIntent send failed", e);
            }
        } else {
            Log.d(TAG, "notify: contentIntent is null");
        }
        // 跳转失败时直接拉起微信，再由界面扫描接手
        if (!sent) {
            boolean ok = AccessibilityUtils.openWeChat(this);
            Log.d(TAG, "notify: fallback open WeChat -> " + ok);
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeUpScreen() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isInteractive()) return;
        PowerManager.WakeLock lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "lfg:wake");
        lock.acquire(10_000);
    }

    // ---------------- 窗口切换 ----------------

    private void handleWindowChanged(String cls) {
        Log.d(TAG, "window: " + cls + ", state=" + state);
        currentWindow = cls;
        boolean lucky = cls.contains(WeChatUi.CLS_LUCKY);
        if (lucky || cls.contains("dialog")) dialogSeen = true;
        if (lucky && cls.contains(WeChatUi.CLS_RECEIVE)) {
            receiveActive = true;
            receiveShownAt = SystemClock.uptimeMillis();
            watchdogRestarts = 0;
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, WATCHDOG_INTERVAL);
            startOpening();
        } else if (lucky && cls.contains(WeChatUi.CLS_DETAIL) && !cls.contains(WeChatUi.CLS_BEFORE_DETAIL)) {
            receiveActive = false;
            onDetailShown();
        } else if (lucky) {
            // BeforeDetailUI 等过渡页，说明已拆开，停止后续点击
            if (cls.contains(WeChatUi.CLS_BEFORE_DETAIL)) {
                openFinished = true;
                receiveActive = false;
            }
        } else {
            if (pendingListBack && state != State.OPENING) {
                handler.postDelayed(() -> checkChatThenBack("window"), 400);
            }
            // 弹窗出现后短时间内的背景切换是微信自身行为，超过阈值才认为弹窗已关闭
            if (receiveActive && SystemClock.uptimeMillis() - receiveShownAt > RECEIVE_CLOSE_GRACE) {
                Log.d(TAG, "window: receive dialog closed");
                receiveActive = false;
                openFinished = true;
            }
            if (state == State.OPENING) {
                // 拆红包弹窗是半透明窗口，背后 LauncherUI 等仍会上报切换，忽略
                Log.d(TAG, "window: ignored while opening");
            } else {
                scanWindow(true);
                if (cls.contains(WeChatUi.CLS_LAUNCHER) && state == State.IDLE) {
                    // 切换动画期间截图可能识别不到会话列表，延时补扫
                    handler.removeCallbacks(listRescan);
                    for (long d : LIST_RESCAN_DELAYS) handler.postDelayed(listRescan, d);
                }
            }
        }
    }

    private void onWatchdog() {
        if (!receiveActive) return;
        if (state != State.OPENING) {
            if (watchdogRestarts >= WATCHDOG_MAX_RESTARTS) {
                Log.d(TAG, "watchdog: give up after " + watchdogRestarts + " restarts");
                return;
            }
            watchdogRestarts++;
            Log.d(TAG, "watchdog: receive dialog still shown, restart opening #" + watchdogRestarts + " state=" + state);
            startOpening();
        }
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL);
    }

    private void handleContentChanged() {
        if (state == State.OPENING) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastScan < SCAN_INTERVAL) return;
        lastScan = now;
        scanWindow(false);
    }

    // ---------------- 聊天/会话列表扫描 ----------------

    private void scanWindow(boolean force) {
        // 等待弹窗、拆红包或返回流程中不再扫描，避免重复点击
        if (state != State.IDLE || pendingListBack) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (force) Log.d(TAG, "scan: root is null");
            return;
        }
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(WeChatUi.PACKET_TEXT);
        if (nodes == null || nodes.isEmpty()) {
            if (force) Log.d(TAG, "scan: no '" + WeChatUi.PACKET_TEXT + "' node, root=" + root.getClassName());
            // 会话列表界面被微信清空，改用截图文字识别
            if (root.getClassName() == null && currentWindow.contains(WeChatUi.CLS_LAUNCHER)) {
                scanChatListByOcr(false);
            }
            return;
        }
        // 能搜到“微信红包”说明已在聊天页
        if (enteredFromList) enterConfirmed = true;
        pruneClicked();

        // 从下往上，优先最新的红包
        int blankNodes = 0;
        for (int i = nodes.size() - 1; i >= 0; i--) {
            AccessibilityNodeInfo node = nodes.get(i);
            if (node.getClassName() == null) {
                blankNodes++;
                if (VERBOSE) Log.d(TAG, "pkt: skip, blank node " + describe(node));
                continue;
            }
            CharSequence t = node.getText();
            String text = t == null ? "" : t.toString();
            if (text.contains(WeChatUi.NOTIFY_KEYWORD)) {
                if (tryOpenConversation(node, text)) return;
            } else if (tryClickPacket(node)) {
                return;
            }
        }
        Log.d(TAG, "scan: found " + nodes.size() + " packet nodes, blank=" + blankNodes + ", force=" + force);
        // 微信返回空节点时改用截图识别
        if (blankNodes > 0) scanByScreenshot(false);
    }

    private void scanByScreenshot(boolean rescan) {
        if (state != State.IDLE || pendingListBack) return;
        long now = SystemClock.uptimeMillis();
        if (!rescan && now - lastShotScan < SHOT_SCAN_INTERVAL) return;
        lastShotScan = now;
        capture(bmp -> {
            PacketShot s = new PacketShot();
            s.packets = ScreenDetector.findPackets(bmp);
            s.width = bmp.getWidth();
            s.height = bmp.getHeight();
            return s;
        }, s -> {
            if (s == null || state != State.IDLE || pendingListBack) return;
            List<Rect> packets = s.packets;
            int width = s.width;
            Log.d(TAG, "shot-scan: " + s.width + "x" + s.height + " packets=" + packets + (rescan ? " (rescan)" : ""));
            pruneClicked();
            boolean partial = false;
            for (Rect r : packets) {
                if (!config.grabSelf() && r.centerX() > width / 2) {
                    Log.d(TAG, "shot-scan: skip self " + r.toShortString());
                    continue;
                }
                if (r.height() < s.height * PACKET_MIN_HEIGHT_RATIO) {
                    Log.d(TAG, "shot-scan: skip partial " + r.toShortString());
                    partial = true;
                    continue;
                }
                String key = "shot@" + (r.top / 40);
                if (clicked.containsKey(key)) {
                    Log.d(TAG, "shot-scan: skip cooldown " + r.toShortString());
                    continue;
                }
                clicked.put(key, SystemClock.uptimeMillis() + CLICK_COOLDOWN);
                Log.d(TAG, "shot-scan: tap packet " + r.toShortString());
                tapPacket(key, r);
                return;
            }
            handler.removeCallbacks(shotRescan);
            if (partial) {
                // 半露红包等完全滑入后再识别
                handler.postDelayed(shotRescan, PARTIAL_RESCAN_DELAY);
            } else if (!rescan && packets.isEmpty()) {
                // 新消息可能仍在滑入动画中，补扫几次
                for (long d : SHOT_RESCAN_DELAYS) handler.postDelayed(shotRescan, d);
            }
        });
    }

    private void tapPacket(String key, Rect r) {
        handler.removeCallbacks(shotRescan);
        lastTapKey = key;
        dialogSeen = false;
        tap(r.centerX(), r.centerY());
        setState(State.WAIT_DIALOG);
        handler.removeCallbacks(tapConfirm);
        handler.postDelayed(tapConfirm, TAP_CONFIRM_TIMEOUT);
    }

    // 点击红包后未出现弹窗，立即恢复并重新识别
    private void onTapConfirm() {
        if (state != State.WAIT_DIALOG || dialogSeen) return;
        Log.d(TAG, "shot-scan: no dialog after tap, rescan");
        if (lastTapKey != null) clicked.remove(lastTapKey);
        setState(State.IDLE);
        scanByScreenshot(true);
    }

    private void scanChatListByOcr(boolean force) {
        if (!config.listenChatList() || state != State.IDLE || enteredFromList || chatListDetector == null) return;
        if (!currentWindow.contains(WeChatUi.CLS_LAUNCHER)) return;
        long now = SystemClock.uptimeMillis();
        // 连续判定不在会话列表时逐步拉长间隔，省电
        long interval = Math.min(LIST_SCAN_INTERVAL << Math.min(listMissCount, 2), LIST_SCAN_MAX_INTERVAL);
        if (!force && now - lastListScan < interval) return;
        lastListScan = now;
        ChatListDetector detector = chatListDetector;
        capture(bmp -> {
            try {
                return detector.analyze(bmp);
            } catch (Exception e) {
                Log.w(TAG, "list-ocr: analyze failed", e);
                return null;
            }
        }, res -> {
            if (res == null || state != State.IDLE || enteredFromList) return;
            if (!res.chatList) {
                listMissCount++;
                Log.d(TAG, "list-ocr: not chat list, miss=" + listMissCount);
                return;
            }
            listMissCount = 0;
            Log.d(TAG, "list-ocr: rows=" + res.rows);
            pruneClicked();
            for (ChatListDetector.Row row : res.rows) {
                if (!row.unread) {
                    Log.d(TAG, "list-ocr: skip read " + row.title);
                    continue;
                }
                if (isExcluded(row.text)) {
                    Log.d(TAG, "list-ocr: skip excluded " + row.title);
                    continue;
                }
                // 未读角标已能区分新旧红包，冷却只用于防止连续误点
                String key = "list:" + row.title;
                if (clicked.containsKey(key)) {
                    Log.d(TAG, "list-ocr: skip cooldown " + row.title);
                    continue;
                }
                clicked.put(key, SystemClock.uptimeMillis() + LIST_ENTER_COOLDOWN);
                Log.d(TAG, "list-ocr: enter " + row);
                enteredFromList = true;
                enterConfirmed = false;
                enteredTitle = row.title;
                tap(res.width / 2f, row.line.centerY());
                handler.removeCallbacks(enterCheck);
                handler.postDelayed(enterCheck, LIST_ENTER_CONFIRM);
                handler.removeCallbacks(listReturn);
                handler.postDelayed(listReturn, LIST_RETURN_TIMEOUT);
                return;
            }
        });
    }

    // 点击会话后未进入聊天（列表重排导致点偏），立即重新识别
    private void onEnterCheck() {
        if (!enteredFromList || enterConfirmed || state != State.IDLE || pendingListBack) return;
        Log.d(TAG, "list-ocr: enter not confirmed, rescan list");
        enteredFromList = false;
        handler.removeCallbacks(listReturn);
        if (enteredTitle != null) clicked.remove("list:" + enteredTitle);
        scanChatListByOcr(true);
    }

    // 进入聊天后长时间没有拆红包，确认不在会话列表后再返回
    private void onListReturnTimeout() {
        if (!enteredFromList) return;
        if (state != State.IDLE || receiveActive || pendingListBack) {
            handler.postDelayed(listReturn, 2_000);
            return;
        }
        enteredFromList = false;
        ChatListDetector detector = chatListDetector;
        if (detector == null) return;
        capture(bmp -> {
            try {
                return detector.analyze(bmp);
            } catch (Exception e) {
                return null;
            }
        }, res -> {
            if (state != State.IDLE) return;
            if (res == null || res.chatList) {
                Log.d(TAG, "list-ocr: timeout, already on chat list or unknown, no back");
                return;
            }
            Log.d(TAG, "list-ocr: no packet opened, back to list");
            performGlobalAction(GLOBAL_ACTION_BACK);
        });
    }

    private boolean tryOpenConversation(AccessibilityNodeInfo node, String preview) {
        Log.d(TAG, "conv: preview=" + preview);
        if (!config.listenChatList() && !fromNotification) {
            Log.d(TAG, "conv: skip, chat list listening off");
            return false;
        }
        if (isExcluded(preview)) {
            Log.d(TAG, "conv: skip, excluded word");
            return false;
        }
        AccessibilityNodeInfo item = clickableParent(node);
        if (item == null) {
            Log.d(TAG, "conv: skip, no clickable parent " + describe(node));
            return false;
        }
        String key = "conv:" + collectText(item);
        if (clicked.containsKey(key)) {
            Log.d(TAG, "conv: skip, in cooldown");
            return false;
        }
        clicked.put(key, SystemClock.uptimeMillis() + CHAT_LIST_COOLDOWN);
        boolean ok = item.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.d(TAG, "conv: click " + describe(item) + " -> " + ok);
        return ok;
    }

    private boolean tryClickPacket(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo bubble = clickableParent(node);
        if (bubble == null) {
            if (VERBOSE) Log.d(TAG, "pkt: skip, no clickable parent " + describe(node));
            return false;
        }
        String all = collectText(bubble);
        Log.d(TAG, "pkt: candidate " + describe(bubble) + " text=" + all);
        if (containsAny(all, WeChatUi.RECEIVED_TEXTS)) {
            Log.d(TAG, "pkt: skip, already received/expired");
            return false;
        }
        if (isExcluded(all)) {
            Log.d(TAG, "pkt: skip, excluded word");
            return false;
        }
        if (!config.grabSelf() && isSelfBubble(bubble)) {
            Log.d(TAG, "pkt: skip, self bubble");
            return false;
        }

        Rect r = new Rect();
        bubble.getBoundsInScreen(r);
        String key = "pkt:" + all + "@" + r.top;
        if (clicked.containsKey(key)) {
            Log.d(TAG, "pkt: skip, in cooldown");
            return false;
        }
        clicked.put(key, SystemClock.uptimeMillis() + CLICK_COOLDOWN);

        boolean ok = bubble.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.d(TAG, "pkt: click -> " + ok);
        if (ok) {
            setState(State.WAIT_DIALOG);
            return true;
        }
        return false;
    }

    private boolean isSelfBubble(AccessibilityNodeInfo bubble) {
        Rect r = new Rect();
        bubble.getBoundsInScreen(r);
        return r.centerX() > getResources().getDisplayMetrics().widthPixels / 2;
    }

    private void pruneClicked() {
        long now = SystemClock.uptimeMillis();
        Iterator<Map.Entry<String, Long>> it = clicked.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) it.remove();
        }
    }

    // ---------------- 拆红包 ----------------

    private void startOpening() {
        openFinished = false;
        setState(State.OPENING);
        handler.postDelayed(() -> tryOpen(0), Math.max(OPEN_FIRST_DELAY, config.openDelay()));
    }

    private void tryOpen(int attempt) {
        if (state != State.OPENING || !isReceiveWindow()) {
            Log.d(TAG, "open#" + attempt + ": abort, state=" + state + " window=" + currentWindow);
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "open#" + attempt + ": root is null");
        } else {
            if (attempt == 0 && VERBOSE) dumpTree(root);
            String all = collectText(root);
            Log.d(TAG, "open#" + attempt + ": root=" + root.getClassName() + " text=" + all);
            if (containsAny(all, WeChatUi.EMPTY_TEXTS)) {
                Log.d(TAG, "open#" + attempt + ": packet empty/expired");
                setState(State.IDLE);
                handler.postDelayed(this::doAfterAction, 600);
                return;
            }
            AccessibilityNodeInfo btn = findOpenButton(root);
            if (btn == null) {
                Log.d(TAG, "open#" + attempt + ": open button not found");
            } else {
                boolean ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "open#" + attempt + ": click " + describe(btn) + " -> " + ok);
                if (ok) {
                    setState(State.OPENING);
                    return;
                }
            }
        }
        openByScreenshot(attempt);
    }

    private boolean isReceiveWindow() {
        return !openFinished;
    }

    private void openByScreenshot(int attempt) {
        // 拆红包时不受截屏间隔限制，被限时直接走固定位置点击
        capture(bmp -> {
            OpenShot s = new OpenShot();
            s.button = ScreenDetector.findOpenButton(bmp);
            if (s.button == null) s.goldAreas = ScreenDetector.components(bmp, 1);
            return s;
        }, s -> {
            if (state != State.OPENING || !isReceiveWindow()) {
                Log.d(TAG, "open#" + attempt + ": shot callback abort, state=" + state + " finished=" + openFinished);
                return;
            }
            Rect btn = s == null ? null : s.button;
            if (s != null && btn == null) {
                Log.d(TAG, "open#" + attempt + ": shot gold areas=" + s.goldAreas);
            }
            if (btn != null) {
                Log.d(TAG, "open#" + attempt + ": shot found button " + btn.toShortString());
                tap(btn.centerX(), btn.centerY());
                // “開”按钮点中后有约 1.4 秒旋转动画，等动画结束再重试
                if (attempt + 1 < MAX_OPEN_ATTEMPTS) {
                    handler.postDelayed(() -> tryOpen(attempt + 1), OPEN_HIT_WAIT);
                }
                return;
            } else {
                Point size = screenSize();
                float y = size.y * OPEN_Y_RATIOS[attempt % OPEN_Y_RATIOS.length];
                Log.d(TAG, "open#" + attempt + ": fallback tap ratio=" + OPEN_Y_RATIOS[attempt % OPEN_Y_RATIOS.length]);
                tap(size.x / 2f, y);
            }
            if (attempt + 1 < MAX_OPEN_ATTEMPTS) {
                handler.postDelayed(() -> tryOpen(attempt + 1), OPEN_RETRY_INTERVAL);
            }
        });
    }

    private Point screenSize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new Point(b.width(), b.height());
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new Point(dm.widthPixels, dm.heightPixels);
    }

    // Android 11+ 截屏，解码与识别在后台线程执行，结果回到主线程；不可用或失败时结果为 null
    private <R> void capture(Function<Bitmap, R> analyzer, Consumer<R> onResult) {
        long now = SystemClock.uptimeMillis();
        Handler bg = shotHandler;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || bg == null || now - lastShot < SHOT_MIN_INTERVAL) {
            onResult.accept(null);
            return;
        }
        lastShot = now;
        Executor executor = bg::post;
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                R r = null;
                HardwareBuffer hb = result.getHardwareBuffer();
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    Bitmap sw = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                    if (hw != null) hw.recycle();
                    if (sw != null) {
                        r = analyzer.apply(sw);
                        sw.recycle();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "screenshot analyze failed", t);
                } finally {
                    hb.close();
                }
                R out = r;
                handler.post(() -> onResult.accept(out));
            }

            @Override
            public void onFailure(int errorCode) {
                Log.d(TAG, "screenshot failed: " + errorCode);
                handler.post(() -> onResult.accept(null));
            }
        });
    }

    private static final class PacketShot {
        List<Rect> packets;
        int width;
        int height;
    }

    private static final class OpenShot {
        Rect button;
        List<Rect> goldAreas;
    }

    private AccessibilityNodeInfo findOpenButton(AccessibilityNodeInfo root) {
        for (String t : WeChatUi.OPEN_TEXTS) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(t);
            if (list == null) continue;
            for (AccessibilityNodeInfo n : list) {
                AccessibilityNodeInfo c = clickableParent(n);
                if (c != null) return c;
            }
        }
        return findButton(root, 0);
    }

    // 新版微信“開”按钮通常是无文字的 Button
    private AccessibilityNodeInfo findButton(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) return null;
        CharSequence cls = node.getClassName();
        if (node.isClickable() && cls != null && cls.toString().endsWith("Button")) {
            CharSequence desc = node.getContentDescription();
            String d = desc == null ? "" : desc.toString();
            if (!d.contains("关闭") && !d.contains("返回")) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findButton(node.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private void tap(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Point size = screenSize();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();
        boolean sent = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                Log.d(TAG, "gesture: completed");
            }

            @Override
            public void onCancelled(GestureDescription g) {
                Log.d(TAG, "gesture: cancelled");
            }
        }, null);
        Log.d(TAG, "gesture: tap (" + x + "," + y + ") screen=" + size.x + "x" + size.y
                + " dispatched=" + sent);
    }

    // ---------------- 详情页 ----------------

    private void onDetailShown() {
        boolean byUs = state != State.IDLE;
        Log.d(TAG, "detail: shown, byUs=" + byUs);
        setState(State.IDLE);
        handler.removeCallbacks(resetState);
        if (!byUs) return;
        handler.postDelayed(() -> {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            config.addRecord(root == null ? 0 : findAmountCent(root, 0));
            if (config.vibrate()) vibrate();
            doAfterAction();
        }, 500);
    }

    private long findAmountCent(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 25) return 0;
        CharSequence t = node.getText();
        if (t != null) {
            Matcher m = AMOUNT.matcher(t.toString().trim());
            if (m.matches()) {
                try {
                    return Long.parseLong(m.group(1)) * 100 + Long.parseLong(m.group(2));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            long v = findAmountCent(node.getChild(i), depth + 1);
            if (v > 0) return v;
        }
        return 0;
    }

    // 回到聊天页后先检查是否还有未领红包，没有再返回会话列表
    private void checkChatThenBack(String source) {
        if (!pendingListBack) return;
        pendingListBack = false;
        handler.removeCallbacks(listBackFallback);
        capture(bmp -> {
            PacketShot s = new PacketShot();
            s.packets = ScreenDetector.findPackets(bmp);
            s.width = bmp.getWidth();
            s.height = bmp.getHeight();
            return s;
        }, s -> {
            if (state != State.IDLE) return;
            if (s != null) {
                pruneClicked();
                for (Rect r : s.packets) {
                    if (!config.grabSelf() && r.centerX() > s.width / 2) continue;
                    if (r.height() < s.height * PACKET_MIN_HEIGHT_RATIO) continue;
                    String key = "shot@" + (r.top / 40);
                    if (clicked.containsKey(key)) continue;
                    clicked.put(key, SystemClock.uptimeMillis() + CLICK_COOLDOWN);
                    Log.d(TAG, "after: more packet in chat " + r.toShortString() + " by " + source);
                    tapPacket(key, r);
                    handler.removeCallbacks(listReturn);
                    handler.postDelayed(listReturn, LIST_RETURN_TIMEOUT);
                    return;
                }
            }
            enteredFromList = false;
            handler.removeCallbacks(listReturn);
            Log.d(TAG, "after: back to chat list (2/2) by " + source);
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.removeCallbacks(listBackVerify);
            handler.postDelayed(listBackVerify, 1_500);
        });
    }

    // 校验是否已回到会话列表，未回到则再补一次返回
    private void verifyBackToList() {
        if (state != State.IDLE || chatListDetector == null || !currentWindow.contains(WeChatUi.CLS_LAUNCHER)) return;
        ChatListDetector detector = chatListDetector;
        capture(bmp -> {
            try {
                return detector.analyze(bmp);
            } catch (Exception e) {
                return null;
            }
        }, res -> {
            if (res == null || state != State.IDLE) return;
            if (res.chatList) {
                Log.d(TAG, "after: verified on chat list");
            } else {
                Log.d(TAG, "after: not on chat list, back again");
                performGlobalAction(GLOBAL_ACTION_BACK);
            }
        });
    }

    private void doAfterAction() {
        if (enteredFromList) {
            // 第一次返回到聊天页，等聊天页出现后检查剩余红包再决定是否返回列表
            handler.removeCallbacks(listReturn);
            Log.d(TAG, "after: back to chat list (1/2)");
            pendingListBack = true;
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(listBackFallback, 1_200);
            return;
        }
        int action = config.afterAction();
        if (action == Config.AFTER_STAY) return;
        performGlobalAction(GLOBAL_ACTION_BACK);
        if (action == Config.AFTER_HOME) {
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 500);
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(200);
        }
    }

    // ---------------- 工具 ----------------

    private void setState(State s) {
        state = s;
        handler.removeCallbacks(resetState);
        if (s != State.IDLE) handler.postDelayed(resetState, STATE_TIMEOUT);
    }

    private boolean isExcluded(String text) {
        for (String w : config.excludeWords()) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo clickableParent(AccessibilityNodeInfo node) {
        int depth = 0;
        while (node != null && depth++ < 8) {
            if (node.isClickable()) return node;
            node = node.getParent();
        }
        return null;
    }

    private static String describe(AccessibilityNodeInfo n) {
        if (n == null) return "null";
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return n.getClassName() + "{id=" + n.getViewIdResourceName() + ", text=" + n.getText()
                + ", desc=" + n.getContentDescription() + ", click=" + n.isClickable()
                + ", visible=" + n.isVisibleToUser() + ", bounds=" + r.toShortString() + "}";
    }

    private static void dumpTree(AccessibilityNodeInfo root) {
        Log.d(TAG, "---- dump tree begin ----");
        dumpTree(root, 0);
        Log.d(TAG, "---- dump tree end ----");
    }

    private static void dumpTree(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 30) return;
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");
        Log.d(TAG, "tree " + indent + describe(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpTree(node.getChild(i), depth + 1);
        }
    }

    private static String collectText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb, 0);
        return sb.toString();
    }

    private static void collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 25) return;
        if (node.getText() != null) sb.append(node.getText()).append('|');
        if (node.getContentDescription() != null) sb.append(node.getContentDescription()).append('|');
        for (int i = 0; i < node.getChildCount(); i++) {
            collectText(node.getChild(i), sb, depth + 1);
        }
    }
}
