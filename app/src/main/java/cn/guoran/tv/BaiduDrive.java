package cn.guoran.tv;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 仅接入用户授权的应用目录；不调用分享解析、付费流量或转码接口。 */
public final class BaiduDrive {
    private static BaiduDrive instance;
    private final AtomicFile file;
    private JSONObject account = new JSONObject();
    private JSONObject pending;
    private long pollAt, authorizationExpires;
    private int pollInterval;
    private final Map<String, Stream> streams = new HashMap<>();
    private final Map<String, String> thumbnails = new HashMap<>();

    public static synchronized BaiduDrive get(Context context) {
        if (instance == null) instance = new BaiduDrive(context.getApplicationContext());
        return instance;
    }

    private BaiduDrive(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "baidu-private.json"));
        if (!file.getBaseFile().exists()) return;
        try (FileInputStream input = file.openRead()) {
            account = new JSONObject(read(input, 32768));
        } catch (Exception error) { throw new IllegalStateException("网盘连接设置无法读取，请保留应用数据后检查"); }
    }

    private static JSONObject json(Object... fields) {
        JSONObject value = new JSONObject();
        try { for (int i = 0; i < fields.length; i += 2) value.put((String) fields[i], fields[i + 1]); }
        catch (JSONException error) { throw new IllegalStateException(error); }
        return value;
    }

    /** 配置及令牌只在应用私有文件中保存；家长接口永不回传密钥或令牌。 */
    private void persist() {
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(account.toString().getBytes("UTF-8")); file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("保存网盘连接设置失败");
        }
    }

    public synchronized JSONObject status() {
        boolean configured = !account.optString("appKey").isEmpty();
        boolean connected = !account.optString("accessToken").isEmpty();
        return json("configured", configured, "connected", connected, "root", account.optString("root"),
                "appName", account.optString("appName"), "authorizing", pending != null && authorizationExpires > System.currentTimeMillis());
    }

    public synchronized void configure(JSONObject body) {
        String name = body.optString("appName").trim(), key = body.optString("appKey").trim(), secret = body.optString("secretKey").trim();
        if (name.isEmpty() || name.length() > 64 || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..") || name.matches("(?s).*[\\p{Cntrl}].*"))
            throw new IllegalArgumentException("请填写百度应用的准确名称，不含斜杠");
        if (!key.matches("[A-Za-z0-9_-]{8,128}") || !secret.matches("[A-Za-z0-9_-]{8,256}"))
            throw new IllegalArgumentException("请填写应用控制台中的 AppKey 和 SecretKey");
        if (name.equals(account.optString("appName")) && key.equals(account.optString("appKey")) && secret.equals(account.optString("secretKey"))) return;
        account = json("appName", name, "root", "/apps/" + name, "appKey", key, "secretKey", secret);
        pending = null; streams.clear(); thumbnails.clear(); persist();
    }

    public synchronized JSONObject authorize() {
        if (!status().optBoolean("configured")) throw new IllegalArgumentException("请先配置自己的百度网盘应用");
        JSONObject result = request("https://openapi.baidu.com/oauth/2.0/device/code", "response_type", "device_code",
                "client_id", account.optString("appKey"), "scope", "basic,netdisk");
        if (!result.has("device_code")) throw new IllegalArgumentException("百度未能创建授权，请检查应用配置");
        pending = result;
        pollInterval = Math.max(6, result.optInt("interval", 5));
        pollAt = System.currentTimeMillis() + pollInterval * 1000L;
        authorizationExpires = System.currentTimeMillis() + result.optInt("expires_in", 300) * 1000L;
        // 用户码允许展示，设备码和 token 不离开盒子。只打开官方授权页面。
        return json("verificationUrl", "https://openapi.baidu.com/device?display=mobile&code=" + encode(result.optString("user_code")),
                "userCode", result.optString("user_code"), "expiresIn", result.optInt("expires_in", 300), "interval", pollInterval);
    }

    public synchronized JSONObject pollAuthorization() {
        if (pending == null) return status();
        long now = System.currentTimeMillis();
        if (now >= authorizationExpires) { pending = null; throw new IllegalArgumentException("百度授权已过期，请重新授权"); }
        if (now < pollAt) return status();
        pollAt = now + pollInterval * 1000L;
        JSONObject result = request("https://openapi.baidu.com/oauth/2.0/token", "grant_type", "device_token",
                "code", pending.optString("device_code"), "client_id", account.optString("appKey"), "client_secret", account.optString("secretKey"));
        String error = result.optString("error");
        if (error.equals("authorization_pending")) return status();
        if (error.equals("slow_down")) { pollInterval += 5; pollAt = now + pollInterval * 1000L; return status(); }
        if (!result.has("access_token")) { pending = null; throw new IllegalArgumentException("百度授权未完成，请重新发起并在官方页面允许访问"); }
        saveTokens(result); pending = null; streams.clear(); return status();
    }

    public synchronized void disconnect() {
        account.remove("accessToken"); account.remove("refreshToken"); account.remove("expiresAt");
        pending = null; streams.clear(); thumbnails.clear(); persist();
    }

    private void saveTokens(JSONObject result) {
        try {
            account.put("accessToken", result.getString("access_token")).put("refreshToken", result.getString("refresh_token"))
                    .put("expiresAt", System.currentTimeMillis() + result.getLong("expires_in") * 1000L);
            persist();
        } catch (JSONException error) { throw new IllegalArgumentException("百度授权返回不完整，请重新授权"); }
    }

    private synchronized String accessToken() {
        if (account.optString("accessToken").isEmpty()) throw new IllegalArgumentException("请家长先在手机管理页连接百度网盘");
        if (account.optLong("expiresAt") > System.currentTimeMillis() + 60000) return account.optString("accessToken");
        String refresh = account.optString("refreshToken");
        // 百度刷新令牌仅能使用一次。失败后要求重新授权，不能用旧令牌反复请求。
        account.remove("refreshToken"); account.remove("accessToken"); persist();
        JSONObject result = request("https://openapi.baidu.com/oauth/2.0/token", "grant_type", "refresh_token", "refresh_token", refresh,
                "client_id", account.optString("appKey"), "client_secret", account.optString("secretKey"));
        if (!result.has("access_token")) throw new IllegalArgumentException("网盘登录已过期，请家长重新授权");
        saveTokens(result); return account.optString("accessToken");
    }

    public static void validateBinding(JSONObject binding) {
        String path = binding.optString("path"), id = binding.optString("fsId");
        validPath(path);
        if (!(binding.opt("fsId") instanceof String) || !id.matches("[1-9][0-9]{0,19}") || !(binding.opt("isDir") instanceof Boolean)
                || (!binding.optBoolean("isDir") && binding.optInt("category") != 1))
            throw new IllegalArgumentException("请选择网盘中的文件夹或视频");
    }

    private static void validPath(String path) {
        if (!path.startsWith("/") || path.length() > 4096 || path.contains("\\") || path.contains("//") || path.matches("(?s).*[\\p{Cntrl}].*")
                || (path.length() > 1 && path.endsWith("/"))) throw new IllegalArgumentException("网盘目录路径无效");
        for (String part : path.split("/")) if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException("网盘目录路径无效");
    }

    static boolean within(String path, String root) { return path.equals(root) || path.startsWith(root + "/"); }

    private synchronized String requirePath(String path) {
        validPath(path);
        String root = account.optString("root");
        if (root.isEmpty() || !within(path, root)) throw new IllegalArgumentException("只能浏览本应用授权的网盘目录");
        return path;
    }

    public JSONObject list(String path, int start) {
        if (path.isEmpty()) path = status().optString("root");
        requirePath(path);
        if (start < 0 || start > 100000) throw new IllegalArgumentException("目录页码无效");
        JSONObject result = request("https://pan.baidu.com/rest/2.0/xpan/file", "method", "list", "access_token", accessToken(),
                "dir", path, "order", "name", "desc", "0", "start", String.valueOf(start), "limit", "100", "folder", "0");
        checkError(result);
        try {
            // 官方同时记录了顶层 list 与 data.list 两种响应结构。
            JSONArray source = result.has("list") ? result.getJSONArray("list") : result.getJSONObject("data").getJSONArray("list");
            JSONArray entries = new JSONArray();
            for (int i = 0; i < source.length(); i++) {
                JSONObject entry = source.getJSONObject(i);
                String entryPath = entry.getString("path"); requirePath(entryPath);
                if (!parentPath(entryPath).equals(path)) throw new IllegalArgumentException("百度返回的文件不在当前目录中");
                entries.put(json("fsId", entry.getString("fs_id"), "path", entryPath, "name", entry.getString("server_filename"),
                        "isDir", entry.getInt("isdir") == 1, "category", entry.optInt("category"), "size", entry.optLong("size")));
            }
            JSONObject listing = json("path", path, "root", status().optString("root"), "entries", entries,
                    "start", start, "hasMore", source.length() == 100);
            // list 的 web=1 只保证图片缩略图；视频需批量 filemetas + thumb=1，避免逐集请求。
            try { loadPreviews(entries); }
            catch (IllegalArgumentException error) { listing.put("previewError", "缩略图暂未加载，仍可选择视频播放"); }
            return listing;
        } catch (JSONException error) { throw new IllegalArgumentException("百度目录返回不完整，请稍后重试"); }
    }

    private void loadPreviews(JSONArray entries) throws JSONException {
        StringBuilder ids = new StringBuilder();
        Map<String, JSONObject> videos = new HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entry.optBoolean("isDir") || entry.optInt("category") != 1) continue;
            String id = entry.getString("fsId");
            if (ids.length() > 0) ids.append(',');
            ids.append(id); videos.put(id, entry);
        }
        if (videos.isEmpty()) return;
        JSONObject result = request("https://pan.baidu.com/rest/2.0/xpan/multimedia", "method", "filemetas", "access_token", accessToken(),
                "fsids", "[" + ids + "]", "thumb", "1", "needmedia", "1", "dlink", "0");
        checkError(result);
        JSONArray metas = result.optJSONArray("list");
        if (metas == null) throw new IllegalArgumentException("百度未返回缩略图信息");
        synchronized (this) {
            if (thumbnails.size() + metas.length() > 300) thumbnails.clear();
            for (int i = 0; i < metas.length(); i++) {
                JSONObject meta = metas.getJSONObject(i), entry = videos.get(meta.optString("fs_id"));
                if (entry == null || !entry.optString("path").equals(meta.optString("path")) || meta.optInt("errno") != 0) continue;
                entry.put("duration", meta.optInt("duration"));
                JSONObject thumbs = meta.optJSONObject("thumbs");
                if (thumbs == null || thumbs.optString("url2").isEmpty()) continue;
                String key = UUID.randomUUID().toString();
                thumbnails.put(key, thumbs.optString("url2")); entry.put("thumbnailId", key);
            }
        }
    }

    /** 缩略图只使用接口提供的签名地址，限制域名及大小，不向家长网页泄露临时 URL。 */
    public byte[] thumbnail(String id) {
        String target;
        synchronized (this) { target = thumbnails.get(id); }
        if (target == null) return null;
        HttpURLConnection connection = null;
        try {
            for (int redirect = 0; redirect < 4; redirect++) {
                URL url = new URL(target);
                requireBaiduUrl(url);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000); connection.setReadTimeout(6000); connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "pan.baidu.com");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    target = new URL(url, location).toString(); connection.disconnect(); connection = null; continue;
                }
                if (status != 200 || !String.valueOf(connection.getContentType()).startsWith("image/")) return null;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (bytes.size() + n > 2 * 1024 * 1024) return null;
                        bytes.write(buffer, 0, n);
                    }
                    return bytes.toByteArray();
                }
            }
        } catch (Exception ignored) { /* 缩略图失败保留课程封面，不影响文件选择。 */ }
        finally { if (connection != null) connection.disconnect(); }
        return null;
    }

    private static void requireBaiduUrl(URL url) {
        String host = url.getHost().toLowerCase(java.util.Locale.US);
        boolean baidu = host.endsWith(".baidu.com") || host.endsWith(".baidupcs.com") || host.endsWith(".baidubce.com");
        if (!"https".equals(url.getProtocol()) || !baidu || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443))
            throw new IllegalArgumentException("百度返回的文件地址不受支持");
    }

    public static String parentPath(String path) { int slash = path.lastIndexOf('/'); return slash <= 0 ? "/" : path.substring(0, slash); }

    /** 保存前重新核对文件元信息，不能靠前端传入的类型、路径或大整数 ID 判断视频。 */
    public JSONObject binding(String path, String id) {
        JSONObject meta = metadata(path, id, false);
        JSONObject result = json("path", path, "fsId", id, "isDir", meta.optInt("isdir") == 1, "category", meta.optInt("category"));
        validateBinding(result); return result;
    }

    private JSONObject metadata(String path, String id, boolean download) {
        requirePath(path);
        if (!id.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("网盘文件标识无效");
        JSONObject result = request("https://pan.baidu.com/rest/2.0/xpan/multimedia", "method", "filemetas", "access_token", accessToken(),
                "fsids", "[" + id + "]", "dlink", download ? "1" : "0");
        checkError(result);
        JSONArray list = result.optJSONArray("list");
        if (list == null || list.length() != 1) throw new IllegalArgumentException("网盘文件已移动、删除或不在授权范围内，请重新选择");
        JSONObject item = list.optJSONObject(0);
        if (item == null || item.optInt("errno") != 0 || !id.equals(item.optString("fs_id")) || !path.equals(item.optString("path")))
            throw new IllegalArgumentException("网盘文件信息已改变，请重新选择课程目录");
        return item;
    }

    public String createStream(JSONObject course, JSONObject selected) {
        validateBinding(course); validateBinding(selected);
        requirePath(course.optString("path")); requirePath(selected.optString("path"));
        if (selected.optBoolean("isDir") || (course.optBoolean("isDir")
                ? !within(selected.optString("path"), course.optString("path"))
                : !selected.optString("fsId").equals(course.optString("fsId")))) throw new IllegalArgumentException("视频不在当前课程中");
        return videoStream(selected);
    }

    /** 自动目录只能播放当前应用授权根目录内的真实视频，不需要伪造主目录的文件 ID。 */
    public String createRootStream(JSONObject selected) {
        validateBinding(selected); requirePath(selected.optString("path"));
        if (selected.optBoolean("isDir")) throw new IllegalArgumentException("请选择视频文件");
        return videoStream(selected);
    }

    private String videoStream(JSONObject selected) {
        JSONObject meta = metadata(selected.optString("path"), selected.optString("fsId"), true);
        if (meta.optInt("isdir") != 0 || meta.optInt("category") != 1 || meta.optString("dlink").isEmpty())
            throw new IllegalArgumentException("百度未提供此视频的文件地址，请检查应用下载权限");
        Stream stream = new Stream(); stream.url = meta.optString("dlink"); stream.created = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        synchronized (this) { streams.clear(); streams.put(id, stream); }
        // 播放器只得到回环地址，百度令牌与临时文件地址不会进入播放器日志或家长网页。
        return "http://127.0.0.1:8787/baidu-stream/" + id;
    }

    public synchronized void closeStreams() { streams.clear(); }

    HttpURLConnection openStream(String id, String range, boolean head) {
        Stream stream;
        synchronized (this) { stream = streams.get(id); }
        if (stream == null || System.currentTimeMillis() - stream.created > 7L * 60 * 60 * 1000)
            throw new IllegalArgumentException("播放地址已过期，请重新打开视频");
        if (range != null && !range.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)")) throw new IllegalArgumentException("播放范围无效");
        String target = stream.url, token = accessToken();
        try {
            for (int redirect = 0; redirect < 6; redirect++) {
                URL url = new URL(target); requireBaiduUrl(url);
                if (!target.matches(".*[?&]access_token=.*")) target += (target.contains("?") ? "&" : "?") + "access_token=" + encode(token);
                HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
                connection.setConnectTimeout(8000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(head ? "HEAD" : "GET"); connection.setRequestProperty("User-Agent", "pan.baidu.com");
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (range != null) connection.setRequestProperty("Range", range);
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location"); connection.disconnect();
                    if (location == null) throw new IllegalArgumentException("百度文件跳转失败");
                    target = new URL(url, location).toString(); continue;
                }
                if (status != 200 && status != 206 && status != 416) { connection.disconnect(); throw new IllegalArgumentException("百度文件暂时无法读取，请稍后重新打开"); }
                if (String.valueOf(connection.getContentType()).toLowerCase(java.util.Locale.US).contains("json")) {
                    connection.disconnect(); throw new IllegalArgumentException("百度未提供视频数据，请检查应用权限或稍后重试");
                }
                return connection;
            }
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("连接网盘视频失败，请检查网络后重试"); }
        throw new IllegalArgumentException("百度文件跳转次数过多，请重新打开视频");
    }

    private static final class Stream { String url; long created; }

    private void checkError(JSONObject result) {
        int code = result.optInt("errno", -1);
        if (code == 0 && result.optJSONObject("data") != null) code = result.optJSONObject("data").optInt("errno", 0);
        if (code == 0) return;
        if (code == 110 || code == 111 || code == 31045) { disconnect(); throw new IllegalArgumentException("网盘授权已失效，请家长重新连接"); }
        if (code == 20011 || code == -9 || code == 31066) throw new IllegalArgumentException("目录不存在或没有权限。请将课程放入「我的应用数据／" + status().optString("appName") + "」后重试");
        throw new IllegalArgumentException("百度网盘暂时无法处理此请求（错误码 " + code + "），请检查应用权限后重试");
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static JSONObject request(String endpoint, String... fields) {
        HttpURLConnection connection = null;
        try {
            StringBuilder query = new StringBuilder(endpoint).append('?');
            for (int i = 0; i < fields.length; i += 2) {
                if (i > 0) query.append('&');
                query.append(encode(fields[i])).append('=').append(encode(fields[i + 1]));
            }
            connection = (HttpURLConnection) new URL(query.toString()).openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "pan.baidu.com");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IllegalArgumentException("百度接口返回了意外跳转，请稍后重试");
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new IllegalArgumentException("百度接口暂时不可用");
            String body;
            try (InputStream source = input) { body = read(source, 2 * 1024 * 1024); }
            // Android JSON 会把超出 long 的数转成浮点；解析前将官方 fs_id 数值保留为字符串。
            body = body.replaceAll("(\\\"fs_id\\\"\\s*:\\s*)([0-9]+)", "$1\"$2\"");
            return new JSONObject(body);
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("暂时无法连接百度网盘，请检查网络或稍后重试"); }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static String read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) {
            if (bytes.size() + n > limit) throw new IllegalArgumentException("百度返回的数据过大，请缩小目录后重试");
            bytes.write(buffer, 0, n);
        }
        return bytes.toString("UTF-8");
    }
}
