package com.xros.wechatredmoney;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity {

    private Config config;
    private TextView tvStatus, tvCount, tvAmount, tvToday, tvDelay;
    private MaterialButton btnOpenSettings;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        config = new Config(this);

        tvStatus = findViewById(R.id.tv_status);
        tvCount = findViewById(R.id.tv_count);
        tvAmount = findViewById(R.id.tv_amount);
        tvToday = findViewById(R.id.tv_today);
        tvDelay = findViewById(R.id.tv_delay);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        ((TextView) findViewById(R.id.tv_guide)).setText(BrandUtils.getGuide());

        btnOpenSettings.setOnClickListener(v -> AccessibilityUtils.openAccessibilitySettings(this));
        findViewById(R.id.btn_open_wechat).setOnClickListener(v -> {
            if (!AccessibilityUtils.openWeChat(this)) {
                Toast.makeText(this, "未安装微信", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_reset).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("确定清空所有统计数据？")
                .setPositiveButton(android.R.string.ok, (d, w) -> config.resetStats())
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        bindSwitch(R.id.sw_enabled, Config.KEY_ENABLED, config.isEnabled());
        bindSwitch(R.id.sw_notification, Config.KEY_NOTIFICATION, config.listenNotification());
        bindSwitch(R.id.sw_chat_list, Config.KEY_CHAT_LIST, config.listenChatList());
        bindSwitch(R.id.sw_self, Config.KEY_SELF, config.grabSelf());
        bindSwitch(R.id.sw_vibrate, Config.KEY_VIBRATE, config.vibrate());
        bindSwitch(R.id.sw_wake, Config.KEY_WAKE, config.wakeScreen());
        bindSwitch(R.id.sw_float, Config.KEY_FLOAT, config.floatBall());

        Slider slider = findViewById(R.id.slider_delay);
        slider.setValue(Math.min(3000, Math.max(0, config.openDelay() / 100 * 100)));
        slider.addOnChangeListener((s, value, fromUser) -> {
            if (fromUser) config.putInt(Config.KEY_DELAY, (int) value);
        });

        MaterialButtonToggleGroup group = findViewById(R.id.group_after);
        int[] ids = {R.id.btn_after_stay, R.id.btn_after_back, R.id.btn_after_home};
        group.check(ids[Math.min(2, Math.max(0, config.afterAction()))]);
        group.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == id) config.putInt(Config.KEY_AFTER, i);
            }
        });

        TextInputEditText etExclude = findViewById(R.id.et_exclude);
        etExclude.setText(config.excludeWordsRaw());
        etExclude.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                config.putString(Config.KEY_EXCLUDE, s.toString());
            }
        });
    }

    private void bindSwitch(int id, String key, boolean value) {
        MaterialSwitch sw = findViewById(id);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, checked) -> config.putBoolean(key, checked));
    }

    @Override
    protected void onResume() {
        super.onResume();
        config.prefs().registerOnSharedPreferenceChangeListener(prefListener);
        ((MaterialSwitch) findViewById(R.id.sw_enabled)).setChecked(config.isEnabled());
        refresh();
    }

    @Override
    protected void onPause() {
        config.prefs().unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onPause();
    }

    private void refresh() {
        boolean serviceOn = AccessibilityUtils.isServiceEnabled(this);
        int color;
        if (!serviceOn) {
            tvStatus.setText(R.string.status_off);
            color = R.color.status_bad;
        } else if (!config.isEnabled()) {
            tvStatus.setText(R.string.status_paused);
            color = R.color.status_warn;
        } else {
            tvStatus.setText(R.string.status_on);
            color = R.color.status_ok;
        }
        tvStatus.setTextColor(ContextCompat.getColor(this, color));
        btnOpenSettings.setText(serviceOn ? "无障碍设置" : getString(R.string.btn_open_settings));

        tvCount.setText(String.valueOf(config.totalCount()));
        tvAmount.setText(Config.formatCent(config.totalAmountCent()));
        tvToday.setText(getString(R.string.stats_today) + "：" + config.todayCount() + " 个 / "
                + Config.formatCent(config.todayAmountCent()) + " 元");
        tvDelay.setText(getString(R.string.set_delay, config.openDelay()));
    }
}
