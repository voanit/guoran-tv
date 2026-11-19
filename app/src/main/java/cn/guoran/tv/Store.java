package cn.guoran.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 保存家长自定义片单及全盒子观看规则，所有读写在同一锁内完成。 */
public final class Store {
    /** 首次安装给家长使用的本地 PIN；解锁后可在电视端修改。 */
    private static final String DEFAULT_PARENT_PIN = "2580";
    private static Store instance;
    private final Context context;
    private final AtomicFile file;
    private JSONObject data;
    private int revision = 1;
    private String pairingCode;
    private Runnable listener;
    private long lastWatchSave;

    public static synchronized Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private Store(Context context) {
        this.context = context;
        file = new AtomicFile(new File(context.getFilesDir(), "family.json"));
        rotatePairingCode();
        try {
            if (file.getBaseFile().exists()) {
                FileInputStream input = file.openRead();
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (bytes.size() + n > 1024 * 1024) throw new IllegalStateException("设置文件过大");
                        bytes.write(buffer, 0, n);
                    }
                    data = new JSONObject(bytes.toString("UTF-8"));
                    if (data.getJSONArray("profiles").length() != 2) throw new IllegalStateException("孩子配置无效");
                } finally { input.close(); }
            } else {
                data = defaults();
                persist();
            }
            migrateParentPin();
            migrateFamilyApps();
            migrateCustomContent();
            migrateFamilyResources();
            migrateBoxSettings();
        } catch (Exception error) {
            // 已有配置损坏时停止写入，保留原文件，避免用默认值覆盖家庭资源。
            throw new IllegalStateException("无法读取果然 TV 设置，请保留数据后检查", error);
        }
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); }
        catch (JSONException e) { throw new IllegalStateException(e); }
        return result;
    }

    private JSONObject defaults() {
        JSONArray profiles = new JSONArray();
        // 仅保留旧数据结构所需的占位记录；首次安装不携带任何家庭成员或年龄。
        for (String id : new String[]{"brother", "sister"}) {
            JSONObject p = object("id", id, "name", "未命名片单", "space", "未命名片单", "dailyLimit", 30,
                    "usedSeconds", 0, "paused", false, "startTime", "00:00", "endTime", "00:00",
                    "usageDate", today(), "resources", new JSONArray());
            profiles.put(p);
        }
        return object("schemaVersion", 3, "customContentApplied", true, "activeChild", "sister", "parentPin", DEFAULT_PARENT_PIN,
                "allowedApps", new JSONArray(), "profiles", profiles, "playlists", new JSONArray());
    }

    /** 旧版按孩子保存的应用合并为家庭列表，保留任一孩子已开放的应用和全部课程配置。 */
    private void migrateFamilyApps() {
        if (data.has("allowedApps")) return;
        Set<String> allowed = new LinkedHashSet<>();
        JSONArray profiles = data.optJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject p = profiles.optJSONObject(i);
            JSONArray old = p.optJSONArray("allowedApps");
            if (old != null) for (int j = 0; j < old.length(); j++) allowed.add(old.optString(j));
            p.remove("allowedApps");
        }
        JSONArray apps = new JSONArray(); for (String name : allowed) apps.put(name);
        try { data.put("allowedApps", apps).put("schemaVersion", 2); persist(); }
        catch (JSONException error) { throw new IllegalStateException("家庭应用设置迁移失败", error); }
    }

    private void migrateParentPin() {
        String pin = data.optString("parentPin", "");
        if (!pin.matches("\\d{4,6}")) {
            try { data.put("parentPin", DEFAULT_PARENT_PIN); persist(); }
            catch (JSONException e) { throw new IllegalStateException("家长 PIN 设置无效", e); }
        }
    }

    /** 只移除旧版完整匹配的空示例；填写过地址、改名、换封面或隐藏过的资源一律保留。 */
    private void migrateCustomContent() throws JSONException {
        if (data.optBoolean("customContentApplied")) return;
        String[][] originals = {
                {"brother-blocks-0", "方块世界英语", "video", "blocks"},
                {"brother-dragon-1", "驯龙高手", "video", "dragon"},
                {"brother-space-2", "宇宙探索", "video", "space"},
                {"brother-code-3", "编程小项目", "video", "code"},
                {"brother-course-4", "英语阅读", "baidu", "course"},
                {"brother-course-5", "编程课程", "baidu", "course"},
                {"sister-frozen-0", "冰雪奇缘", "video", "frozen"},
                {"sister-sofia-1", "小公主苏菲亚", "video", "sofia"},
                {"sister-bluey-2", "布鲁伊", "video", "bluey"},
                {"sister-course-3", "自然拼读", "baidu", "course"},
                {"sister-course-4", "公主裙手工", "baidu", "course"}
        };
        for (String id : new String[]{"brother", "sister"}) {
            JSONObject p = profileRef(id);
            JSONArray previous = p.getJSONArray("resources"), kept = new JSONArray();
            for (int i = 0; i < previous.length(); i++) {
                JSONObject item = previous.getJSONObject(i);
                boolean unchanged = false;
                for (String[] original : originals) {
                    if (item.length() == 6 && original[0].equals(item.optString("id"))
                            && original[1].equals(item.optString("title")) && original[2].equals(item.optString("kind"))
                            && original[3].equals(item.optString("cover")) && item.optString("url").isEmpty()
                            && item.optBoolean("pinned")) { unchanged = true; break; }
                }
                if (!unchanged) kept.put(item);
            }
            p.put("resources", kept);
            if ((id.equals("brother") ? "探索基地" : "冰雪小屋").equals(p.optString("space")))
                p.put("space", p.optString("name") + "的片单");
        }
        data.put("schemaVersion", 3).put("customContentApplied", true);
        persist();
    }

    /** 共享栏目独立保存；旧课程保持原归属，避免把家长已选的孩子范围扩大。 */
    private void migrateFamilyResources() throws JSONException {
        if (data.has("familyResources")) return;
        data.put("familyResources", new JSONArray()).put("schemaVersion", 4);
        persist();
    }

    /** 旧空白兄妹栏目不生成新片单，已配置内容及家长改过的名称全部保留。 */
    private void migrateBoxSettings() throws JSONException {
        if (!data.has("playlists")) {
            JSONArray shelves = new JSONArray(), profiles = data.getJSONArray("profiles");
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = profiles.getJSONObject(i);
                String name = p.optString("space");
                if (p.getJSONArray("resources").length() == 0 && (name.equals("哥哥的片单") || name.equals("妹妹的片单"))) continue;
                shelves.put(object("id", p.optString("id"), "name", name, "resources", p.getJSONArray("resources")));
            }
            data.put("playlists", shelves);
        }
        if (!data.has("displayName")) data.put("displayName", "果然 TV");
        if (!data.has("watch")) data.put("watch", object("dailyLimit", 0, "startTime", "00:00", "endTime", "00:00",
                "sessionLimit", 30, "breakMinutes", 10, "usedSeconds", 0, "sessionSeconds", 0,
                "usageDate", today(), "restUntil", 0L, "idleSince", 0L, "overrideUntil", 0L, "extraSeconds", 0, "paused", false));
        if (data.optInt("schemaVersion") < 5) { data.put("schemaVersion", 5); persist(); }
    }

    public synchronized JSONArray playlists() {
        try { return new JSONArray(data.getJSONArray("playlists").toString()); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }

    public synchronized String displayName() { return data.optString("displayName"); }

    public synchronized String playlistName(String id) {
        return id.equals("family") ? "首页精选" : playlistRef(id).optString("name");
    }

    private JSONObject playlistRef(String id) {
        JSONArray shelves = data.optJSONArray("playlists");
        for (int i = 0; i < shelves.length(); i++) if (id.equals(shelves.optJSONObject(i).optString("id"))) return shelves.optJSONObject(i);
        throw new IllegalArgumentException("片单不存在");
    }

    public synchronized void savePlaylist(JSONObject body) {
        String name = body.optString("name").trim(), id = body.optString("id");
        if (name.isEmpty() || name.length() > 24) throw new IllegalArgumentException("片单名称须为 1–24 个字");
        JSONArray shelves = data.optJSONArray("playlists");
        try {
            if (id.isEmpty()) {
                if (shelves.length() >= 20) throw new IllegalArgumentException("最多创建 20 个片单");
                shelves.put(object("id", UUID.randomUUID().toString(), "name", name, "resources", new JSONArray()));
            } else playlistRef(id).put("name", name);
            changed();
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    /** 删除片单只移除分类，课程移回无标题的首页精选，不删除资源和封面。 */
    public synchronized void deletePlaylist(String id) {
        JSONObject shelf = playlistRef(id);
        JSONArray resources = shelf.optJSONArray("resources"), family = data.optJSONArray("familyResources");
        if (family.length() + resources.length() > 60) throw new IllegalArgumentException("请先移动部分资源，首页精选最多 60 项");
        JSONArray next = new JSONArray(), shelves = data.optJSONArray("playlists");
        for (int i = 0; i < shelves.length(); i++) if (!id.equals(shelves.optJSONObject(i).optString("id"))) next.put(shelves.optJSONObject(i));
        for (int i = 0; i < resources.length(); i++) family.put(resources.optJSONObject(i));
        try { data.put("playlists", next); changed(); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }

    public synchronized List<String> resourceOwners() {
        List<String> owners = new ArrayList<>(); owners.add("family");
        JSONArray shelves = data.optJSONArray("playlists");
        for (int i = 0; i < shelves.length(); i++) owners.add(shelves.optJSONObject(i).optString("id"));
        return owners;
    }

    public synchronized JSONArray resourceList(String owner) {
        try { return new JSONArray(resourceRefs(owner).toString()); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }

    private JSONArray resourceRefs(String owner) {
        return "family".equals(owner) ? data.optJSONArray("familyResources") : playlistRef(owner).optJSONArray("resources");
    }

    private void putResources(String owner, JSONArray list) throws JSONException {
        if ("family".equals(owner)) data.put("familyResources", list);
        else playlistRef(owner).put("resources", list);
    }

    public synchronized JSONObject resource(String owner, String id) {
        JSONArray list = resourceRefs(owner);
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (id.equals(item.optString("id"))) return copy(item);
        }
        throw new IllegalArgumentException("资源不存在");
    }

    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    private JSONObject profileRef(String id) {
        JSONArray profiles = data.optJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject p = profiles.optJSONObject(i);
            if (id.equals(p.optString("id"))) return p;
        }
        throw new IllegalArgumentException("孩子不存在");
    }

    private void resetDay(JSONObject p) {
        String date = today();
        if (!date.equals(p.optString("usageDate"))) {
            try { p.put("usageDate", date); p.put("usedSeconds", 0); changed(); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
    }

    public synchronized JSONObject profile(String id) {
        ensureDefaultBaiduAccess();
        JSONObject p = profileRef(id); resetDay(p); return copy(p);
    }

    public synchronized JSONObject snapshot() {
        resetWatchDay();
        ensureDefaultBaiduAccess();
        JSONObject snapshot = copy(data);
        snapshot.remove("parentPin"); snapshot.remove("profiles"); snapshot.remove("activeChild");
        try {
            snapshot.put("watch", watchStatus());
            snapshot.put("revision", revision).put("serverUrl", serverUrl()).put("apps", installedApps());
            snapshot.put("capabilities", object("silentInstall", false, "externalAppLimits", MouseNavigationService.isEnabled(context)));
        } catch (JSONException e) { throw new IllegalStateException(e); }
        return snapshot;
    }

    public synchronized int revision() { return revision; }
    public synchronized String activeChild() { return data.optString("activeChild", "sister"); }
    public synchronized void setChangeListener(Runnable callback) { listener = callback; }

    public synchronized void selectChild(String id) {
        if (!id.equals("family")) playlistRef(id);
        try { data.put("activeChild", id); changed(); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private static void time(String value) {
        if (!value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("时间格式须为 HH:mm");
    }

    public synchronized void updateProfile(JSONObject body) {
        JSONObject p = copy(profileRef(body.optString("id")));
        try {
            if (body.has("space")) {
                String value = body.getString("space").trim();
                if (value.isEmpty() || value.length() > 24) throw new IllegalArgumentException("栏目名称须为 1–24 个字");
                p.put("space", value);
            }
            if (body.has("dailyLimit")) {
                int value = body.getInt("dailyLimit");
                if (value < 5 || value > 180) throw new IllegalArgumentException("每日时长须为 5–180 分钟");
                p.put("dailyLimit", value);
            }
            if (body.has("paused")) p.put("paused", body.getBoolean("paused"));
            for (String key : new String[]{"startTime", "endTime"}) {
                if (body.has(key)) { String value = body.getString(key); time(value); p.put(key, value); }
            }
            if (p.getString("startTime").equals(p.getString("endTime"))) throw new IllegalArgumentException("开始和结束时间不能相同");
            replaceProfile(p);
        } catch (JSONException e) { throw new IllegalArgumentException("观看设置字段无效"); }
    }

    private void replaceProfile(JSONObject profile) {
        JSONArray profiles = data.optJSONArray("profiles");
        try {
            for (int i = 0; i < profiles.length(); i++) {
                if (profiles.getJSONObject(i).getString("id").equals(profile.getString("id"))) {
                    profiles.put(i, profile); changed(); return;
                }
            }
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized void addExtraTime(String child, int minutes) {
        if (minutes != 5) throw new IllegalArgumentException("每次可增加 5 分钟");
        resetWatchDay(); JSONObject p = data.optJSONObject("watch");
        try { p.put("extraSeconds", Math.min(24 * 3600, p.optInt("extraSeconds") + minutes * 60)); changed(); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized void addWatchSeconds(String child, int seconds) {
        // 计时已统一交给盒子服务，播放器不再按片单重复累计。
    }

    public synchronized boolean canWatch(String child) { return blockedReason(child).isEmpty(); }

    public synchronized String blockedReason(String child) {
        resetWatchDay(); JSONObject p = data.optJSONObject("watch");
        long now = System.currentTimeMillis();
        if (p.optLong("overrideUntil") > now) return "";
        if (p.optBoolean("paused")) return "家长暂停了观看，先休息一下吧。";
        if (p.optInt("dailyLimit") > 0 && p.optInt("usedSeconds") >= p.optInt("dailyLimit") * 60 + p.optInt("extraSeconds"))
            return "今天的电视时间到啦，明天再继续。";
        String current = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        String start = p.optString("startTime"), end = p.optString("endTime");
        boolean inWindow = start.equals(end) || (start.compareTo(end) < 0 ? current.compareTo(start) >= 0 && current.compareTo(end) < 0
                : current.compareTo(start) >= 0 || current.compareTo(end) < 0);
        if (!inWindow) return "现在是休息时间，可用时段为 " + start + "–" + end + "。";
        return p.optLong("restUntil") > now ? "连续使用时间到啦，休息一下眼睛。" : "";
    }

    private void resetWatchDay() {
        JSONObject p = data.optJSONObject("watch"); if (p == null) return;
        try {
            if (!today().equals(p.optString("usageDate"))) {
                p.put("usageDate", today()).put("usedSeconds", 0).put("extraSeconds", 0); persist();
            }
            if (p.optLong("restUntil") > 0 && p.optLong("restUntil") <= System.currentTimeMillis())
                p.put("restUntil", 0L).put("sessionSeconds", 0);
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    public synchronized JSONObject watchStatus() {
        resetWatchDay(); JSONObject result = copy(data.optJSONObject("watch"));
        try { result.put("blockedReason", blockedReason("")).put("restRemaining", Math.max(0, (result.optLong("restUntil") - System.currentTimeMillis() + 999) / 1000)); }
        catch (JSONException error) { throw new IllegalStateException(error); }
        return result;
    }

    public synchronized void updateBox(JSONObject body) {
        JSONObject watch = copy(data.optJSONObject("watch")); String name = data.optString("displayName");
        try {
            if (body.has("displayName")) {
                name = body.getString("displayName").trim();
                if (name.isEmpty() || name.length() > 24) throw new IllegalArgumentException("桌面名称须为 1–24 个字");
            }
            JSONObject input = body.optJSONObject("watch");
            if (input != null) {
                for (String key : new String[]{"dailyLimit", "sessionLimit", "breakMinutes"}) if (input.has(key)) {
                    Object supplied = input.get(key);
                    if (!(supplied instanceof Number) || ((Number) supplied).doubleValue() != ((Number) supplied).intValue())
                        throw new IllegalArgumentException("时长须为整数分钟");
                    int value = input.getInt(key), minimum = key.equals("dailyLimit") ? 0 : 1;
                    if (value < minimum || value > (key.equals("dailyLimit") ? 1440 : 180)) throw new IllegalArgumentException("时长超出可配置范围");
                    watch.put(key, value);
                }
                for (String key : new String[]{"startTime", "endTime"}) if (input.has(key)) {
                    time(input.getString(key)); watch.put(key, input.getString(key));
                }
                if (input.has("paused")) {
                    boolean paused = input.getBoolean("paused");
                    watch.put("paused", paused);
                    // 手机重新暂停时立即收回临时解锁，避免孩子还能继续使用十分钟。
                    if (paused) watch.put("overrideUntil", 0L);
                }
            }
            data.put("displayName", name).put("watch", watch); changed();
        } catch (JSONException error) { throw new IllegalArgumentException("盒子设置字段无效"); }
    }

    /** 盒子服务按亮屏使用计时，跨片单、网盘和播放器共享额度；关屏休息同样有效。 */
    public synchronized void tickBoxUsage(boolean interactive, int seconds) {
        resetWatchDay(); JSONObject p = data.optJSONObject("watch"); long now = System.currentTimeMillis();
        try {
            long previousRest = p.optLong("restUntil");
            if (!interactive) {
                if (p.optLong("idleSince") == 0) p.put("idleSince", now);
                if (now - p.optLong("idleSince") >= p.optInt("breakMinutes") * 60000L) p.put("sessionSeconds", 0).put("restUntil", 0L);
            } else {
                if (p.optLong("idleSince") > 0 && now - p.optLong("idleSince") >= p.optInt("breakMinutes") * 60000L)
                    p.put("sessionSeconds", 0).put("restUntil", 0L);
                p.put("idleSince", 0L);
                if (blockedReason("").isEmpty() && seconds > 0 && seconds <= 5) {
                    p.put("usedSeconds", Math.min(86400, p.optInt("usedSeconds") + seconds));
                    p.put("sessionSeconds", p.optInt("sessionSeconds") + seconds);
                    if (p.optInt("sessionSeconds") >= p.optInt("sessionLimit") * 60 && p.optLong("restUntil") == 0
                            && p.optLong("overrideUntil") <= now) p.put("restUntil", now + p.optInt("breakMinutes") * 60000L);
                }
            }
            if (now - lastWatchSave > 10000 || p.optLong("restUntil") != previousRest) { persist(); lastWatchSave = now; }
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    public synchronized void unlockBox(String pin) {
        if (!verifyParentPin(pin)) throw new IllegalArgumentException("家长 PIN 不正确");
        try { data.optJSONObject("watch").put("overrideUntil", System.currentTimeMillis() + 10 * 60000L).put("restUntil", 0L).put("sessionSeconds", 0); changed(); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }

    public synchronized void saveResource(JSONObject body) {
        String owner = body.optString("child", "family"), from = body.optString("fromChild", owner);
        String title = body.optString("title").trim(), kind = body.optString("kind"), url = body.optString("url").trim();
        if (title.isEmpty() || title.length() > 40) throw new IllegalArgumentException("资源名称须为 1–40 个字");
        if (!kind.equals("video") && !kind.equals("baidu") && !kind.equals("baiduDrive") && !kind.equals("link"))
            throw new IllegalArgumentException("资源类型无效");
        if (kind.equals("baiduDrive")) {
            JSONObject binding = body.optJSONObject("baidu");
            if (binding == null) throw new IllegalArgumentException("请先选择自家网盘中的文件夹或视频");
            BaiduDrive.validateBinding(binding);
            url = "";
        } else if (!url.isEmpty()) {
            try {
                URI parsed = new URI(url);
                if (!("https".equals(parsed.getScheme()) || "http".equals(parsed.getScheme())) || parsed.getHost() == null || parsed.getUserInfo() != null)
                    throw new IllegalArgumentException("请填写完整的 http 或 https 地址");
                if (kind.equals("baidu") && !"pan.baidu.com".equals(parsed.getHost())) throw new IllegalArgumentException("网盘课程请填写 pan.baidu.com 分享链接");
            } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("资源地址无效"); }
            if (url.length() > 4096) throw new IllegalArgumentException("资源地址过长");
        }
        String cover = body.optString("cover", "course");
        boolean customImage = body.has("coverImage");
        if (customImage ? !cover.equals("custom") : !cover.matches("frozen|sofia|bluey|blocks|dragon|space|code|course|custom-[a-f0-9]{64}"))
            throw new IllegalArgumentException("封面类型无效");
        JSONArray target = resourceList(owner), previous = resourceList(from);
        String id = body.optString("id", "");
        int index = -1;
        for (int i = 0; i < previous.length(); i++) if (previous.optJSONObject(i).optString("id").equals(id)) index = i;
        if (!id.isEmpty() && index == -1) throw new IllegalArgumentException("原栏目中的资源不存在");
        if ((index < 0 || !owner.equals(from)) && target.length() >= 60) throw new IllegalArgumentException("每个栏目最多 60 份资源");
        if (customImage) cover = saveCover(body.optString("coverImage"));
        else if (cover.startsWith("custom-") && !coverFile(cover).isFile()) throw new IllegalArgumentException("封面图片不存在，请重新选择图片");
        JSONObject item = index < 0 ? new JSONObject() : copy(previous.optJSONObject(index));
        String oldCover = item.optString("cover");
        if (id.isEmpty()) id = UUID.randomUUID().toString();
        try {
            item.put("id", id).put("title", title).put("kind", kind).put("url", url).put("cover", cover)
                    .put("pinned", body.optBoolean("pinned", true));
            if (kind.equals("baiduDrive")) item.put("baidu", copy(body.getJSONObject("baidu")));
            else item.remove("baidu");
            if (owner.equals(from) && index >= 0) target.put(index, item);
            else {
                JSONArray updated = new JSONArray(); updated.put(item);
                for (int i = 0; i < target.length(); i++) updated.put(target.get(i));
                target = updated;
            }
            // 调整所属片单时移动同一资源，不复制课程，也不改变封面标识。
            if (!owner.equals(from) && index >= 0) {
                JSONArray kept = new JSONArray();
                for (int i = 0; i < previous.length(); i++) if (i != index) kept.put(previous.get(i));
                putResources(from, kept);
            }
            putResources(owner, target); changed();
            if (!oldCover.equals(cover)) deleteUnusedCover(oldCover);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    /** 手机裁剪为横版 JPEG 后随内容保存，图片单独落盘，不把大图写进家庭配置。 */
    private String saveCover(String image) {
        if (!image.startsWith("data:image/jpeg;base64,") || image.length() > 350000)
            throw new IllegalArgumentException("请使用不超过 256KB 的 JPEG 封面");
        try {
            String encoded = image.substring("data:image/jpeg;base64,".length());
            if (!encoded.matches("[A-Za-z0-9+/]+={0,2}")) throw new IllegalArgumentException("封面图片编码无效");
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (bytes.length == 0 || bytes.length > 256 * 1024) throw new IllegalArgumentException("封面不能超过 256KB");
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (!"image/jpeg".equals(options.outMimeType) || options.outWidth < 32 || options.outHeight < 32
                    || options.outWidth > 1920 || options.outHeight > 1080)
                throw new IllegalArgumentException("封面须为 32–1920 × 32–1080 像素的 JPEG 图片");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder key = new StringBuilder("custom-");
            for (byte value : digest) key.append(String.format(Locale.US, "%02x", value & 255));
            File destination = coverFile(key.toString());
            if (!destination.getParentFile().isDirectory() && !destination.getParentFile().mkdirs())
                throw new IllegalStateException("无法创建封面目录");
            if (!destination.isFile()) {
                AtomicFile cover = new AtomicFile(destination);
                FileOutputStream output = null;
                try { output = cover.startWrite(); output.write(bytes); cover.finishWrite(output); }
                catch (Exception error) { if (output != null) cover.failWrite(output); throw error; }
            }
            return key.toString();
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("保存封面失败，请检查盒子存储空间", error); }
    }

    private File coverFile(String cover) {
        if (!cover.matches("custom-[a-f0-9]{64}")) throw new IllegalArgumentException("封面标识无效");
        return new File(new File(context.getFilesDir(), "covers"), cover + ".jpg");
    }

    /** 家长预览仍经过配对鉴权，不公开家庭上传图片的下载地址。 */
    public synchronized JSONObject coverImage(String cover) {
        File source = coverFile(cover);
        if (!source.isFile() || source.length() > 256 * 1024) throw new IllegalArgumentException("封面图片不存在");
        try (FileInputStream input = new FileInputStream(source); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return object("image", "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
        } catch (java.io.IOException error) { throw new IllegalStateException("读取封面失败", error); }
    }

    private void deleteUnusedCover(String cover) {
        if (!cover.matches("custom-[a-f0-9]{64}")) return;
        for (String id : resourceOwners()) {
            JSONArray resources = resourceRefs(id);
            for (int i = 0; i < resources.length(); i++) if (cover.equals(resources.optJSONObject(i).optString("cover"))) return;
        }
        coverFile(cover).delete();
    }

    public synchronized void deleteResource(JSONObject body) {
        String owner = body.optString("child");
        JSONArray old = resourceRefs(owner), next = new JSONArray();
        boolean found = false;
        String removedCover = "";
        for (int i = 0; i < old.length(); i++) {
            JSONObject r = old.optJSONObject(i);
            if (r.optString("id").equals(body.optString("id"))) { found = true; removedCover = r.optString("cover"); }
            else next.put(r);
        }
        if (!found) throw new IllegalArgumentException("资源不存在");
        try { putResources(owner, next); changed(); deleteUnusedCover(removedCover); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized void reorderResources(JSONObject body) {
        String owner = body.optString("child");
        JSONArray old = resourceRefs(owner), ids = body.optJSONArray("ids"), next = new JSONArray();
        if (ids == null || ids.length() != old.length()) throw new IllegalArgumentException("排序须包含这个栏目的全部资源");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i); JSONObject found = null;
            if (!seen.add(id)) throw new IllegalArgumentException("排序包含重复资源");
            for (int j = 0; j < old.length(); j++) if (id.equals(old.optJSONObject(j).optString("id"))) found = old.optJSONObject(j);
            if (found == null) throw new IllegalArgumentException("排序包含其他栏目或不存在的资源");
            next.put(found);
        }
        try { putResources(owner, next); changed(); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized void setAppAllowed(JSONObject body) {
        String packageName = body.optString("packageName");
        if ("com.dangbeimarket".equals(packageName)) throw new IllegalArgumentException("当贝市场请从电视端家长工具打开");
        boolean installed = false;
        JSONArray apps = installedApps();
        for (int i = 0; i < apps.length(); i++) if (packageName.equals(apps.optJSONObject(i).optString("packageName"))) installed = true;
        if (!installed) throw new IllegalArgumentException("应用未安装或没有可打开的界面");
        JSONArray old = data.optJSONArray("allowedApps"), next = new JSONArray();
        for (int i = 0; i < old.length(); i++) if (!packageName.equals(old.optString(i))) next.put(old.optString(i));
        if (body.optBoolean("allowed")) next.put(packageName);
        try { data.put("allowedApps", next); changed(); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized boolean isAllowedApp(String packageName) {
        ensureDefaultBaiduAccess();
        JSONArray allowed = data.optJSONArray("allowedApps");
        for (int i = 0; i < allowed.length(); i++) if (allowed.optString(i).equals(packageName)) return true;
        return false;
    }

    public synchronized boolean verifyParentPin(String pin) {
        return pin != null && pin.matches("\\d{4,6}") && pin.equals(data.optString("parentPin"));
    }

    public synchronized void setParentPin(String pin) {
        if (pin == null || !pin.matches("\\d{4,6}")) throw new IllegalArgumentException("家长 PIN 须为 4–6 位数字");
        try { data.put("parentPin", pin); changed(); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized String parentPinHint() {
        return data.optString("parentPin").equals(DEFAULT_PARENT_PIN)
                ? "PIN 由家长保管；进入家长工具后可以修改"
                : "家长 PIN 已设置，可在家长工具中修改";
    }

    /** 百度网盘实际安装后，首次默认加入家庭应用列表，之后尊重家长的关闭设置。 */
    private void ensureDefaultBaiduAccess() {
        if (data.optBoolean("baiduDefaultApplied") || !isPackageInstalled("com.baidu.netdisk.tv")) return;
        JSONArray apps = data.optJSONArray("allowedApps");
        boolean present = false;
        for (int j = 0; j < apps.length(); j++) if ("com.baidu.netdisk.tv".equals(apps.optString(j))) present = true;
        if (!present) apps.put("com.baidu.netdisk.tv");
        try { data.put("baiduDefaultApplied", true); changed(); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private boolean isPackageInstalled(String packageName) {
        try { context.getPackageManager().getPackageInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    private JSONArray installedApps() {
        PackageManager pm = context.getPackageManager();
        Set<String> packages = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo r : pm.queryIntentActivities(intent, 0)) packages.add(r.activityInfo.packageName);
        }
        List<JSONObject> result = new ArrayList<>();
        for (String name : packages) {
            if (name.equals(context.getPackageName()) || name.equals("com.android.settings") || name.equals("com.android.packageinstaller") || name.contains("mboxlauncher")) continue;
            try {
                PackageInfo info = pm.getPackageInfo(name, 0);
                if (!info.applicationInfo.enabled) continue;
                result.add(object("packageName", name, "label", String.valueOf(pm.getApplicationLabel(info.applicationInfo)), "versionName", info.versionName == null ? "" : info.versionName));
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        Collections.sort(result, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) { return a.optString("label").compareToIgnoreCase(b.optString("label")); }
        });
        JSONArray array = new JSONArray(); for (JSONObject value : result) array.put(value); return array;
    }

    public synchronized String pairingCode() { return pairingCode; }
    public synchronized void rotatePairingCode() { pairingCode = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000)); }

    public String serverUrl() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return "http://" + address.getHostAddress() + ":8787";
                }
            }
        } catch (Exception ignored) { }
        return "网络未连接";
    }

    private JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private void changed() {
        persist(); revision++;
        if (listener != null) listener.run();
    }

    private void persist() {
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(data.toString().getBytes("UTF-8")); file.finishWrite(output);
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("保存设置失败，请检查盒子存储空间", e);
        }
    }
}
