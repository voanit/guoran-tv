package cn.guoran.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 遥控器和鼠标均可操作的家庭桌面，家长配置片单，时长由整盒子统一管理。 */
public final class MainActivity extends Activity {
    private static final String DRIVE_ROOT_ID = "baidu-root";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private BaiduDrive baidu;
    private final ExecutorService driveWorker = Executors.newSingleThreadExecutor();
    private JSONObject driveCourse;
    private JSONObject driveListing;
    private JSONObject playingCourse;
    private JSONObject playingResource;
    private String drivePath = "";
    private String driveFocusTag = "";
    private String driveError = "";
    private boolean driveLoading;
    private int driveRequest;
    private int driveStart;
    private JSONObject homeDriveListing;
    private String homeDriveRoot = "", homeDriveError = "";
    private boolean homeDriveLoading;
    private int homeDriveStart, homeDriveRequest;
    private long homeDriveLoadedAt;

    private SharedPreferences playback;
    private FrameLayout root;
    private LinearLayout body;
    private TextView progressView;
    private ControlIconView playButton;
    private View playerControls;
    private TextView playerStatus;
    private View playerLoading;
    private SeekBar playerSeek;
    private String playerTitle = "";
    private long playerOpenedAt, stalledAt;
    private boolean playerSeeking;
    private boolean userPaused;
    private boolean externalStream;
    private VideoView video;
    private AlertDialog pairingDialog;
    private AlertDialog parentToolsDialog;
    private AlertDialog systemSettingsDialog;
    private Intent pendingExternalIntent;
    private static final int NAVIGATION_REQUEST = 41;
    private static final int LOCAL_VIDEO_REQUEST = 42;
    private String child = "";
    private String observedActive = "";
    private String page = "home";
    private String focusChild = "";
    private String homeFocusTag = "";
    private boolean resetHomeFocus;
    private ScrollView homeScroll;
    private final List<List<View>> homeFocusRows = new ArrayList<>();
    private String playingId = "";
    private String playingUrl = "";
    private int observedRevision;
    private int background, surface, ink, muted, accent, accentInk;
    private float unit;
    private boolean foreground;
    private boolean buffering;
    private boolean prepared;
    private boolean lastWasPlaying;
    private long lastProbe;
    private long pendingMillis;
    private int lastPosition;
    private int elapsedTicks;
    private View firstFocus;
    private final Runnable hidePlayerControls = () -> {
        if (video != null && prepared && !userPaused && playerControls != null)
            playerControls.setVisibility(View.GONE);
    };

    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (!foreground || store == null) return;
            try {
                probePlayback(false);
                String active = store.activeChild();
                // 手机定位只改变首页焦点，不改变全盒子的使用额度。
                if (!active.equals(observedActive)) {
                    observedActive = active;
                    if (video == null) { focusChild = active; render(); }
                }
                if (video != null && !store.canWatch(child)) {
                    String reason = store.blockedReason(child);
                    leavePlayer();
                    toast(reason);
                }
                if (++elapsedTicks % 2 == 0) {
                    int revision = store.revision();
                    if (revision != observedRevision) {
                        observedRevision = revision;
                        if (video == null) render();
                        else checkCurrentResource();
                    }
                }
                if (video == null && page.equals("home")) refreshHomeDrive(false, homeDriveStart);
            } catch (RuntimeException error) {
                stopVideo();
                showFatal("读取家庭设置失败，请检查盒子存储空间后重新打开。\n已有资源不会被自动清空。");
                return;
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        unit = getResources().getDisplayMetrics().widthPixels / 1280f;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        try {
            store = Store.get(this);
            baidu = BaiduDrive.get(this);
            playback = getSharedPreferences("playback", MODE_PRIVATE);
            observedActive = store.activeChild();
            observedRevision = store.revision();
            // 配对服务与电视界面共用 Store；启动界面不改变系统默认桌面。
            startDeviceService();
            render();
        } catch (RuntimeException error) {
            showFatal("果然 TV 暂时无法启动。\n请保留应用数据，检查盒子可用空间后重试。");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        // 外部播放器返回后撤销该次临时地址，继续留在原目录与选中卡片。
        if (externalStream) { externalStream = false; baidu.closeStreams(); }
        if (store != null) {
            startDeviceService();
            if (video == null) render();
            if (page.equals("home")) refreshHomeDrive(false, homeDriveStart);
            handler.removeCallbacks(pulse);
            handler.post(pulse);
        }
    }

    @Override protected void onPause() {
        foreground = false;
        handler.removeCallbacks(pulse);
        // Home 或外部应用离开时立即停止内置视频，保留断点，不在后台计时。
        if (video != null) {
            stopVideo();
            page = driveCourse == null ? "home" : "drive";
        }
        super.onPause();
    }

    private void startDeviceService() {
        Intent intent = new Intent(this, DeviceService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    /** 平板多窗口或显示尺寸变化后重新计算布局比例，不中断正在播放的视频。 */
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        unit = getResources().getDisplayMetrics().widthPixels / 1280f;
        if (store != null && video == null) render();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopVideo();
        driveRequest++; driveWorker.shutdownNow();
        if (pairingDialog != null) pairingDialog.dismiss();
        if (parentToolsDialog != null) parentToolsDialog.dismiss();
        if (systemSettingsDialog != null) systemSettingsDialog.dismiss();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        stopVideo();
        baidu.closeStreams(); externalStream = false;
        driveRequest++; driveCourse = null; driveListing = null;
        page = "home";
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            focusChild = "";
            resetHomeFocus = true;
            if (homeScroll != null) homeScroll.scrollTo(0, 0);
            if (pairingDialog != null) pairingDialog.dismiss();
            if (parentToolsDialog != null) parentToolsDialog.dismiss();
            if (systemSettingsDialog != null) systemSettingsDialog.dismiss();
        }
        if (store != null) render();
    }

    private int s(float value) { return Math.round(value * unit); }
    private static int c(String value) { return Color.parseColor(value); }

    private void palette() {
        background = c("#101218"); surface = c("#21252E"); ink = c("#F4F5F8");
        muted = c("#939BAA"); accent = c("#FFB65C"); accentInk = c("#20170D");
    }

    private void render() {
        if (video != null) return;
        if ("drive".equals(page) && driveCourse != null) { renderDrive(); return; }
        View current = getCurrentFocus();
        if (current != null && current.getTag() instanceof String && !String.valueOf(current.getTag()).startsWith("player-")
                && !String.valueOf(current.getTag()).startsWith("drive-")) {
            homeFocusTag = (String) current.getTag();
        }
        final boolean goingTop = resetHomeFocus;
        if (resetHomeFocus) { homeFocusTag = ""; resetHomeFocus = false; }
        final int scrollY = homeScroll == null ? 0 : homeScroll.getScrollY();
        homeFocusRows.clear();
        palette(); firstFocus = null;
        root = new FrameLayout(this); root.setBackgroundColor(background);
        body = column();
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        familyHome();
        connectHomeFocus();
        View target = homeFocusTag.isEmpty() ? null : root.findViewWithTag(homeFocusTag);
        final boolean locatingChild = !focusChild.isEmpty();
        if (!focusChild.isEmpty()) {
            View module = root.findViewWithTag("shelf-" + focusChild);
            if (module != null) {
                List<View> controls = module.getFocusables(View.FOCUS_FORWARD);
                if (!controls.isEmpty()) target = controls.get(0);
            }
            focusChild = "";
        }
        if (target == null || !target.isFocusable()) {
            target = firstFocus;
        }
        if (target != null) target.requestFocusFromTouch();
        // 更新资源或从播放器返回时保留纵向位置；手机定位允许系统滚动到目标模块。
        homeScroll.post(() -> {
            if (goingTop) homeScroll.scrollTo(0, 0);
            else if (!locatingChild) homeScroll.scrollTo(0, scrollY);
        });
    }

    /** 视频首页按片单纵向排布；资源固定列数换行，所有内容都能用上下键浏览到。 */
    private void familyHome() {
        LinearLayout top = row(); top.setPadding(s(48), s(14), s(48), s(14));
        top.setBackgroundColor(background); top.setElevation(s(14));
        TextView mark = text("▶", 26, accent, true); top.addView(mark); space(top, 10);
        TextView brand = text(store.displayName(), 27, ink, true); brand.setMaxWidth(s(330)); brand.setSingleLine(true); brand.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(brand); space(top, 25);
        top.addView(text("家庭影院", 17, c("#C8CDD6"), false), new LinearLayout.LayoutParams(0, s(44), 1));
        top.addView(text("上下浏览片单  ·  方向键选内容", 14, muted, false)); space(top, 26);
        List<View> tools = new ArrayList<>();
        // 本地入口始终可见，不能因盒子未及时上报 U 盘或 SD 卡挂载状态而消失。
        Button local = button("本地资源", "usb-resources", false, this::openLocalResources);
        top.addView(local); tools.add(local); space(top, 12);
        Button parent = button("家长管理", "parent", false, this::pairing);
        top.addView(parent); tools.add(parent); homeFocusRows.add(tools);
        body.addView(top, new LinearLayout.LayoutParams(-1, s(76)));
        homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true); homeScroll.setFocusable(false);
        homeScroll.setClipChildren(true); homeScroll.setClipToPadding(true); homeScroll.setVerticalScrollBarEnabled(false);
        homeScroll.setSmoothScrollingEnabled(true);
        body.addView(homeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout feed = column(); feed.setPadding(0, 0, 0, s(42)); homeScroll.addView(feed);
        featuredStory(feed);
        gap(feed, 24);
        childShelf(feed, "family");
        gap(feed, 36);
        recentShelf(feed);
        for (String owner : store.resourceOwners()) if (!owner.equals("family")) { childShelf(feed, owner); gap(feed, 36); }
        homeDriveShelf(feed);
        appShelf(feed);
        gap(feed, 34);
        TextView hint = text("↑↓ 浏览片单     方向键选内容     确定打开     Home 回到顶部", 15, muted, false);
        hint.setPadding(s(48), s(12), s(48), s(12)); feed.addView(hint);
    }

    private void featuredStory(LinearLayout feed) {
        String owner = "family";
        JSONObject chosen = null;
        // 主推只引用已有资源；没有可播放地址时，按钮定位片单，不伪装成可播放影片。
        for (String id : store.resourceOwners()) {
            for (JSONObject item : resourcesFor(id)) {
                if (chosen == null) { chosen = item; owner = id; }
                if (resourceReady(item)) { chosen = item; owner = id; break; }
            }
            if (chosen != null && resourceReady(chosen)) break;
        }
        if (chosen == null) {
            TextView empty = text("好故事，等你来收藏", 44, ink, true);
            empty.setPadding(s(48), s(56), s(48), s(56)); feed.addView(empty); return;
        }
        final String id = owner;
        final JSONObject resource = chosen;
        final boolean ready = resourceReady(resource);
        FrameLayout hero = new FrameLayout(this);
        hero.setBackgroundColor(c("#151C29"));
        String cover = resource.optString("cover");
        PosterView poster = new PosterView(this, cover.equals("frozen") ? "hero-frozen" : cover, true);
        FrameLayout.LayoutParams art = new FrameLayout.LayoutParams(s(870), -1, Gravity.RIGHT);
        hero.addView(poster, art);
        View shade = new View(this);
        GradientDrawable shadeFill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{c("#151C29"), c("#151C29"), c("#65151C29"), c("#00151C29")});
        shade.setBackground(shadeFill); hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, c("#00101218"), background}));
        FrameLayout.LayoutParams fadeLp = new FrameLayout.LayoutParams(-1, s(100), Gravity.BOTTOM);
        hero.addView(fade, fadeLp);
        LinearLayout copy = column();
        TextView eyebrow = text("家庭片单精选  /  " + profileName(id), 14, c("#EBC28B"), true);
        eyebrow.setLetterSpacing(.12f); copy.addView(eyebrow); gap(copy, 15);
        TextView title = text(resource.optString("title"), 48, Color.WHITE, true);
        title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); copy.addView(title); gap(copy, 14);
        copy.addView(text("把喜欢的故事，留给一起看的时光。", 19, c("#C3CBD9"), false)); gap(copy, 25);
        LinearLayout actions = row();
        String mainTitle = !ready ? "查看" + profileName(id) + "片单  ↓"
                : resource.optString("kind").equals("video") ? "立即播放" : "打开课程";
        Button main = button(mainTitle, "hero-main", true, () -> {
            if (ready) { if (activateChild(id)) openResource(resource); }
            else focusShelf(id);
        });
        actions.addView(main); space(actions, 13);
        List<View> row = new ArrayList<>(); row.add(main);
        JSONArray shelves = store.playlists();
        if (shelves.length() > 0) {
            final String other = shelves.optJSONObject(0).optString("id");
            Button next = button(store.playlistName(other), "hero-next", false, () -> focusShelf(other));
            actions.addView(next); row.add(next);
        }
        copy.addView(actions); homeFocusRows.add(row);
        firstFocus = main;
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(s(600), -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        cp.leftMargin = s(48); cp.bottomMargin = s(12); hero.addView(copy, cp);
        feed.addView(hero, new LinearLayout.LayoutParams(-1, s(320)));
    }

    private void focusShelf(String id) {
        View module = root.findViewWithTag("shelf-" + id);
        if (module == null) return;
        List<View> controls = module.getFocusables(View.FOCUS_FORWARD);
        if (!controls.isEmpty()) controls.get(0).requestFocus();
    }

    private void childShelf(LinearLayout feed, final String id) {
        boolean named = !id.equals("family");
        List<JSONObject> list = resourcesFor(id);
        // 共享内容直接呈现海报，不额外放标题；未添加时不占一整块空栏目。
        if (list.isEmpty()) return;
        LinearLayout section = column(); section.setTag("shelf-" + id);
        if (named) {
            LinearLayout heading = row(); heading.setPadding(s(48), 0, s(48), 0);
            heading.addView(text(store.playlistName(id), 25, ink, true), new LinearLayout.LayoutParams(0, s(36), 1));
            heading.addView(text(list.size() + " 项内容", 14, muted, false));
            section.addView(heading, new LinearLayout.LayoutParams(-1, s(40))); gap(section, 8);
        }
        if (list.isEmpty()) {
            TextView empty = text("这里还没有收藏，家长添加的动画和课程会出现在这里。", 19, muted, false);
            empty.setPadding(s(48), s(24), s(48), s(24)); section.addView(empty);
        } else {
            // 固定五列，超出的资源换到下一行，所有内容都能用上下键浏览到。
            final int columns = 5;
            for (int offset = 0; offset < list.size(); offset += columns) {
                LinearLayout cards = row();
                cards.setPadding(s(48), s(7), s(48), s(7));
                List<View> focusRow = new ArrayList<>();
                int end = Math.min(list.size(), offset + columns);
                for (int index = offset; index < end; index++) {
                    View card = resourceCard(id, list.get(index), Color.WHITE);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, s(204), 1);
                    if (index > offset) lp.leftMargin = s(18);
                    cards.addView(card, lp); focusRow.add(card);
                }
                // 不足五张时用不可聚焦的空位保留列宽，避免单张封面被权重拉满整行。
                for (int index = end; index < offset + columns; index++) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, s(204), 1);
                    lp.leftMargin = s(18);
                    cards.addView(new View(this), lp);
                }
                section.addView(cards, new LinearLayout.LayoutParams(-1, s(223)));
                homeFocusRows.add(focusRow);
            }
        }
        feed.addView(section);
    }

    /** 首页只同步授权主目录这一层，不递归扫描课程；进入文件夹才加载下一级。 */
    private void refreshHomeDrive(boolean force, int start) {
        JSONObject status = baidu.status();
        String directory = status.optString("root");
        if (!status.optBoolean("connected")) {
            if (homeDriveListing != null || homeDriveLoading || !homeDriveRoot.isEmpty()) {
                homeDriveRequest++; homeDriveListing = null; homeDriveLoading = false; homeDriveRoot = "";
                homeDriveStart = 0; homeDriveLoadedAt = 0; homeDriveError = "";
                if (video == null && page.equals("home")) render();
            }
            return;
        }
        boolean changedRoot = !directory.equals(homeDriveRoot);
        if (homeDriveLoading && !changedRoot) return;
        if (!force && !changedRoot && SystemClock.elapsedRealtime() - homeDriveLoadedAt < 60000) return;
        if (changedRoot) { start = 0; homeDriveListing = null; }
        final String target = directory;
        final int offset = start, request = ++homeDriveRequest;
        homeDriveRoot = target; homeDriveStart = offset; homeDriveLoading = true; homeDriveError = "";
        if (video == null && page.equals("home")) render();
        driveWorker.execute(() -> {
            JSONObject result = null; String error = "";
            try { result = baidu.list(target, offset); }
            catch (IllegalArgumentException problem) { error = problem.getMessage(); }
            catch (RuntimeException problem) { error = "暂时无法同步网盘，请稍后刷新"; }
            final JSONObject listing = result; final String message = error;
            handler.post(() -> {
                if (request != homeDriveRequest || isFinishing()) return;
                homeDriveLoading = false; homeDriveLoadedAt = SystemClock.elapsedRealtime();
                homeDriveListing = listing; homeDriveError = message;
                if (video == null && page.equals("home")) render();
            });
        });
    }

    private void homeDriveShelf(LinearLayout feed) {
        JSONObject status = baidu.status();
        LinearLayout section = column(); section.setTag("shelf-drive-root");
        LinearLayout heading = row(); heading.setPadding(s(48), 0, s(48), 0);
        heading.addView(text(status.optBoolean("configured") ? status.optString("appName") : "百度网盘", 25, ink, true));
        space(heading, 18);
        heading.addView(text(homeDriveLoading ? "正在同步…" : "网盘目录自动同步", 14, muted, false), new LinearLayout.LayoutParams(0, s(50), 1));
        Button refresh = button("刷新", "root-refresh", false, () -> refreshHomeDrive(true, homeDriveStart));
        refresh.setEnabled(!homeDriveLoading && status.optBoolean("connected"));
        heading.addView(refresh); section.addView(heading);
        if (refresh.isEnabled()) { List<View> controls = new ArrayList<>(); controls.add(refresh); homeFocusRows.add(controls); }
        gap(section, 12);
        if (!status.optBoolean("connected") || !homeDriveError.isEmpty() || homeDriveListing == null) {
            String message = !status.optBoolean("connected") ? "家长连接百度网盘后，这里的文件夹和视频会自动显示。"
                    : !homeDriveError.isEmpty() ? homeDriveError : "正在读取主目录…";
            TextView hint = text(message, 18, muted, false); hint.setPadding(s(48), s(16), s(48), s(20)); section.addView(hint);
        }
        if (homeDriveListing != null && status.optBoolean("connected")) {
            JSONArray source = homeDriveListing.optJSONArray("entries");
            List<JSONObject> entries = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) {
                JSONObject entry = source.optJSONObject(i);
                if (entry.optBoolean("isDir") || entry.optInt("category") == 1) entries.add(entry);
            }
            if (entries.isEmpty()) {
                TextView empty = text("把文件夹或视频放入「我的应用数据／" + status.optString("appName") + "」，这里就会自动出现。", 18, muted, false);
                empty.setPadding(s(48), s(16), s(48), s(20)); section.addView(empty);
            }
            for (int offset = 0; offset < entries.size(); offset += 4) {
                LinearLayout cards = row(); cards.setGravity(Gravity.TOP); cards.setPadding(s(48), s(7), s(48), s(7));
                List<View> focusRow = new ArrayList<>();
                for (int i = offset; i < Math.min(offset + 4, entries.size()); i++) {
                    final JSONObject entry = entries.get(i);
                    View card = driveFileCard(entry, driveCover(entry), "root-file-" + entry.optString("fsId"), () -> openRootDrive(entry));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s(281), s(230));
                    if (i > offset) lp.leftMargin = s(20);
                    cards.addView(card, lp); focusRow.add(card);
                }
                section.addView(cards); homeFocusRows.add(focusRow); gap(section, 12);
            }
            if (homeDriveStart > 0 || homeDriveListing.optBoolean("hasMore")) {
                LinearLayout pages = row(); pages.setPadding(s(48), s(6), s(48), 0); List<View> controls = new ArrayList<>();
                if (homeDriveStart > 0) { Button previous = button("上一页", "root-previous", false, () -> refreshHomeDrive(true, Math.max(0, homeDriveStart - 100))); pages.addView(previous); controls.add(previous); space(pages, 14); }
                if (homeDriveListing.optBoolean("hasMore")) { Button next = button("下一页", "root-next", false, () -> refreshHomeDrive(true, homeDriveStart + 100)); pages.addView(next); controls.add(next); }
                section.addView(pages); homeFocusRows.add(controls);
            }
        }
        feed.addView(section); gap(feed, 36);
    }

    private String driveCover(JSONObject entry) {
        // 已单独配置过的目录继续使用家长上传的封面，其余目录使用文件夹图标。
        for (String owner : store.resourceOwners()) for (JSONObject course : resourcesFor(owner)) {
            JSONObject binding = course.optJSONObject("baidu");
            if (binding != null && binding.optString("fsId").equals(entry.optString("fsId"))) return course.optString("cover");
        }
        return "";
    }

    private JSONObject rootDriveCourse() {
        JSONObject status = baidu.status();
        if (!status.optBoolean("connected")) return null;
        try {
            // 主目录没有接口返回的 fsId，不伪造文件标识；播放时由百度层单独校验授权根路径。
            return new JSONObject().put("id", DRIVE_ROOT_ID).put("title", status.optString("appName"))
                    .put("kind", "baiduDrive").put("cover", "").put("url", "").put("pinned", true)
                    .put("baidu", new JSONObject().put("path", status.optString("root")).put("isDir", true));
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    private void openRootDrive(JSONObject entry) {
        if (!activateChild("family")) return;
        driveCourse = rootDriveCourse();
        if (driveCourse == null) { toast("请家长先连接百度网盘"); return; }
        driveListing = null; driveError = ""; driveLoading = false; driveStart = 0;
        drivePath = entry.optBoolean("isDir") ? entry.optString("path") : homeDriveRoot;
        driveFocusTag = "drive-file-" + entry.optString("fsId"); page = "drive";
        if (entry.optBoolean("isDir")) loadDrive(drivePath, 0);
        else playDriveFile(entry);
    }

    /** 历史只保存资源标识和文件信息，播放地址每次重新获取；下架、改绑资源不继续展示。 */
    private void recentShelf(LinearLayout feed) {
        JSONArray history = recentPlays();
        LinearLayout section = column(); section.setTag("shelf-recent");
        LinearLayout cards = row(); cards.setPadding(s(48), s(7), s(48), s(7));
        List<View> focusRow = new ArrayList<>();
        for (int i = 0; i < history.length() && focusRow.size() < 5; i++) {
            final JSONObject entry = history.optJSONObject(i);
            final String owner = recentOwner(entry);
            if (owner.isEmpty()) continue;
            JSONObject course = recentCourse(owner, entry);
            LinearLayout card = column(); card.setPadding(s(3), s(3), s(3), s(3));
            FrameLayout art = new FrameLayout(this); art.setBackground(shape(surface, 0, 7)); art.setClipToOutline(true);
            art.addView(new PosterView(this, course.optString("cover")), new FrameLayout.LayoutParams(-1, -1));
            card.addView(art, new LinearLayout.LayoutParams(-1, s(126))); gap(card, 10);
            TextView title = text(entry.optString("title"), 17, ink, true);
            title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); card.addView(title); gap(card, 6);
            boolean finished = entry.optInt("position") >= entry.optInt("duration") - 2000;
            card.addView(text(finished ? "已看完 · 再看一遍" : "继续 " + clock(entry.optInt("position")) + " / " + clock(entry.optInt("duration")), 14, muted, false));
            focusable(card, "recent-" + entry.optString("videoId"), surface, accent, 10);
            card.setOnClickListener(v -> {
                if (!activateChild(owner)) return;
                JSONObject current = recentCourse(owner, entry);
                if (!"baiduDrive".equals(current.optString("kind"))) { openResource(current); return; }
                JSONObject selected = entry.optJSONObject("file");
                driveCourse = current; driveListing = null; driveLoading = false; driveError = "";
                driveStart = 0; drivePath = current.optJSONObject("baidu").optBoolean("isDir")
                        ? BaiduDrive.parentPath(selected.optString("path")) : current.optJSONObject("baidu").optString("path");
                driveFocusTag = "drive-file-" + selected.optString("fsId"); page = "drive"; playDriveFile(selected);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, s(203), 1);
            if (!focusRow.isEmpty()) lp.leftMargin = s(18);
            cards.addView(card, lp); focusRow.add(card);
        }
        if (focusRow.isEmpty()) return;
        TextView heading = text("最近播放", 25, ink, true); heading.setPadding(s(48), 0, s(48), s(12)); section.addView(heading);
        for (int i = focusRow.size(); i < 5; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, s(203), 1); lp.leftMargin = s(18); cards.addView(new View(this), lp);
        }
        section.addView(cards); homeFocusRows.add(focusRow); feed.addView(section); gap(feed, 36);
    }

    private JSONArray recentPlays() {
        try { return new JSONArray(playback.getString("recentPlays", "[]")); }
        catch (org.json.JSONException ignored) { return new JSONArray(); }
    }

    private String recentOwner(JSONObject entry) {
        if (entry == null) return "";
        if ("localVideo".equals(entry.optString("kind"))) {
            Uri uri = Uri.parse(entry.optString("localUri"));
            // 只展示系统已保留读取权限的文件，拔出存储后由播放器提示重新选择。
            for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions())
                if (permission.isReadPermission() && permission.getUri().equals(uri)) return "family";
            return "";
        }
        if (DRIVE_ROOT_ID.equals(entry.optString("courseId"))) {
            JSONObject course = rootDriveCourse(), selected = entry.optJSONObject("file");
            return course != null && selected != null && BaiduDrive.within(selected.optString("path"), course.optJSONObject("baidu").optString("path")) ? "family" : "";
        }
        for (String owner : store.resourceOwners()) {
            try {
                JSONObject current = store.resource(owner, entry.optString("courseId"));
                if (!current.optBoolean("pinned") || !current.optString("kind").equals(entry.optString("kind"))) return "";
                if ("baiduDrive".equals(current.optString("kind"))) {
                    JSONObject binding = current.optJSONObject("baidu"), selected = entry.optJSONObject("file");
                    if (binding == null || selected == null || (binding.optBoolean("isDir")
                            ? !BaiduDrive.within(selected.optString("path"), binding.optString("path"))
                            : !binding.optString("fsId").equals(selected.optString("fsId")))) return "";
                }
                return owner;
            } catch (IllegalArgumentException ignored) { }
        }
        return "";
    }

    private JSONObject recentCourse(String owner, JSONObject entry) {
        if ("localVideo".equals(entry.optString("kind")))
            return localVideoResource(Uri.parse(entry.optString("localUri")), entry.optString("title"));
        return DRIVE_ROOT_ID.equals(entry.optString("courseId")) ? rootDriveCourse() : store.resource(owner, entry.optString("courseId"));
    }

    private void rememberPlayback(int position, int duration) {
        if (position < 1000 || duration <= 0 || playingCourse == null || playingResource == null) return;
        try {
            JSONObject entry = new JSONObject().put("courseId", playingCourse.optString("id")).put("videoId", playingId)
                    .put("kind", playingCourse.optString("kind")).put("title", playerTitle)
                    .put("position", position).put("duration", duration);
            if (playingResource.optJSONObject("baidu") != null) entry.put("file", playingResource.optJSONObject("baidu"));
            if ("localVideo".equals(playingCourse.optString("kind"))) entry.put("localUri", playingCourse.optString("url"));
            JSONArray next = new JSONArray().put(entry), previous = recentPlays();
            for (int i = 0; i < previous.length() && next.length() < 8; i++) {
                JSONObject old = previous.getJSONObject(i);
                if (!old.optString("videoId").equals(playingId) || !old.optString("courseId").equals(playingCourse.optString("id"))) next.put(old);
            }
            playback.edit().putString("recentPlays", next.toString()).apply();
        } catch (org.json.JSONException ignored) { }
    }

    /** 应用由全家共用，固定六列小图标，只在片单底部出现一次；不足一行也不拉伸。 */
    private void appShelf(LinearLayout feed) {
        LinearLayout section = column(); section.setTag("shelf-apps");
        LinearLayout heading = row(); heading.setPadding(s(48), 0, s(48), 0);
        heading.addView(text("我的应用", 25, ink, true)); space(heading, 18);
        heading.addView(text("全家共用", 15, muted, false));
        section.addView(heading, new LinearLayout.LayoutParams(-1, s(40))); gap(section, 10);
        JSONArray installed = store.snapshot().optJSONArray("apps");
        List<View> cards = new ArrayList<>();
        for (int i = 0; i < installed.length(); i++) {
            JSONObject app = installed.optJSONObject(i);
            final String packageName = app.optString("packageName");
            if (packageName.equals("com.dangbeimarket") || !store.isAllowedApp(packageName)) continue;
            cards.add(appCard(app));
        }
        final int columns = 6;
        final int cardWidth = 180;
        for (int offset = 0; offset < cards.size(); offset += columns) {
            LinearLayout row = row(); row.setPadding(s(48), s(6), s(48), s(6));
            List<View> focusRow = new ArrayList<>();
            for (int i = offset; i < Math.min(offset + columns, cards.size()); i++) {
                View card = cards.get(i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s(cardWidth), s(146));
                if (i > offset) lp.leftMargin = s(20);
                row.addView(card, lp); focusRow.add(card);
            }
            section.addView(row); homeFocusRows.add(focusRow);
        }
        if (cards.isEmpty()) {
            TextView empty = text("家长在手机端开放应用后，会显示在这里。", 18, muted, false);
            empty.setPadding(s(48), s(18), s(48), s(18)); section.addView(empty);
        }
        feed.addView(section);
    }

    private View appCard(JSONObject app) {
        final String packageName = app.optString("packageName");
        LinearLayout card = column(); card.setGravity(Gravity.CENTER);
        card.setPadding(s(12), s(16), s(12), s(12));
        ImageView icon = new ImageView(this); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try { icon.setImageDrawable(getPackageManager().getApplicationIcon(packageName)); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { icon.setImageResource(R.drawable.app_icon); }
        card.addView(icon, new LinearLayout.LayoutParams(s(70), s(70))); gap(card, 12);
        TextView label = text(app.optString("label"), 17, ink, false);
        label.setGravity(Gravity.CENTER); label.setSingleLine(true); label.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(label, new LinearLayout.LayoutParams(-1, s(28)));
        focusable(card, "app-" + packageName, surface, accent, 14);
        card.setContentDescription("打开应用，" + app.optString("label"));
        card.setOnClickListener(v -> {
            homeFocusTag = "app-" + packageName;
            openApp(packageName);
        });
        return card;
    }

    /** 电视只展示家长开启的资源，顺序与家长端一致，不再自行调整栏目内容。 */
    private List<JSONObject> resourcesFor(String owner) {
        List<JSONObject> visible = new ArrayList<>();
        JSONArray array = store.resourceList(owner);
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && item.optBoolean("pinned")) visible.add(item);
            }
        }
        return visible;
    }

    private boolean resourceReady(JSONObject item) {
        return !item.optString("url").isEmpty() || ("baiduDrive".equals(item.optString("kind")) && item.optJSONObject("baidu") != null);
    }

    private View resourceCard(final String id, final JSONObject item, int ring) {
        final boolean ready = resourceReady(item);
        LinearLayout card = column(); card.setPadding(s(3), s(3), s(3), s(3));
        FrameLayout art = new FrameLayout(this); art.setBackground(shape(surface, 0, 7)); art.setClipToOutline(true);
        art.addView(new PosterView(this, item.optString("cover")), new FrameLayout.LayoutParams(-1, -1));
        TextView status = text(ready ? "▶" : "待添加", 11, Color.WHITE, false);
        status.setPadding(s(7), s(4), s(7), s(4)); status.setBackground(shape(c("#C51A202B"), 0, 4));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
        badge.setMargins(s(8), s(8), s(8), s(8)); art.addView(status, badge);
        card.addView(art, new LinearLayout.LayoutParams(-1, s(126)));
        LinearLayout copy = column(); copy.setPadding(s(3), s(11), s(3), 0);
        TextView title = text(item.optString("title"), 19, ink, true);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END); copy.addView(title); gap(copy, 6);
        String kind = item.optString("kind").equals("video") ? "动画与视频" : (item.optString("kind").equals("baidu") || item.optString("kind").equals("baiduDrive")) ? "百度网盘课程" : "课程链接";
        copy.addView(text(ready ? kind : kind + " · 待家长添加", 13, muted, false)); card.addView(copy);
        focusable(card, (ready ? "ready-" : "pending-") + id + "-" + item.optString("id"), Color.TRANSPARENT, ring, 10);
        card.setContentDescription(profileName(id) + "，" + item.optString("title") + "，" + (ready ? "打开" : "待家长添加"));
        card.setOnClickListener(v -> {
            if (!ready) { toast("这份内容还没有地址，请找家长添加"); return; }
            if (activateChild(id)) openResource(item);
        });
        return card;
    }

    /** 显式连接网格行，让上下键按海报列向下移动，不跳到下一模块。 */
    private void connectHomeFocus() {
        for (List<View> row : homeFocusRows) for (View view : row) view.setId(View.generateViewId());
        for (int r = 0; r < homeFocusRows.size(); r++) {
            List<View> row = homeFocusRows.get(r);
            List<View> up = homeFocusRows.get(Math.max(0, r - 1));
            List<View> down = homeFocusRows.get(Math.min(homeFocusRows.size() - 1, r + 1));
            for (int i = 0; i < row.size(); i++) {
                View view = row.get(i);
                view.setNextFocusLeftId(row.get(Math.max(0, i - 1)).getId());
                view.setNextFocusRightId(row.get(Math.min(row.size() - 1, i + 1)).getId());
                view.setNextFocusUpId(up.get(Math.min(i, up.size() - 1)).getId());
                view.setNextFocusDownId(down.get(Math.min(i, down.size() - 1)).getId());
            }
        }
    }

    private String profileName(String id) { return store.playlistName(id); }

    /** 只在真实打开内容时选择计时归属，两个模块始终留在同一首页。 */
    private boolean activateChild(String id) {
        if (!store.canWatch(id)) { toast(store.blockedReason(id)); return false; }
        // 片单只作为内容位置，盒子时间规则不因栏目切换重新计时。
        View current = getCurrentFocus();
        if (current != null && current.getTag() instanceof String) homeFocusTag = (String) current.getTag();
        store.selectChild(id); observedActive = id;
        child = id; observedRevision = store.revision();
        return true;
    }

    /** 已有盒子保留原文件管理器；其他电视和平板由系统授权选择单个视频，不扫描存储。 */
    private void openLocalResources() {
        if (!store.canWatch("family")) { toast(store.blockedReason("family")); return; }
        Intent manager = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName("com.softwinner.TvdFileManager", "com.softwinner.TvdFileManager.MainUI");
        try {
            if (getPackageManager().resolveActivity(manager, 0) != null) { openExternalActivity(manager); return; }
        } catch (ActivityNotFoundException | SecurityException ignored) { }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            if (getPackageManager().resolveActivity(picker, 0) == null) {
                picker.setAction(Intent.ACTION_GET_CONTENT);
                picker.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            startActivityForResult(picker, LOCAL_VIDEO_REQUEST);
        } catch (ActivityNotFoundException | SecurityException error) {
            toast("系统没有提供文件选择器，请家长安装文件管理器后再打开本地资源");
        }
    }

    private JSONObject localVideoResource(Uri uri, String title) {
        try {
            return new JSONObject().put("id", "local-" + java.util.UUID.nameUUIDFromBytes(uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .put("title", title).put("kind", "localVideo").put("url", uri.toString()).put("cover", "course").put("pinned", true);
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    private void openResource(JSONObject item) {
        try {
            if (!store.canWatch(child)) { toast(store.blockedReason(child)); return; }
            if ("baiduDrive".equals(item.optString("kind"))) { openDriveCourse(item); return; }
            // 只有真实地址才进入播放；预置插画卡不伪装成已就绪的影片。
            if (item.optString("url").isEmpty()) {
                new AlertDialog.Builder(this).setTitle("待添加资源")
                        .setMessage("“" + item.optString("title") + "”还没有播放地址。\n请家长用手机打开管理页，添加视频地址或课程链接。")
                        .setPositiveButton("打开家长管理", (dialog, which) -> pairing())
                        .setNegativeButton("返回", null).show();
                return;
            }
            // 网盘分享只交给本机网盘；旧版保存为普通链接的网盘地址也不能再落到浏览器。
            if (item.optString("kind").equals("baidu")
                    || (item.optString("kind").equals("link")
                    && "pan.baidu.com".equalsIgnoreCase(Uri.parse(item.optString("url")).getHost()))) openBaiduResource(item);
            else if (item.optString("kind").equals("video") || item.optString("kind").equals("localVideo")) playVideo(item);
            else {
                new AlertDialog.Builder(this).setTitle(item.optString("title"))
                        .setMessage("将在已安装的应用或浏览器中打开，使用时间计入整个盒子。")
                        .setPositiveButton("打开", (dialog, which) -> {
                            try {
                                if (!store.canWatch(child)) { toast(store.blockedReason(child)); return; }
                                openExternalActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.optString("url"))));
                            }
                            catch (ActivityNotFoundException error) { toast("盒子没有可打开此链接的应用，请家长先安装"); }
                            catch (RuntimeException error) { toast("无法打开此链接，请家长检查地址"); }
                        }).setNegativeButton("返回", null).show();
            }
        } catch (RuntimeException error) { toast("资源暂时无法打开，请家长检查设置"); }
    }

    private void openDriveCourse(JSONObject course) {
        driveCourse = course; driveListing = null; driveFocusTag = ""; driveError = "";
        drivePath = course.optJSONObject("baidu").optString("path"); driveStart = 0; page = "drive";
        if (course.optJSONObject("baidu").optBoolean("isDir")) loadDrive(drivePath, 0);
        else {
            try {
                JSONObject file = new JSONObject(course.optJSONObject("baidu").toString()).put("name", course.optString("title"));
                driveListing = new JSONObject().put("entries", new JSONArray().put(file)).put("hasMore", false);
                playDriveFile(file);
            } catch (org.json.JSONException error) { toast("课程文件信息无效，请家长重新选择"); }
        }
    }

    private void loadDrive(final String path, final int start) {
        if (driveCourse == null) return;
        String courseRoot = driveCourse.optJSONObject("baidu").optString("path");
        if (!BaiduDrive.within(path, courseRoot)) { toast("只能浏览这份课程的目录"); return; }
        final int request = ++driveRequest;
        if (!path.equals(drivePath) || start != driveStart) driveFocusTag = "";
        drivePath = path; driveStart = start; driveLoading = true; driveError = ""; render();
        driveWorker.execute(() -> {
            JSONObject result = null; String error = "";
            try { result = baidu.list(path, start); }
            catch (IllegalArgumentException problem) { error = problem.getMessage(); }
            catch (RuntimeException problem) { error = "暂时无法读取网盘，请稍后重试"; }
            final JSONObject listing = result; final String message = error;
            handler.post(() -> {
                if (request != driveRequest || isFinishing() || driveCourse == null) return;
                driveLoading = false; driveListing = listing; driveError = message; render();
            });
        });
    }

    /** 文件夹沿用网盘原层级和名称；视频类型由百度 category 字段确定。 */
    private void renderDrive() {
        if (!driveLoading && driveListing == null && driveError.isEmpty() && driveCourse.optJSONObject("baidu").optBoolean("isDir")) {
            loadDrive(drivePath, driveStart); return;
        }
        palette(); firstFocus = null;
        root = new FrameLayout(this); root.setBackgroundColor(background); body = column();
        root.addView(body, new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        LinearLayout bar = row(); bar.setPadding(s(48), s(20), s(48), s(16));
        bar.addView(backButton("drive-back", this::backDrive)); space(bar, 20);
        TextView title = text(driveCourse.optString("title"), 28, ink, true);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, s(50), 1));
        bar.addView(plainButton("首页", "drive-home", this::exitDrive)); body.addView(bar);
        String courseRoot = driveCourse.optJSONObject("baidu").optString("path");
        String relative = drivePath.equals(courseRoot) ? "课程目录" : drivePath.substring(courseRoot.length() + 1).replace("/", "  /  ");
        TextView breadcrumb = text("百度网盘  /  " + relative, 17, muted, false);
        breadcrumb.setPadding(s(48), s(5), s(48), s(14)); breadcrumb.setMaxLines(2); body.addView(breadcrumb);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setFocusable(false);
        scroll.setClipToPadding(false);
        LinearLayout list = column(); list.setPadding(s(48), s(4), s(48), s(38)); scroll.addView(list);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (driveLoading) list.addView(text("正在读取网盘…", 24, ink, false));
        else if (!driveError.isEmpty()) {
            list.addView(text(driveError, 22, ink, false)); gap(list, 20);
            list.addView(button("重试", "drive-retry", true, () -> loadDrive(drivePath, driveStart)));
        } else if (driveListing != null) {
            JSONArray entries = driveListing.optJSONArray("entries");
            if (entries.length() == 0) list.addView(text("这个文件夹还没有内容", 23, muted, false));
            else {
                String hint = entries.length() + (driveListing.optBoolean("hasMore") ? "+" : "") + " 项  ·  选择视频即可播放";
                if (!driveListing.optString("previewError").isEmpty()) hint += "  ·  " + driveListing.optString("previewError");
                list.addView(text(hint, 15, muted, false)); gap(list, 18);
            }
            View firstEntry = null;
            List<List<View>> rows = new ArrayList<>();
            List<View> focusRow = null;
            LinearLayout gridRow = null;
            for (int i = 0; i < entries.length(); i++) {
                final JSONObject entry = entries.optJSONObject(i);
                if (i % 4 == 0) {
                    gridRow = row(); gridRow.setGravity(Gravity.TOP); list.addView(gridRow); gap(list, 22);
                    focusRow = new ArrayList<>(); rows.add(focusRow);
                }
                String cover = driveCourse.optString("cover");
                if (cover.isEmpty()) cover = driveCover(entry);
                View item = driveFileCard(entry, cover, "drive-file-" + entry.optString("fsId"), () -> {
                    driveFocusTag = "drive-file-" + entry.optString("fsId");
                    if (entry.optBoolean("isDir")) loadDrive(entry.optString("path"), 0);
                    else if (entry.optInt("category") == 1) playDriveFile(entry);
                    else toast("这个文件不是视频，请用百度网盘应用查看");
                });
                item.setId(View.generateViewId()); focusRow.add(item);
                if (firstEntry == null) firstEntry = item;
                LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(s(281), s(230));
                if (i % 4 > 0) card.leftMargin = s(20);
                gridRow.addView(item, card);
            }
            // 固定四列的上下焦点与可见区域一起移动，鼠标滚轮仍由 ScrollView 自然处理。
            for (int r = 0; r < rows.size(); r++) for (int col = 0; col < rows.get(r).size(); col++) {
                View item = rows.get(r).get(col);
                item.setNextFocusLeftId(rows.get(r).get(Math.max(0, col - 1)).getId());
                item.setNextFocusRightId(rows.get(r).get(Math.min(rows.get(r).size() - 1, col + 1)).getId());
                if (r > 0) item.setNextFocusUpId(rows.get(r - 1).get(Math.min(col, rows.get(r - 1).size() - 1)).getId());
                if (r + 1 < rows.size()) item.setNextFocusDownId(rows.get(r + 1).get(Math.min(col, rows.get(r + 1).size() - 1)).getId());
            }
            LinearLayout pages = row();
            if (driveStart > 0) pages.addView(button("上一页", "drive-previous", false, () -> loadDrive(drivePath, Math.max(0, driveStart - 100))));
            if (driveListing.optBoolean("hasMore")) {
                space(pages, 12); pages.addView(button("下一页", "drive-next", false, () -> loadDrive(drivePath, driveStart + 100)));
            }
            list.addView(pages);
            if (firstEntry != null) firstFocus = firstEntry;
        }
        View target = driveFocusTag.isEmpty() ? null : root.findViewWithTag(driveFocusTag);
        if (target == null) target = firstFocus;
        if (target != null) target.requestFocusFromTouch();
    }

    /** 首页主目录与课程目录共用卡片，缩略图、比例和遥控器焦点保持一致。 */
    private View driveFileCard(JSONObject entry, String cover, String tag, Runnable action) {
        boolean directory = entry.optBoolean("isDir"), playable = entry.optInt("category") == 1;
        LinearLayout item = column(); item.setPadding(s(4), s(4), s(4), s(10));
        FrameLayout artwork = new FrameLayout(this); artwork.setBackground(shape(c("#2C303B"), 0, 10)); artwork.setClipToOutline(true);
        if (!entry.optString("thumbnailId").isEmpty() || !cover.isEmpty())
            artwork.addView(new DriveThumbnailView(this, entry.optString("thumbnailId"), cover), new FrameLayout.LayoutParams(-1, -1));
        if (directory || (!playable || (entry.optString("thumbnailId").isEmpty() && cover.isEmpty()))) {
            if (!cover.isEmpty()) { View shade = new View(this); shade.setBackgroundColor(c("#55101218")); artwork.addView(shade, new FrameLayout.LayoutParams(-1, -1)); }
            artwork.addView(new ControlIconView(this, directory ? "folder" : "play", directory ? "文件夹" : "视频"),
                    new FrameLayout.LayoutParams(s(96), s(96), Gravity.CENTER));
        }
        TextView badge = text(directory ? "文件夹" : playable ? (entry.optInt("duration") > 0 ? clock(entry.optInt("duration") * 1000) : "视频") : "文件", 13, Color.WHITE, false);
        badge.setPadding(s(10), s(4), s(10), s(4)); badge.setBackground(shape(c("#B3101218"), 0, 6));
        FrameLayout.LayoutParams badgeLayout = new FrameLayout.LayoutParams(-2, s(28), Gravity.RIGHT | Gravity.BOTTOM);
        badgeLayout.setMargins(s(8), s(8), s(8), s(8)); artwork.addView(badge, badgeLayout);
        item.addView(artwork, new LinearLayout.LayoutParams(-1, s(153))); gap(item, 10);
        String filename = entry.optString("name");
        if (playable && filename.lastIndexOf('.') > 0) filename = filename.substring(0, filename.lastIndexOf('.'));
        TextView name = text(filename, 18, ink, true);
        name.setGravity(Gravity.TOP); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(s(9), 0, s(9), 0); item.addView(name, new LinearLayout.LayoutParams(-1, s(49)));
        focusable(item, tag, surface, accent, 14); item.setOnClickListener(v -> action.run());
        return item;
    }

    private void playDriveFile(final JSONObject selected) {
        if (!store.canWatch(child)) { toast(store.blockedReason(child)); return; }
        if (!driveCourseAvailable()) { exitDrive(); toast("家长更新了课程，请从首页重新打开"); return; }
        final JSONObject course = driveCourse;
        final int request = ++driveRequest; driveLoading = true; driveError = ""; render();
        driveWorker.execute(() -> {
            String url = "", error = "";
            try { url = DRIVE_ROOT_ID.equals(course.optString("id")) ? baidu.createRootStream(selected) : baidu.createStream(course.optJSONObject("baidu"), selected); }
            catch (IllegalArgumentException problem) { error = problem.getMessage(); }
            catch (RuntimeException problem) { error = "暂时无法连接网盘视频，请稍后重试"; }
            final String source = url, message = error;
            handler.post(() -> {
                if (request != driveRequest || isFinishing() || driveCourse != course) return;
                driveLoading = false;
                if (!message.isEmpty()) { render(); toast(message); return; }
                if (!driveCourseAvailable()) { exitDrive(); toast("家长更新了课程，请从首页重新打开"); return; }
                if (!foreground || !store.canWatch(child)) { baidu.closeStreams(); render(); return; }
                try {
                    JSONObject resource = new JSONObject().put("id", "baidu-" + selected.optString("fsId"))
                            .put("title", selected.optString("name")).put("url", source)
                            .put("baidu", new JSONObject().put("fsId", selected.optString("fsId")).put("path", selected.optString("path"))
                                    .put("isDir", false).put("category", 1).put("name", selected.optString("name")));
                    playVideo(resource, course);
                } catch (org.json.JSONException problem) { render(); toast("视频信息无效，请重新打开"); }
            });
        });
    }

    private void backDrive() {
        if (driveCourse == null) { exitDrive(); return; }
        String courseRoot = driveCourse.optJSONObject("baidu").optString("path");
        if (driveCourse.optJSONObject("baidu").optBoolean("isDir") && !drivePath.equals(courseRoot)) loadDrive(BaiduDrive.parentPath(drivePath), 0);
        else exitDrive();
    }

    private void exitDrive() {
        driveRequest++; driveCourse = null; driveListing = null; driveLoading = false; driveFocusTag = "";
        baidu.closeStreams(); page = "home"; render();
    }

    private boolean driveCourseAvailable() {
        if (driveCourse == null) return false;
        if (DRIVE_ROOT_ID.equals(driveCourse.optString("id"))) {
            JSONObject current = rootDriveCourse();
            return current != null && current.optJSONObject("baidu").optString("path").equals(driveCourse.optJSONObject("baidu").optString("path"));
        }
        try {
            JSONObject current = store.resource(child, driveCourse.optString("id"));
            return current.optBoolean("pinned") && "baiduDrive".equals(current.optString("kind"))
                    && String.valueOf(current.optJSONObject("baidu")).equals(String.valueOf(driveCourse.optJSONObject("baidu")));
        } catch (IllegalArgumentException error) { return false; }
    }

    /** 先验证网盘是否接收分享链接；TV 版只有应用入口时，明确说明无法直达课程。 */
    private void openBaiduResource(JSONObject item) {
        android.content.pm.PackageManager pm = getPackageManager();
        String[] packages = {"com.baidu.netdisk.tv", "com.baidu.netdisk"};
        for (String packageName : packages) {
            Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(item.optString("url"))).setPackage(packageName);
            for (android.content.pm.ResolveInfo entry : pm.queryIntentActivities(link, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
                if (entry.activityInfo == null || !entry.activityInfo.exported || !entry.activityInfo.enabled
                        || !packageName.equals(entry.activityInfo.packageName)) continue;
                link.setClassName(packageName, entry.activityInfo.name);
                openExternalActivity(link);
                return;
            }
        }
        Intent launch = null;
        for (String packageName : packages) {
            launch = pm.getLeanbackLaunchIntentForPackage(packageName);
            if (launch == null) launch = pm.getLaunchIntentForPackage(packageName);
            if (launch != null) break;
        }
        if (launch == null) {
            new AlertDialog.Builder(this).setTitle("没有可用的百度网盘")
                    .setMessage("请家长先在家长工具中安装或启用百度网盘，再打开这份课程。")
                    .setPositiveButton("打开家长工具", (dialog, which) -> pairing())
                    .setNegativeButton("返回", null).show();
            return;
        }
        final Intent app = launch;
        // 已检查没有可接收分享的入口，不能把启动应用首页当作已打开指定课程。
        new AlertDialog.Builder(this).setTitle(item.optString("title"))
                .setMessage("当前百度网盘版本不支持从分享链接直达这份课程。\n\n可打开本机百度网盘，再在「我的文件」中选择。若还没保存，请家长先在手机百度网盘中把分享内容保存到电视登录的同一个账号。\n\n打开后仍需手动选择课程，使用时间计入整个盒子。")
                .setPositiveButton("打开本机百度网盘", (dialog, which) -> {
                    try {
                        if (!store.canWatch(child)) { toast(store.blockedReason(child)); return; }
                        openExternalActivity(app);
                    } catch (RuntimeException error) { toast("百度网盘暂时无法打开，请家长检查安装状态"); }
                }).setNegativeButton("返回", null).show();
    }

    private void openApp(String packageName) {
        try {
            // 外部应用为家庭共用入口，开放与否由家长统一管理，不绑定某个孩子的片单身份。
            if (!store.isAllowedApp(packageName)) { toast("家长还没有开放这个应用"); return; }
            Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
            if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) { toast("这个应用暂时无法启动"); return; }
            openExternalActivity(intent);
        } catch (RuntimeException error) { toast("应用暂时无法打开，请家长检查安装状态"); }
    }

    private void pairing() {
        if (store == null || isFinishing()) return;
        if (pairingDialog != null && pairingDialog.isShowing()) return;
        LinearLayout panel = column();
        panel.setPadding(s(28), s(14), s(28), s(18));
        panel.addView(text("家长验证后才能打开安装工具和管理入口。", 20, ink, false)); gap(panel, 12);
        final EditText pin = new EditText(this);
        pin.setSingleLine(true);
        pin.setHint("4–6 位数字");
        pin.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(24));
        pin.setTextColor(ink); pin.setHintTextColor(muted);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        panel.addView(pin, new LinearLayout.LayoutParams(-1, s(62)));
        gap(panel, 8);
        panel.addView(text(store.parentPinHint(), 16, muted, false));
        pairingDialog = new AlertDialog.Builder(this).setTitle("果然 TV · 家长验证").setView(panel)
                .setPositiveButton("进入家长工具", null)
                .setNegativeButton("取消", null)
                .create();
        pairingDialog.setOnShowListener(dialog -> {
            pairingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (store.verifyParentPin(pin.getText().toString().trim())) {
                    pairingDialog.dismiss();
                    parentTools();
                } else {
                    pin.setError("PIN 不正确");
                    pin.requestFocus();
                }
            });
            pin.requestFocus();
        });
        pairingDialog.show();
        if (pairingDialog.getWindow() != null) pairingDialog.getWindow().setLayout(s(620), -2);
    }

    private void parentTools() {
        if (parentToolsDialog != null && parentToolsDialog.isShowing()) return;
        LinearLayout panel = column();
        panel.setPadding(s(28), s(14), s(28), s(18));
        panel.addView(text("家长工具", 28, ink, true)); gap(panel, 6);
        panel.addView(text("安装电视应用、调整盒子设置，或用手机管理课程和观看安排。", 17, muted, false)); gap(panel, 18);
        panel.addView(button("电视资源安装（当贝市场）", "parent-dangbei", true, new Runnable() { public void run() { openDangbeiMarket(); } }));
        gap(panel, 10);
        panel.addView(button("系统设置（网络、显示、声音）", "parent-settings", false, new Runnable() { public void run() { systemSettings(); } }));
        gap(panel, 10);
        panel.addView(button("运行内存与缓存清理", "parent-memory", false, this::memoryTools));
        gap(panel, 10);
        panel.addView(button("鼠标返回与首页", "parent-navigation", false, () -> {
            if (parentToolsDialog != null) parentToolsDialog.dismiss();
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (RuntimeException error) { toast("盒子未提供无障碍设置入口"); }
        }));
        gap(panel, 10);
        panel.addView(button("手机管理：添加课程和资源", "parent-phone", false, new Runnable() { public void run() { showPhoneManagement(); } }));
        gap(panel, 10);
        panel.addView(button("修改家长 PIN", "parent-pin", false, new Runnable() { public void run() { changeParentPin(); } }));
        parentToolsDialog = new AlertDialog.Builder(this).setTitle("果然 TV · 家长工具").setView(panel)
                .setNegativeButton("关闭", null).create();
        parentToolsDialog.setOnShowListener(dialog -> {
            Button market = panel.findViewWithTag("parent-dangbei");
            if (market != null) market.requestFocus();
        });
        parentToolsDialog.show();
        if (parentToolsDialog.getWindow() != null) parentToolsDialog.getWindow().setLayout(s(760), -2);
    }

    private void memoryTools() {
        if (parentToolsDialog != null) parentToolsDialog.dismiss();
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory);
        int cache = PosterView.memoryCacheBytes() + DriveThumbnailView.memoryCacheBytes();
        String message = "盒子可用运行内存 " + memory.availMem / 1048576 + " MB / " + memory.totalMem / 1048576 + " MB\n"
                + "果然 TV 图片缓存 " + String.format(Locale.US, "%.1f", cache / 1048576f) + " MB\n\n"
                + "释放图片缓存不删除课程、封面和最近播放。其他应用的缓存可在系统应用管理中清理。";
        new AlertDialog.Builder(this).setTitle("运行内存与缓存").setMessage(message)
                .setPositiveButton("释放图片缓存", (dialog, which) -> {
                    int bytes = PosterView.clearMemoryCache() + DriveThumbnailView.clearMemoryCache();
                    toast("已清空 " + String.format(Locale.US, "%.1f", bytes / 1048576f) + " MB 图片缓存");
                }).setNeutralButton("应用管理", (dialog, which) -> openSystemSetting("应用管理", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))
                .setNegativeButton("返回", (dialog, which) -> parentTools()).show();
    }

    private void openDangbeiMarket() {
        try {
            Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage("com.dangbeimarket");
            if (intent == null) intent = getPackageManager().getLaunchIntentForPackage("com.dangbeimarket");
            if (intent == null) throw new ActivityNotFoundException();
            if (parentToolsDialog != null) parentToolsDialog.dismiss();
            openExternalActivity(intent);
        } catch (ActivityNotFoundException error) {
            new AlertDialog.Builder(this).setTitle("还没有安装当贝市场")
                    .setMessage("请用手机家长管理上传当贝市场 APK，或用 U 盘安装后再从这里打开。")
                    .setPositiveButton("知道了", null).show();
        }
    }

    private void showPhoneManagement() {
        if (parentToolsDialog != null) parentToolsDialog.dismiss();
        LinearLayout panel = column();
        panel.setPadding(s(28), s(14), s(28), s(18));
        panel.addView(text("手机与电视连接同一 Wi‑Fi，在手机浏览器打开：", 19, ink, false)); gap(panel, 12);
        TextView address = text(store.serverUrl(), 25, accent, true);
        address.setTextIsSelectable(true); panel.addView(address); gap(panel, 14);
        panel.addView(text("输入下面的配对码：", 17, muted, false));
        TextView code = text(store.pairingCode(), 42, ink, true);
        code.setLetterSpacing(.12f); panel.addView(code); gap(panel, 12);
        panel.addView(text("手机端可以连接自家网盘、选择课程目录、指定适用孩子，并管理观看安排和应用。", 16, muted, false));
        new AlertDialog.Builder(this).setTitle("手机家长管理").setView(panel)
                .setPositiveButton("完成", null).setNeutralButton("刷新配对码", (dialog, which) -> {
                    store.rotatePairingCode(); showPhoneManagement();
                }).show();
    }

    private void changeParentPin() {
        if (parentToolsDialog != null) parentToolsDialog.dismiss();
        LinearLayout panel = column();
        panel.setPadding(s(28), s(14), s(28), s(18));
        final EditText next = new EditText(this); next.setSingleLine(true); next.setHint("新 PIN（4–6 位数字）");
        next.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(22)); next.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        next.setTextColor(ink); next.setHintTextColor(muted);
        final EditText repeat = new EditText(this); repeat.setSingleLine(true); repeat.setHint("再次输入新 PIN");
        repeat.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(22)); repeat.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        repeat.setTextColor(ink); repeat.setHintTextColor(muted);
        panel.addView(next, new LinearLayout.LayoutParams(-1, s(58))); gap(panel, 8); panel.addView(repeat, new LinearLayout.LayoutParams(-1, s(58)));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("修改家长 PIN").setView(panel)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String first = next.getText().toString().trim(), second = repeat.getText().toString().trim();
            if (!first.matches("\\d{4,6}") || !first.equals(second)) { repeat.setError("两次 PIN 必须相同，且为 4–6 位数字"); repeat.requestFocus(); return; }
            store.setParentPin(first); dialog.dismiss(); toast("家长 PIN 已更新");
        }));
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(s(620), -2);
    }

    private void playVideo(final JSONObject resource) {
        playVideo(resource, resource);
    }

    private void playVideo(final JSONObject resource, final JSONObject course) {
        stopVideo();
        playingCourse = course;
        playingResource = resource;
        page = "player";
        playingId = resource.optString("id");
        playingUrl = resource.optString("url");
        playerTitle = resource.optString("title");
        userPaused = false;
        playerOpenedAt = SystemClock.elapsedRealtime(); stalledAt = playerOpenedAt;
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        root.setOnClickListener(v -> showPlayerControls());
        video = new VideoView(this);
        video.setFocusable(false);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        root.addView(video, vp);
        LinearLayout loading = column(); loading.setGravity(Gravity.CENTER);
        ProgressBar spinner = new ProgressBar(this); loading.addView(spinner, new LinearLayout.LayoutParams(s(40), s(40))); gap(loading, 16);
        playerStatus = text("正在打开视频…", 21, Color.WHITE, false); playerStatus.setGravity(Gravity.CENTER);
        loading.addView(playerStatus, new LinearLayout.LayoutParams(-1, -2));
        playerLoading = loading;
        FrameLayout.LayoutParams loadingLayout = new FrameLayout.LayoutParams(s(750), -2, Gravity.CENTER);
        root.addView(loading, loadingLayout);
        LinearLayout overlay = column();
        overlay.setPadding(s(42), s(20), s(42), s(54));
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{c("#A6000000"), Color.TRANSPARENT, c("#C9000000")}));
        playerControls = overlay;
        LinearLayout top = row();
        View back = backButton("player-back", this::leavePlayer);
        top.addView(back); space(top, 18);
        TextView title = text(resource.optString("title"), 24, Color.WHITE, true);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, s(54), 1));
        top.addView(plainButton("其他播放器", "player-external", this::chooseExternalPlayer));
        overlay.addView(top);
        overlay.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        playerSeek = new SeekBar(this); playerSeek.setMax(1000);
        playerSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        playerSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        playerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && video != null && prepared) {
                    int destination = (int) (video.getDuration() * (progress / 1000f));
                    progressView.setText(clock(destination) + "  /  " + clock(video.getDuration()));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { playerSeeking = true; handler.removeCallbacks(hidePlayerControls); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                playerSeeking = false;
                if (video != null && prepared) seek((int) (video.getDuration() * (bar.getProgress() / 1000f)) - video.getCurrentPosition());
                showPlayerControls();
            }
        });
        overlay.addView(playerSeek, new LinearLayout.LayoutParams(-1, s(34)));
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(s(3), s(3), s(3), s(3));
        playButton = playerIcon("play", "播放或暂停", "player-toggle", this::togglePlayback);
        controls.addView(playButton, new LinearLayout.LayoutParams(s(48), s(48)));
        space(controls, 16);
        controls.addView(playerIcon("rewind", "后退 10 秒", "player-rewind", () -> seek(-10000)), new LinearLayout.LayoutParams(s(48), s(48)));
        space(controls, 8);
        controls.addView(playerIcon("forward", "快进 10 秒", "player-forward", () -> seek(10000)), new LinearLayout.LayoutParams(s(48), s(48)));
        space(controls, 18);
        progressView = text("正在连接视频…", 18, Color.WHITE, false);
        progressView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        controls.addView(progressView, new LinearLayout.LayoutParams(0, s(50), 1));
        overlay.addView(controls);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        MouseNavigationService.setPlaybackActive(true);
        playButton.requestFocus();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final VideoView target = video;
        target.setOnPreparedListener(mp -> {
            if (video != target) return;
            if (!store.canWatch(child)) { leavePlayer(); toast(store.blockedReason(child)); return; }
            prepared = true;
            int position = playback.getInt("position-" + child + "-" + playingId, 0);
            if (position > 0 && position < target.getDuration() - 5000) target.seekTo(position);
            // 此回调早于 VideoView 内部的 Surface 尺寸协调，不能在这里提前启动解码器。
            // setVideoURI 后预设的播放目标会由 VideoView 在 Surface 就绪时自动执行。
            lastProbe = SystemClock.elapsedRealtime(); lastPosition = position;
            lastWasPlaying = false;
            playButton.setIcon("pause");
            playerStatus.setText("正在缓冲视频…"); playerSeek.setEnabled(true);
            android.util.Log.i("GuoranTV", "视频已准备，等待画面，耗时ms=" + (SystemClock.elapsedRealtime() - playerOpenedAt));
            showPlayerControls();
            mp.setOnInfoListener((player, what, extra) -> {
                if (video != target) return false;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    probePlayback(false); buffering = true; stalledAt = SystemClock.elapsedRealtime();
                    playerStatus.setText("正在缓冲视频…"); playerLoading.setVisibility(View.VISIBLE);
                }
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    buffering = false; stalledAt = 0; playerLoading.setVisibility(View.GONE);
                    lastProbe = SystemClock.elapsedRealtime(); lastPosition = target.getCurrentPosition();
                    // 缓冲恢复不自动唤出控制层，避免网速波动时底栏反复遮挡。
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                        android.util.Log.i("GuoranTV", "视频首帧已显示，耗时ms=" + (SystemClock.elapsedRealtime() - playerOpenedAt));
                }
                return false;
            });
        });
        target.setOnCompletionListener(mp -> {
            if (video != target) return;
            probePlayback(true);
            String key = "position-" + child + "-" + playingId;
            stopVideo();
            playback.edit().remove(key).apply();
            page = driveCourse == null ? "home" : "drive"; render(); toast("这段视频看完啦");
        });
        target.setOnErrorListener((mp, what, extra) -> {
            if (video == target) {
                android.util.Log.w("GuoranTV", "视频播放失败 what=" + what + " extra=" + extra);
                stopVideo(); page = driveCourse == null ? "home" : "drive"; render();
                new AlertDialog.Builder(this).setTitle("视频暂时无法播放")
                        .setMessage("请家长检查网络、文件权限或视频格式。这个盒子建议使用 H.264 编码的 MP4 视频；H.265 等格式可能受系统解码器限制。")
                        .setPositiveButton("知道了", null).show();
            }
            return true;
        });
        playerSeek.setEnabled(false);
        try {
            target.setVideoURI(Uri.parse(playingUrl));
            // 按 Android VideoView 的标准时序预设目标状态，准备及 Surface 就绪后自动起播。
            target.start(); target.requestLayout();
        }
        catch (RuntimeException error) { leavePlayer(); toast("无法载入视频，请家长检查地址"); }
    }

    private ControlIconView playerIcon(String icon, String description, String tag, Runnable action) {
        ControlIconView view = new ControlIconView(this, icon, description);
        focusable(view, tag, Color.TRANSPARENT, Color.WHITE, 7);
        view.setOnClickListener(v -> { action.run(); showPlayerControls(); });
        return view;
    }

    /** 播放中控制层自动收起，遥控器方向键、确定键或鼠标点击可再次唤起。 */
    private void showPlayerControls() {
        if (video == null || playerControls == null) return;
        handler.removeCallbacks(hidePlayerControls);
        boolean hidden = playerControls.getVisibility() != View.VISIBLE;
        playerControls.setVisibility(View.VISIBLE);
        if (hidden && playButton != null) playButton.requestFocusFromTouch();
        handler.postDelayed(hidePlayerControls, 4000);
    }

    /** 外部播放器明确告知家长限制边界，仍仅传盒子回环地址，不传百度令牌。 */
    private void chooseExternalPlayer() {
        final Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(playingUrl), "video/*");
        if ("content".equals(Uri.parse(playingUrl).getScheme())) {
            // 系统文件选择器只授权当前文件，交给另一个播放器时转授这一个 URI 的读取权限。
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("视频", Uri.parse(playingUrl)));
        }
        intent.setPackage("com.mxtech.videoplayer.ad");
        if (getPackageManager().resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) == null) {
            intent.setPackage(null);
            if (getPackageManager().resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) == null) {
                toast("请家长先从当贝市场安装视频播放器"); return;
            }
        }
        new AlertDialog.Builder(this).setTitle("使用已安装的播放器")
                .setMessage("将播放当前视频，时间仍计入整盒子额度。可点右下角返回回到片单。")
                .setPositiveButton("打开播放器", (dialog, which) -> {
                    if (video == null || !store.canWatch(child)) return;
                    intent.putExtra("title", playerTitle);
                    try {
                        stopVideo(false); externalStream = true; page = driveCourse == null ? "home" : "drive";
                        startActivity(intent);
                    } catch (RuntimeException error) {
                        externalStream = false; baidu.closeStreams(); render(); toast("播放器未能打开，请重新选择视频");
                    }
                }).setNegativeButton("继续当前播放", (dialog, which) -> showPlayerControls()).show();
    }

    private void togglePlayback() {
        if (video == null || !prepared) return;
        try {
            if (!store.canWatch(child)) { leavePlayer(); toast(store.blockedReason(child)); return; }
            probePlayback(true);
            userPaused = !userPaused;
            if (userPaused) { video.pause(); playButton.setIcon("play"); }
            else { video.start(); playButton.setIcon("pause"); }
            lastProbe = SystemClock.elapsedRealtime(); lastPosition = video.getCurrentPosition(); lastWasPlaying = false;
            showPlayerControls();
        } catch (RuntimeException error) { leavePlayer(); toast("播放已停止，请重新打开视频"); }
    }

    private void seek(int delta) {
        if (video == null || !prepared) return;
        try {
            probePlayback(true);
            int position = Math.max(0, Math.min(video.getDuration() - 500, video.getCurrentPosition() + delta));
            video.seekTo(position); lastPosition = position; lastProbe = SystemClock.elapsedRealtime(); lastWasPlaying = false;
            showPlayerControls();
        } catch (RuntimeException error) { toast("暂时无法调整播放进度"); }
    }

    private void probePlayback(boolean flush) {
        if (video == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            int position = prepared ? video.getCurrentPosition() : 0;
            boolean playing = prepared && foreground && !buffering && video.isPlaying();
            // 只统计前台、未缓冲且进度实际前进的播放时间；快进的跳跃不算观看。
            if (lastWasPlaying && lastProbe > 0 && position > lastPosition) {
                long elapsed = Math.min(2000, now - lastProbe);
                if (position - lastPosition <= elapsed + 2000) pendingMillis += Math.min(elapsed, position - lastPosition);
            }
            lastProbe = now; lastPosition = position; lastWasPlaying = playing;
            if (pendingMillis >= 5000 || flush) {
                int seconds = (int) (pendingMillis / 1000);
                if (seconds > 0) {
                    pendingMillis -= Math.min(60, seconds) * 1000L;
                }
                if (prepared && !playingId.isEmpty()) playback.edit().putInt("position-" + child + "-" + playingId, position).apply();
                if (prepared) rememberPlayback(position, video.getDuration());
            }
            if (progressView != null && prepared) progressView.setText(clock(position) + "  /  " + clock(video.getDuration()));
            if (playerSeek != null && prepared && !playerSeeking && video.getDuration() > 0)
                playerSeek.setProgress((int) (position * 1000L / video.getDuration()));
            if (playerStatus != null && (!prepared || buffering) && now - stalledAt > 10000)
                playerStatus.setText("视频仍在缓冲…\n可按返回重新选择，或在右上角试用其他播放器");
            // 关闭播放器也会刷新进度；只允许定时探测触发超时，避免关闭过程递归进入。
            if (!flush && !prepared && now - playerOpenedAt > 30000) {
                leavePlayer(); toast("打开视频超时，请检查网络或文件存储后重试");
            }
        } catch (RuntimeException error) { lastWasPlaying = false; }
    }

    private String clock(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void stopVideo() {
        stopVideo(true);
    }

    private void stopVideo(boolean closeStream) {
        MouseNavigationService.setPlaybackActive(false);
        if (video == null) return;
        probePlayback(true);
        VideoView old = video; video = null;
        try { old.stopPlayback(); } catch (RuntimeException ignored) { }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prepared = false; buffering = false; lastWasPlaying = false;
        lastProbe = 0; lastPosition = 0; pendingMillis = 0;
        playingId = ""; playingUrl = ""; playingCourse = null; playingResource = null;
        if (closeStream) baidu.closeStreams();
        handler.removeCallbacks(hidePlayerControls);
        playButton = null; progressView = null;
        playerControls = null; playerStatus = null; playerLoading = null; playerSeek = null; playerSeeking = false;
    }

    private void checkCurrentResource() {
        if (playingCourse == null) return;
        if ("localVideo".equals(playingCourse.optString("kind"))) return;
        if (DRIVE_ROOT_ID.equals(playingCourse.optString("id"))) {
            if (driveCourseAvailable()) return;
            leavePlayer(); toast("网盘连接已更新，请重新打开目录"); return;
        }
        try {
            JSONObject current = store.resource(child, playingCourse.optString("id"));
            if (current.optBoolean("pinned") && current.optString("kind").equals(playingCourse.optString("kind"))
                    && current.optString("url").equals(playingCourse.optString("url"))
                    && String.valueOf(current.optJSONObject("baidu")).equals(String.valueOf(playingCourse.optJSONObject("baidu")))) return;
        } catch (IllegalArgumentException ignored) { }
        leavePlayer(); toast("家长更新了这份资源，请重新打开");
    }

    private void leavePlayer() { stopVideo(); page = driveCourse == null ? "home" : "drive"; render(); }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 鼠标会暂时清除电视焦点，重新按方向键时回到上次位置，不让系统随机跳到页尾。
        if (video == null && root != null && !root.hasFocus()
                && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            String focus = page.equals("drive") ? driveFocusTag : homeFocusTag;
            View target = focus.isEmpty() ? firstFocus : root.findViewWithTag(focus);
            if (target == null) target = firstFocus;
            if (target != null) { target.requestFocusFromTouch(); return true; }
        }
        if (video != null && (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE)) {
            if (event.getRepeatCount() == 0) togglePlayback();
            return true;
        }
        if (video != null && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            boolean hidden = playerControls != null && playerControls.getVisibility() != View.VISIBLE;
            showPlayerControls(); if (hidden) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (video != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) showPlayerControls();
        return super.dispatchTouchEvent(event);
    }

    @Override public void onBackPressed() {
        if (video != null) { leavePlayer(); return; }
        if ("drive".equals(page)) { backDrive(); return; }
        // 默认桌面返回键留在家庭首页，不跳回已被替换的系统桌面。
        toast("已在果然 TV 桌面");
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setClipChildren(false); view.setClipToPadding(false);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setClipChildren(false); view.setClipToPadding(false);
        return view;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(size)); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    private Button button(String title, String tag, boolean primary, final Runnable action) {
        Button button = new Button(this);
        button.setText(title); button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, s(18));
        button.setTextColor(primary ? accentInk : ink);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(s(19), s(9), s(19), s(9));
        button.setMinHeight(s(50)); button.setMinimumHeight(s(50));
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        focusable(button, tag, primary ? accent : surface, page.equals("player") || primary ? c("#FFFFFF") : accent, 10);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { action.run(); }
                catch (RuntimeException error) { toast("操作暂时无法完成，请稍后再试"); }
            }
        });
        return button;
    }

    private Button plainButton(String title, String tag, Runnable action) {
        Button view = button(title, tag, false, action);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setOnFocusChangeListener((v, focused) -> v.setBackground(shape(focused ? c("#404651") : Color.TRANSPARENT, 0, 6)));
        return view;
    }

    /** 目录和播放页复用悬浮导航的同一箭头，文字与图标共用一个点击、遥控焦点区域。 */
    private View backButton(String tag, Runnable action) {
        LinearLayout back = row(); back.setPadding(s(5), 0, s(13), 0);
        back.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        back.addView(new ControlIconView(this, "back", "返回"), new LinearLayout.LayoutParams(s(32), s(50)));
        back.addView(text("返回", 18, Color.WHITE, false));
        focusable(back, tag, Color.TRANSPARENT, Color.WHITE, 6);
        back.setOnFocusChangeListener((v, focused) -> v.setBackground(shape(focused ? c("#404651") : Color.TRANSPARENT, 0, 6)));
        back.setOnClickListener(v -> action.run());
        return back;
    }

    private void focusable(final View view, String tag, final int base, final int ring, final int radius) {
        // 遥控器保留焦点导航，鼠标/触控直接点击；触控模式抢焦点会把第一次点击吞掉。
        view.setTag(tag); view.setFocusable(true); view.setFocusableInTouchMode(false); view.setClickable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
        view.setBackground(shape(base, 0, radius));
        view.setElevation(s(1));
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean focused) {
                v.setBackground(shape(base, focused ? ring : 0, radius));
                // 按钮只变焦点描边，不放大越过父布局边缘，避免圆角和白框被裁成缺口。
                float scale = focused && !(v instanceof Button) ? 1.025f : 1f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(110).start();
                v.setElevation(s(focused ? 7 : 1));
                String tag = String.valueOf(v.getTag());
                // 读取目录时不把临时「返回」焦点记住，加载完直接落到文件列表。
                if (focused && page.equals("drive") && !driveLoading && tag.startsWith("drive-")) driveFocusTag = tag;
                if (focused && page.equals("home") && (tag.startsWith("ready-") || tag.startsWith("pending-") || tag.startsWith("app-") || tag.startsWith("recent-") || tag.startsWith("root-"))) {
                    final ScrollView scroll = homeScroll;
                    v.post(() -> {
                        if (scroll != homeScroll || !v.hasFocus() || !page.equals("home")) return;
                        // 焦点行滚到舒适位置，完整保留片单标题和焦点边缘，不贴着屏幕底部。
                        Rect bounds = new Rect(); v.getDrawingRect(bounds);
                        scroll.offsetDescendantRectToMyCoords(v, bounds);
                        scroll.smoothScrollTo(0, Math.max(0, bounds.top - s(115)));
                    });
                }
            }
        });
        if (firstFocus == null) firstFocus = view;
    }

    /** 系统设置只从已通过 PIN 的家长工具进入，避免孩子从首页直接离开果然 TV。 */
    private void systemSettings() {
        if (parentToolsDialog != null) parentToolsDialog.dismiss();
        if (systemSettingsDialog != null && systemSettingsDialog.isShowing()) return;
        LinearLayout panel = column();
        panel.setPadding(s(28), s(14), s(28), s(18));
        panel.addView(text("选择要调整的项目，可点悬浮「返回」退回上一页，或点「首页」回到果然 TV。", 17, muted, false)); gap(panel, 18);
        String[][] items = {
                {"网络与 Wi‑Fi", "settings-network", Settings.ACTION_WIFI_SETTINGS},
                {"蓝牙与遥控器", "settings-bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS},
                {"显示与屏幕", "settings-display", Settings.ACTION_DISPLAY_SETTINGS},
                {"声音", "settings-sound", Settings.ACTION_SOUND_SETTINGS},
                {"应用管理", "settings-apps", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS},
                {"存储空间", "settings-storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS},
                {"日期与时间", "settings-date", Settings.ACTION_DATE_SETTINGS},
                {"选择默认桌面", "settings-home", Settings.ACTION_HOME_SETTINGS},
                {"关于盒子", "settings-about", Settings.ACTION_DEVICE_INFO_SETTINGS},
                {"完整系统设置", "settings-all", Settings.ACTION_SETTINGS}
        };
        // 两列固定网格适合电视遥控器，也避免纵向长菜单遮住底部入口。
        for (int i = 0; i < items.length; i += 2) {
            LinearLayout row = row();
            for (int j = i; j < Math.min(i + 2, items.length); j++) {
                final String[] item = items[j];
                Button entry = button(item[0], item[1], false, () -> openSystemSetting(item[0], item[2]));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, s(54), 1);
                if (j > i) lp.leftMargin = s(12);
                row.addView(entry, lp);
            }
            panel.addView(row); gap(panel, 10);
        }
        systemSettingsDialog = new AlertDialog.Builder(this).setTitle("果然 TV · 系统设置").setView(panel)
                .setNegativeButton("返回家长工具", (dialog, which) -> parentTools()).create();
        systemSettingsDialog.setOnShowListener(dialog -> {
            Button first = panel.findViewWithTag("settings-network");
            if (first != null) first.requestFocus();
        });
        systemSettingsDialog.show();
        if (systemSettingsDialog.getWindow() != null) systemSettingsDialog.getWindow().setLayout(s(760), -2);
    }

    private void openSystemSetting(String title, String action) {
        try {
            Intent intent = new Intent(action);
            // miniH6 同时给 HDR 和屏幕页注册了显示入口；优先已存在的屏幕页，省去系统二选一。
            if (Settings.ACTION_DISPLAY_SETTINGS.equals(action)) {
                for (android.content.pm.ResolveInfo entry : getPackageManager().queryIntentActivities(intent, 0)) {
                    if ("com.android.settings.Settings$DisplaySettingsActivity".equals(entry.activityInfo.name)) {
                        intent.setClassName(entry.activityInfo.packageName, entry.activityInfo.name);
                        break;
                    }
                }
            }
            openExternalActivity(intent);
            // 成功离开才关闭菜单；不支持的入口提示后仍可用遥控器选择其他设置。
            if (systemSettingsDialog != null) systemSettingsDialog.dismiss();
        } catch (ActivityNotFoundException error) {
            new AlertDialog.Builder(this).setTitle("盒子不支持此入口")
                    .setMessage("当前系统没有提供“" + title + "”设置页面，请打开“完整系统设置”查看。")
                    .setPositiveButton("知道了", null).show();
        } catch (RuntimeException error) {
            toast("暂时无法打开" + title);
        }
    }

    private GradientDrawable shape(int base, int border, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(base); drawable.setCornerRadius(s(radius));
        if (border != 0) drawable.setStroke(s(3), border);
        return drawable;
    }

    /** 跨应用返回交给系统无障碍导航；所有外部入口共用同一开启提示。 */
    private void openExternalActivity(Intent intent) {
        if (!MouseNavigationService.isEnabled(this)) {
            pendingExternalIntent = intent;
            new AlertDialog.Builder(this).setTitle("开启鼠标「返回／首页」")
                    .setMessage("在系统无障碍设置中开启「果然 TV 鼠标导航」，网盘、文件管理器、播放器和设置里就会常驻返回与首页按钮。按住图标可以拖动；到点也会显示全盒子休息页。\n\n服务不读取屏幕内容或文件。")
                    .setPositiveButton("去开启", (dialog, which) -> {
                        try {
                            startActivityForResult(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), NAVIGATION_REQUEST);
                        } catch (RuntimeException error) { pendingExternalIntent = null; toast("请在系统无障碍设置中开启果然 TV 鼠标导航"); }
                    })
                    .setNeutralButton("先直接打开", (dialog, which) -> {
                        pendingExternalIntent = null; startActivity(intent);
                    })
                    .setNegativeButton("取消", (dialog, which) -> pendingExternalIntent = null).show();
            return;
        }
        startActivity(intent);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LOCAL_VIDEO_REQUEST) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            if (!"content".equals(uri.getScheme())) { toast("请使用系统文件选择器选择视频"); return; }
            try {
                // SAF 的持久权限只针对家长本次选中的视频，不申请整个存储的读取权限。
                if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0)
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { /* 部分厂商仅提供本次访问权限，仍可立即播放。 */ }
            String title = "本地视频";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) title = cursor.getString(0);
            } catch (RuntimeException ignored) { /* 文件名不可读时保留通用标题。 */ }
            if (activateChild("family")) {
                driveRequest++; driveCourse = null; driveListing = null; driveLoading = false;
                playVideo(localVideoResource(uri, title));
            }
            return;
        }
        if (requestCode != NAVIGATION_REQUEST || pendingExternalIntent == null) return;
        final Intent intent = pendingExternalIntent; pendingExternalIntent = null;
        if (MouseNavigationService.isEnabled(this)) {
            handler.post(() -> {
                try { openExternalActivity(intent); }
                catch (RuntimeException error) { toast("暂时无法打开，请重新选择"); }
            });
        } else toast("尚未开启鼠标导航；遥控器可按 Home 回到首页");
    }

    private void gap(LinearLayout target, int height) { target.addView(new View(this), new LinearLayout.LayoutParams(1, s(height))); }
    private void space(LinearLayout target, int width) { target.addView(new View(this), new LinearLayout.LayoutParams(s(width), 1)); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void showFatal(String message) {
        child = ""; page = "home"; palette(); firstFocus = null;
        LinearLayout panel = column();
        panel.setGravity(Gravity.CENTER); panel.setPadding(s(80), s(40), s(80), s(40)); panel.setBackgroundColor(background);
        panel.addView(text("果然 TV", 42, ink, true)); gap(panel, 28);
        TextView reason = text(message, 23, muted, false); reason.setGravity(Gravity.CENTER); panel.addView(reason);
        gap(panel, 28);
        panel.addView(button("退出", "exit", true, new Runnable() { public void run() { finish(); } }));
        setContentView(panel); if (firstFocus != null) firstFocus.requestFocus();
    }
}
