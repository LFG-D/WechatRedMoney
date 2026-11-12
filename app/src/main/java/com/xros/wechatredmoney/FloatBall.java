package com.xros.wechatredmoney;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/** 无障碍服务专属悬浮球，无需悬浮窗权限；单击暂停/恢复，可拖动。 */
public class FloatBall {

    private final Context context;
    private final Config config;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final TextView view;
    private final GradientDrawable bg = new GradientDrawable();
    private boolean shown;

    @SuppressLint("ClickableViewAccessibility")
    public FloatBall(Context context, Config config) {
        this.context = context;
        this.config = config;
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        int size = dp(52);
        view = new TextView(context);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(ContextCompat.getColor(context, R.color.white));
        view.setTextSize(12);
        bg.setShape(GradientDrawable.OVAL);
        view.setBackground(bg);

        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = context.getResources().getDisplayMetrics().widthPixels - size;
        lp.y = context.getResources().getDisplayMetrics().heightPixels / 3;

        int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        view.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                        if (moved) {
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            wm.updateViewLayout(view, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) config.putBoolean(Config.KEY_ENABLED, !config.isEnabled());
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    public void show() {
        refresh();
        if (shown) return;
        try {
            wm.addView(view, lp);
            shown = true;
        } catch (Exception ignored) {
            shown = false;
        }
    }

    public void hide() {
        if (!shown) return;
        try {
            wm.removeView(view);
        } catch (Exception ignored) {
            // view 已被系统移除
        }
        shown = false;
    }

    public void refresh() {
        boolean on = config.isEnabled();
        bg.setColor(ContextCompat.getColor(context, on ? R.color.red_primary : android.R.color.darker_gray));
        view.setText(on ? "抢\n" + config.todayCount() : "暂停");
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
