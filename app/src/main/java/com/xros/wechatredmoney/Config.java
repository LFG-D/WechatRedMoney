package com.xros.wechatredmoney;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class Config {

    public static final String PREF_NAME = "red_money_config";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_NOTIFICATION = "listen_notification";
    public static final String KEY_CHAT_LIST = "listen_chat_list";
    public static final String KEY_SELF = "grab_self";
    public static final String KEY_VIBRATE = "vibrate";
    public static final String KEY_FLOAT = "float_ball";
    public static final String KEY_WAKE = "wake_screen";
    public static final String KEY_DELAY = "open_delay";
    public static final String KEY_AFTER = "after_action";
    public static final String KEY_EXCLUDE = "exclude_words";
    public static final String KEY_COUNT = "stat_count";
    public static final String KEY_AMOUNT = "stat_amount_cent";
    public static final String KEY_TODAY_DATE = "stat_today_date";
    public static final String KEY_TODAY_COUNT = "stat_today_count";
    public static final String KEY_TODAY_AMOUNT = "stat_today_amount_cent";

    public static final int AFTER_STAY = 0;
    public static final int AFTER_BACK = 1;
    public static final int AFTER_HOME = 2;

    private final SharedPreferences sp;

    public Config(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences prefs() {
        return sp;
    }

    public boolean isEnabled() { return sp.getBoolean(KEY_ENABLED, true); }
    public boolean listenNotification() { return sp.getBoolean(KEY_NOTIFICATION, true); }
    public boolean listenChatList() { return sp.getBoolean(KEY_CHAT_LIST, true); }
    public boolean grabSelf() { return sp.getBoolean(KEY_SELF, false); }
    public boolean vibrate() { return sp.getBoolean(KEY_VIBRATE, true); }
    public boolean floatBall() { return sp.getBoolean(KEY_FLOAT, false); }
    public boolean wakeScreen() { return sp.getBoolean(KEY_WAKE, true); }
    public int openDelay() { return sp.getInt(KEY_DELAY, 0); }
    public int afterAction() { return sp.getInt(KEY_AFTER, AFTER_BACK); }
    public String excludeWordsRaw() { return sp.getString(KEY_EXCLUDE, ""); }

    public List<String> excludeWords() {
        List<String> list = new ArrayList<>();
        for (String w : excludeWordsRaw().split("[,，\\s]+")) {
            if (!w.trim().isEmpty()) list.add(w.trim());
        }
        return list;
    }

    public void putBoolean(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }
    public void putInt(String key, int value) { sp.edit().putInt(key, value).apply(); }
    public void putString(String key, String value) { sp.edit().putString(key, value).apply(); }

    public int totalCount() { return sp.getInt(KEY_COUNT, 0); }
    public long totalAmountCent() { return sp.getLong(KEY_AMOUNT, 0); }

    public int todayCount() {
        return today().equals(sp.getString(KEY_TODAY_DATE, "")) ? sp.getInt(KEY_TODAY_COUNT, 0) : 0;
    }

    public long todayAmountCent() {
        return today().equals(sp.getString(KEY_TODAY_DATE, "")) ? sp.getLong(KEY_TODAY_AMOUNT, 0) : 0;
    }

    public void addRecord(long amountCent) {
        String today = today();
        boolean sameDay = today.equals(sp.getString(KEY_TODAY_DATE, ""));
        sp.edit()
                .putInt(KEY_COUNT, totalCount() + 1)
                .putLong(KEY_AMOUNT, totalAmountCent() + amountCent)
                .putString(KEY_TODAY_DATE, today)
                .putInt(KEY_TODAY_COUNT, (sameDay ? sp.getInt(KEY_TODAY_COUNT, 0) : 0) + 1)
                .putLong(KEY_TODAY_AMOUNT, (sameDay ? sp.getLong(KEY_TODAY_AMOUNT, 0) : 0) + amountCent)
                .apply();
    }

    public void resetStats() {
        sp.edit().remove(KEY_COUNT).remove(KEY_AMOUNT).remove(KEY_TODAY_DATE)
                .remove(KEY_TODAY_COUNT).remove(KEY_TODAY_AMOUNT).apply();
    }

    public static String formatCent(long cent) {
        return String.format(Locale.getDefault(), "%.2f", cent / 100.0);
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
    }
}
