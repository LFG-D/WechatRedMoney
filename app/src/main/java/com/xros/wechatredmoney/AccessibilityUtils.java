package com.xros.wechatredmoney;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

public final class AccessibilityUtils {

    private AccessibilityUtils() {
    }

    public static boolean isServiceEnabled(Context context) {
        ComponentName target = new ComponentName(context, RedMoneyService.class);
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null) {
            List<AccessibilityServiceInfo> list =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : list) {
                ComponentName cn = ComponentName.unflattenFromString(info.getId());
                if (target.equals(cn)) return true;
            }
        }
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (target.equals(ComponentName.unflattenFromString(splitter.next()))) return true;
        }
        return false;
    }

    public static void openAccessibilitySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    public static boolean openWeChat(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(WeChatUi.PACKAGE);
        if (intent == null) return false;
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }
}
