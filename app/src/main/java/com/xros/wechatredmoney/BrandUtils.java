package com.xros.wechatredmoney;

import android.os.Build;

import java.util.Locale;

public final class BrandUtils {

    private BrandUtils() {
    }

    public static String getGuide() {
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        String path;
        String extra;
        if (brand.contains("xiaomi") || brand.contains("redmi")) {
            path = "设置 → 更多设置 → 无障碍 → 已下载的应用 → 钱来";
            extra = "并在 设置 → 应用设置 → 钱来 → 省电策略 中选择“无限制”，开启“自启动”。";
        } else if (brand.contains("huawei") || brand.contains("honor")) {
            path = "设置 → 辅助功能 → 无障碍 → 钱来";
            extra = "并在 设置 → 电池 → 启动管理 中将 钱来 改为手动管理并全部允许。";
        } else if (brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus")) {
            path = "设置 → 其他设置 → 无障碍 → 已下载的服务 → 钱来";
            extra = "并在 设置 → 电池 → 应用耗电管理 中允许 钱来 后台运行。";
        } else if (brand.contains("vivo") || brand.contains("iqoo")) {
            path = "设置 → 快捷与辅助 → 无障碍 → 钱来";
            extra = "并在 i管家 → 应用管理 → 权限管理 中允许 钱来 后台运行和自启动。";
        } else if (brand.contains("samsung")) {
            path = "设置 → 辅助功能 → 已安装的应用 → 钱来";
            extra = "并在 设置 → 电池 → 后台使用限制 中将 钱来 设为“从不休眠”。";
        } else if (brand.contains("meizu")) {
            path = "设置 → 辅助功能 → 无障碍 → 钱来";
            extra = "并在 手机管家 中允许 钱来 后台运行。";
        } else {
            path = "设置 → 无障碍（或辅助功能）→ 已下载的服务 → 钱来";
            extra = "并在电池设置中允许 钱来 后台运行，避免服务被系统关闭。";
        }
        return "当前设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + "\n\n1. 点击上方“去开启”，进入 " + path + "，打开开关。"
                + "\n2. " + extra
                + "\n3. 在微信 设置 → 消息通知 中开启新消息通知，并允许通知显示消息详情。"
                + "\n4. 在最近任务中锁定微信和本应用，避免被一键清理。";
    }
}
