package com.xros.wechatredmoney;

import android.graphics.Bitmap;
import android.graphics.Rect;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;

/** 基于截图颜色识别红包气泡与“開”按钮，用于微信屏蔽节点信息时兜底。 */
public final class ScreenDetector {

    private static final int STEP = 4;

    private ScreenDetector() {
    }

    // 未领取红包气泡：橙色约 #FA9D3B；已领取的会变淡，不会命中
    static boolean isPacketOrange(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r >= 225 && g >= 125 && g <= 190 && b <= 120 && r - b >= 130;
    }

    // “開”按钮：金色约 #EBCD9A
    static boolean isOpenGold(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r >= 200 && g >= 160 && g <= 225 && b >= 90 && b <= 185 && r - b >= 45 && r >= g;
    }

    /** 返回未领取红包气泡区域（屏幕坐标），按从下到上排序。 */
    public static List<Rect> findPackets(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        List<Rect> result = new ArrayList<>();
        for (Rect r : components(bmp, 0)) {
            if (r.width() >= w * 0.3f && r.height() >= h * 0.04f
                    && r.top > h * 0.08f && r.bottom < h * 0.95f) {
                result.add(r);
            }
        }
        Collections.sort(result, (a, b) -> b.bottom - a.bottom);
        return result;
    }

    /** 返回“開”按钮区域，找不到返回 null。 */
    public static Rect findOpenButton(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        Rect best = null;
        for (Rect r : components(bmp, 1)) {
            float ratio = r.width() / (float) Math.max(1, r.height());
            if (r.width() >= w * 0.12f && r.width() <= w * 0.45f && ratio > 0.7f && ratio < 1.4f
                    && r.centerX() > w * 0.35f && r.centerX() < w * 0.65f
                    && r.centerY() > h * 0.4f && r.centerY() < h * 0.9f) {
                if (best == null || r.width() * r.height() > best.width() * best.height()) best = r;
            }
        }
        return best;
    }

    /** 调试用：输出所有颜色区域。 */
    public static List<Rect> components(Bitmap bmp, int type) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int gw = w / STEP, gh = h / STEP;
        boolean[] mask = new boolean[gw * gh];
        int[] row = new int[w];
        for (int gy = 0; gy < gh; gy++) {
            bmp.getPixels(row, 0, w, 0, gy * STEP, w, 1);
            for (int gx = 0; gx < gw; gx++) {
                int c = row[gx * STEP];
                mask[gy * gw + gx] = type == 0 ? isPacketOrange(c) : isOpenGold(c);
            }
        }
        List<Rect> list = new ArrayList<>();
        boolean[] seen = new boolean[mask.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] || seen[i]) continue;
            int minX = gw, minY = gh, maxX = 0, maxY = 0, count = 0;
            seen[i] = true;
            queue.add(i);
            while (!queue.isEmpty()) {
                int p = queue.poll();
                int x = p % gw, y = p / gw;
                count++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                int[] nb = {p - 1, p + 1, p - gw, p + gw};
                for (int n : nb) {
                    if (n < 0 || n >= mask.length || seen[n] || !mask[n]) continue;
                    if ((n == p - 1 && x == 0) || (n == p + 1 && x == gw - 1)) continue;
                    seen[n] = true;
                    queue.add(n);
                }
            }
            if (count < 30) continue;
            list.add(new Rect(minX * STEP, minY * STEP, (maxX + 1) * STEP, (maxY + 1) * STEP));
        }
        return list;
    }
}
