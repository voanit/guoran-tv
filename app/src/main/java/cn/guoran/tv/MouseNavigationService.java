package cn.guoran.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** 提供鼠标导航及盒子休息遮罩，不读取窗口内容，不注入触摸手势。 */
public final class MouseNavigationService extends AccessibilityService {
    private static MouseNavigationService activeService;
    private static boolean playbackActive;
    private WindowManager windows;
    private View navigation;
    private View restScreen;
    private TextView restMessage;

    public static void enforceWatchRules() {
        if (activeService != null) activeService.updateRestScreen();
    }

    /** 到点回桌面停止后台视频，并用同一无障碍窗口阻止从其他 App 继续使用。 */
    private void updateRestScreen() {
        Store store = Store.get(this);
        String reason = store.blockedReason("");
        if (reason.isEmpty()) {
            if (restScreen != null) { windows.removeView(restScreen); restScreen = null; restMessage = null; }
            if (navigation != null) navigation.setVisibility(playbackActive ? View.GONE : View.VISIBLE);
            return;
        }
        if (windows == null) return;
        if (restScreen == null) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            float unit = getResources().getDisplayMetrics().widthPixels / 1280f;
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER);
            panel.setBackgroundColor(Color.rgb(16, 18, 24)); panel.setPadding(60, 30, 60, 30);
            TextView title = restText("让眼睛休息一会儿", 40 * unit); panel.addView(title);
            restMessage = restText("", 23 * unit);
            LinearLayout.LayoutParams copy = new LinearLayout.LayoutParams(-1, Math.round(120 * unit));
            copy.topMargin = Math.round(18 * unit); panel.addView(restMessage, copy);
            final EditText pin = new EditText(this); pin.setHint("家长 PIN"); pin.setSingleLine(true);
            pin.setTextColor(Color.WHITE); pin.setHintTextColor(Color.LTGRAY);
            pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            pin.setVisibility(View.GONE); pin.setGravity(Gravity.CENTER);
            panel.addView(pin, new LinearLayout.LayoutParams(Math.round(260 * unit), Math.round(50 * unit)));
            Button unlock = new Button(this); unlock.setText("家长临时解锁"); unlock.setAllCaps(false);
            unlock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18 * unit);
            unlock.setOnClickListener(v -> {
                if (pin.getVisibility() != View.VISIBLE) {
                    pin.setVisibility(View.VISIBLE); pin.requestFocus(); unlock.setText("解锁 10 分钟"); return;
                }
                try { store.unlockBox(pin.getText().toString()); pin.setText(""); updateRestScreen(); }
                catch (IllegalArgumentException error) { pin.setError(error.getMessage()); }
            });
            LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(Math.round(260 * unit), Math.round(52 * unit));
            action.topMargin = Math.round(20 * unit); panel.addView(unlock, action);
            TextView hint = restText("也可以在手机家长端调整安排", 16 * unit);
            LinearLayout.LayoutParams hintLayout = new LinearLayout.LayoutParams(-1, -2); hintLayout.topMargin = Math.round(25 * unit); panel.addView(hint, hintLayout);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            try { windows.addView(panel, lp); restScreen = panel; unlock.requestFocus(); }
            catch (RuntimeException error) { Log.w("GuoranTV", "休息页暂未显示：" + error.getClass().getSimpleName()); }
        }
        if (restMessage != null) {
            long remaining = store.watchStatus().optLong("restRemaining");
            String countdown = remaining > 0 ? String.format(java.util.Locale.US, "\n%d:%02d 后可以继续", remaining / 60, remaining % 60) : "";
            restMessage.setText(reason + countdown);
        }
        if (navigation != null) navigation.setVisibility(View.GONE);
    }

    private TextView restText(String text, float size) {
        TextView view = new TextView(this); view.setText(text); view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER); view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        return view;
    }

    /** 自家全屏播放时隐藏悬浮条，外部应用和片单继续保留鼠标返回入口。 */
    public static void setPlaybackActive(boolean active) {
        playbackActive = active;
        if (activeService != null && activeService.navigation != null)
            activeService.navigation.setVisibility(active || activeService.restScreen != null ? View.GONE : View.VISIBLE);
    }

    public static boolean isEnabled(Context context) {
        if (Settings.Secure.getInt(context.getContentResolver(), "accessibility_enabled", 0) != 1) return false;
        String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
        if (enabled == null) return false;
        ComponentName target = new ComponentName(context, MouseNavigationService.class);
        for (String name : enabled.split(":")) if (target.equals(ComponentName.unflattenFromString(name))) return true;
        return false;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;
        showNavigation();
        setPlaybackActive(playbackActive);
    }

    /** 导航条不随前台应用切换隐藏，外部播放器、文件夹和系统设置均可用鼠标离开。 */
    @SuppressWarnings("deprecation")
    private void showNavigation() {
        if (navigation != null) return;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windows == null) return;
        final android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        final float unit = metrics.widthPixels / 1280f;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.parseColor("#8C171A20")); fill.setCornerRadius(6 * unit);
        bar.setBackground(fill);
        ControlIconView back = new ControlIconView(this, "back", "返回上一页，按住可拖动");
        bar.addView(back, new LinearLayout.LayoutParams(Math.round(36 * unit), -1));
        ControlIconView home = new ControlIconView(this, "home", "回到果然 TV 首页，按住可拖动");
        bar.addView(home, new LinearLayout.LayoutParams(Math.round(36 * unit), -1));
        back.setOnClickListener(v -> {
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) Toast.makeText(this, "当前页面暂时无法返回，请点首页", Toast.LENGTH_SHORT).show();
        });
        home.setOnClickListener(v -> {
            // 显式回果然 TV，同时让首页收起家长弹窗并回到首屏。
            Intent intent = new Intent(this, MainActivity.class).setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try { startActivity(intent); }
            catch (RuntimeException error) { performGlobalAction(GLOBAL_ACTION_HOME); }
        });
        int type = Build.VERSION.SDK_INT >= 22 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(72 * unit), Math.round(34 * unit),
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        final SharedPreferences position = getSharedPreferences("mouse-navigation", MODE_PRIVATE);
        // 按家长最新选择迁到右下角；只迁一次，之后继续记住拖动后的位置。
        int inset = Math.round(12 * unit);
        int defaultX = metrics.widthPixels - lp.width - inset, defaultY = metrics.heightPixels - lp.height - inset;
        if (!position.getBoolean("compactIconsApplied", false))
            position.edit().putInt("left", defaultX).putInt("top", defaultY).putBoolean("compactIconsApplied", true).apply();
        lp.x = Math.max(0, Math.min(metrics.widthPixels - lp.width, position.getInt("left", defaultX)));
        lp.y = Math.max(0, Math.min(metrics.heightPixels - lp.height, position.getInt("top", defaultY)));
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        View.OnTouchListener drag = new View.OnTouchListener() {
            private float startX, startY;
            private int initialX, initialY;
            private boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); initialX = lp.x; initialY = lp.y; moved = false;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                    if (moved) {
                        lp.x = Math.max(0, Math.min(metrics.widthPixels - lp.width, initialX + Math.round(dx)));
                        lp.y = Math.max(0, Math.min(metrics.heightPixels - lp.height, initialY + Math.round(dy)));
                        windows.updateViewLayout(bar, lp);
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    if (moved) position.edit().putInt("left", lp.x).putInt("top", lp.y).apply();
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                    return true;
                }
                return false;
            }
        };
        // 不再显示拖动把手，按住任一图标拖动；轻点继续执行返回或首页。
        back.setOnTouchListener(drag); home.setOnTouchListener(drag);
        try { windows.addView(bar, lp); navigation = bar; }
        catch (RuntimeException error) { Log.w("GuoranTV", "鼠标导航未显示：" + error.getClass().getSimpleName()); }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { enforceWatchRules(); }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        if (activeService == this) activeService = null;
        if (restScreen != null) { try { windows.removeView(restScreen); } catch (RuntimeException ignored) { } }
        if (navigation != null) {
            try { windows.removeView(navigation); } catch (RuntimeException ignored) { }
            navigation = null;
        }
        super.onDestroy();
    }
}
