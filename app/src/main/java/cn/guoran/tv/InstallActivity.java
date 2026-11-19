package cn.guoran.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

/** 手机只发起安装请求，是否安装始终由盒子系统安装器确认。 */
public final class InstallActivity extends Activity {
    private String uploadId;
    private boolean installerStarted;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        uploadId = getIntent().getStringExtra("uploadId");
        if (state != null) installerStarted = state.getBoolean("installerStarted");
    }

    @Override public void onResume() {
        super.onResume();
        if (!installerStarted) render("");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void render(String error) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL); column.setGravity(Gravity.CENTER);
        column.setPadding(dp(50), dp(25), dp(50), dp(25)); column.setBackgroundColor(Color.rgb(237, 242, 250));
        setContentView(column);
        File apk = validApk();
        PackageInfo info = apk == null ? null : getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        TextView title = text("果然 TV · 安装应用", 30, Color.rgb(45, 64, 82));
        column.addView(title);
        String label = info == null ? "安装包已失效" : info.packageName + "  ·  " + info.versionName;
        column.addView(text(label, 20, Color.rgb(83, 101, 126)));
        column.addView(text(error.isEmpty() ? "手机上传的安装包需要在电视上确认。" : error, 18, Color.rgb(83, 101, 126)));
        if (info == null) { button(column, "返回", new Runnable() { public void run() { finish(); } }); return; }
        if (!unknownSourcesAllowed()) {
            column.addView(text("盒子当前未允许未知来源应用，请先在系统设置中允许，再返回这里。", 19, Color.rgb(110, 91, 63)));
            button(column, "打开系统安全设置", new Runnable() { public void run() {
                try {
                    Intent settings = Build.VERSION.SDK_INT >= 26
                            ? new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))
                            : new Intent(Settings.ACTION_SECURITY_SETTINGS);
                    startActivity(settings);
                } catch (ActivityNotFoundException e) { render("本盒子未提供标准安全设置入口，请在盒子设置中允许安装。"); }
            } });
        } else {
            button(column, "继续到系统安装器", new Runnable() { public void run() { startInstaller(); } });
        }
        button(column, "取消并返回果然 TV", new Runnable() { public void run() { finish(); } });
    }

    private File validApk() {
        if (uploadId == null || !uploadId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return null;
        File apk = new File(new File(getFilesDir(), "uploads"), uploadId + ".apk");
        return apk.isFile() ? apk : null;
    }

    private boolean unknownSourcesAllowed() {
        if (Build.VERSION.SDK_INT >= 26) return getPackageManager().canRequestPackageInstalls();
        return Settings.Global.getInt(getContentResolver(), "install_non_market_apps", 0) == 1;
    }

    private void startInstaller() {
        if (validApk() == null) { render("安装包不存在，请重新上传。"); return; }
        Uri uri = Uri.parse("content://cn.guoran.tv.files/apk/" + uploadId);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(uri); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("APK", uri));
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try { installerStarted = true; startActivityForResult(intent, 1); }
        catch (Exception e) { installerStarted = false; render("无法打开系统安装器，请检查盒子安装权限。"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1) { installerStarted = false; finish(); }
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("installerStarted", installerStarted); super.onSaveInstanceState(state);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER); view.setPadding(0, dp(10), 0, dp(10)); return view;
    }

    private void button(LinearLayout parent, String label, final Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextSize(20);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(380), dp(65)); params.topMargin = dp(12);
        parent.addView(button, params); button.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { action.run(); } });
    }
}
