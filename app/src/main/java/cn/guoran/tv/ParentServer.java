package cn.guoran.tv;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

/** 由电视前台服务托管的局域网家长入口，不依赖电脑或外部服务器。 */
public final class ParentServer implements Closeable {
    public interface Launcher {
        /** 只打开电视系统安装确认页，不能把调用成功视为安装成功。 */
        void install(File apk, String uploadId);
    }

    public static final int PORT = 8787;
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_JSON = 64 * 1024;
    private static final long MAX_APK = 100L * 1024 * 1024;
    private static final int MAX_UPLOADS = 3;
    private static final long TOKEN_LIFETIME = 12L * 60 * 60 * 1000;
    private static final long PAIR_WINDOW = 60 * 1000L;
    private static final int IDLE_TIMEOUT = 15 * 1000;
    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

    private final Context context;
    private final Store store;
    private final BaiduDrive baidu;
    private final AtomicInteger mediaConnections = new AtomicInteger();
    private final Launcher launcher;
    private final File uploadDirectory;
    private final SecureRandom random = new SecureRandom();
    private final Object pairingLock = new Object();
    private final Object uploadLock = new Object();
    private final Map<String, Long> sessions = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> badPairings = new HashMap<>();
    private final ConcurrentHashMap<String, Upload> uploads = new ConcurrentHashMap<>();
    private final Set<Socket> connections = Collections.newSetFromMap(
            new ConcurrentHashMap<Socket, Boolean>());
    private final ConcurrentHashMap<Socket, Long> deadlines = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor watchdog;
    private volatile boolean running;
    private boolean closed;
    private ServerSocket listener;

    public ParentServer(Context context, Launcher launcher) {
        if (context == null || launcher == null) {
            throw new IllegalArgumentException("家长服务缺少运行环境");
        }
        this.context = context.getApplicationContext();
        this.store = Store.get(this.context);
        this.baidu = BaiduDrive.get(this.context);
        this.launcher = launcher;
        this.uploadDirectory = new File(this.context.getFilesDir(), "uploads");
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "parent-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // 固定工人和有限队列，慢上传不能无限占用电视内存或创建线程。
        this.workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(8), factory);
        this.workers.allowCoreThreadTimeOut(true);
        this.watchdog = new ScheduledThreadPoolExecutor(1, factory);
    }

    public synchronized void start() throws IOException {
        if (closed) throw new IllegalStateException("家长服务已关闭");
        if (running) return;
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("0.0.0.0", PORT), 16);
        } catch (IOException error) {
            closeQuietly(socket);
            throw error;
        }
        listener = socket;
        running = true;
        watchdog.scheduleAtFixedRate(() -> {
            long now = SystemClock.elapsedRealtime();
            for (Map.Entry<Socket, Long> connection : deadlines.entrySet()) {
                if (connection.getValue() <= now) closeQuietly(connection.getKey());
            }
        }, 1, 1, TimeUnit.SECONDS);
        Thread thread = new Thread(this::acceptConnections, "parent-http-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized void close() {
        closed = true;
        running = false;
        closeQuietly(listener);
        for (Socket socket : connections) closeQuietly(socket);
        workers.shutdownNow();
        watchdog.shutdownNow();
        deadlines.clear();
        synchronized (pairingLock) {
            sessions.clear();
            badPairings.clear();
        }
    }

    private void acceptConnections() {
        // APK 签名解析放在后台，最多三份旧包不能阻塞电视主线程启动。
        restoreUploads();
        while (running) {
            Socket socket = null;
            try {
                socket = listener.accept();
                if (!running || !isLanAddress(socket.getInetAddress())) {
                    closeQuietly(socket);
                    continue;
                }
                socket.setSoTimeout(IDLE_TIMEOUT);
                socket.setTcpNoDelay(true);
                connections.add(socket);
                deadlines.put(socket, SystemClock.elapsedRealtime() + 20 * 1000L);
                final Socket accepted = socket;
                workers.execute(() -> handle(accepted));
            } catch (RejectedExecutionException error) {
                // 队列满时立即释放连接，不在接收线程等候慢客户端。
                if (socket != null) {
                    connections.remove(socket);
                    deadlines.remove(socket);
                }
                closeQuietly(socket);
            } catch (IOException error) {
                if (socket != null) {
                    connections.remove(socket);
                    deadlines.remove(socket);
                }
                closeQuietly(socket);
                if (!running) return;
            }
        }
    }

    private void handle(Socket socket) {
        try {
            if (!running) return;
            InputStream input = new BufferedInputStream(socket.getInputStream(), 8192);
            Request request = readRequest(socket, input);
            validateHost(request.host, socket);
            String origin = request.headers.get("origin");
            if (origin != null && !origin.equalsIgnoreCase("http://" + request.host)) {
                throw new HttpError(403, "只允许从电视提供的家长页面访问");
            }
            deadlines.replace(socket, SystemClock.elapsedRealtime()
                    + ("/api/apk/upload".equals(request.path) ? 330 * 1000L : 30 * 1000L));
            dispatch(socket, input, request);
        } catch (HttpError error) {
            sendError(socket, error);
        } catch (SocketTimeoutException error) {
            sendError(socket, new HttpError(408, "请求超时，请重新连接"));
        } catch (IllegalArgumentException error) {
            String message = error.getMessage();
            sendError(socket, new HttpError(400,
                    message == null || message.isEmpty() ? "请求参数不正确" : message));
        } catch (JSONException error) {
            sendError(socket, new HttpError(400, "JSON 格式或字段不正确"));
        } catch (IOException error) {
            sendError(socket, new HttpError(400, "读取请求失败，请重试"));
        } catch (Exception error) {
            // 不把私有文件路径、配对码或会话 token 写入日志和错误响应。
            sendError(socket, new HttpError(500, "电视暂时无法处理此请求"));
        } finally {
            connections.remove(socket);
            deadlines.remove(socket);
            closeQuietly(socket);
        }
    }

    private void dispatch(Socket socket, InputStream input, Request request)
            throws IOException, JSONException, HttpError {
        String path = request.path;
        if ("/health".equals(path)) {
            requireMethod(request, "GET");
            requireEmptyBody(request);
            JSONObject health = new JSONObject().put("app", "果然 TV");
            try {
                health.put("version", context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0).versionName);
            } catch (PackageManager.NameNotFoundException error) {
                throw new HttpError(500, "无法读取应用版本");
            }
            sendJson(socket, 200, health);
            return;
        }
        if ("/".equals(path) || "/parent.js".equals(path) || "/parent.css".equals(path)) {
            requireMethod(request, "GET");
            requireEmptyBody(request);
            serveAsset(socket, path);
            return;
        }
        // 网盘数据只交给同一盒子的播放器；局域网家长接口永远拿不到临时直链。
        if (path.startsWith("/baidu-stream/")) {
            if (!socket.getInetAddress().isLoopbackAddress()) throw new HttpError(403, "只允许盒子本机播放");
            requireMethod(request, "GET, HEAD"); requireEmptyBody(request);
            streamVideo(socket, request, path.substring("/baidu-stream/".length()));
            return;
        }
        if (!path.startsWith("/api/")) throw new HttpError(404, "页面或接口不存在");
        if (!"/api/pair".equals(path)) requireToken(request);

        if ("/api/state".equals(path)) {
            requireMethod(request, "GET");
            requireEmptyBody(request);
            sendSuccess(socket, snapshot());
            return;
        }
        if ("/api/apk/upload".equals(path)) {
            requireMethod(request, "POST");
            Upload upload = receiveApk(socket, input, request);
            sendSuccess(socket, upload.toJson());
            return;
        }
        String method;
        switch (path) {
            case "/api/pair":
            case "/api/profile":
            case "/api/box":
            case "/api/cover":
            case "/api/resource/order":
            case "/api/apps":
            case "/api/select":
            case "/api/extra-time":
            case "/api/apk/install":
            case "/api/baidu/config":
            case "/api/baidu/authorize":
            case "/api/baidu/poll":
            case "/api/baidu/list":
            case "/api/baidu/disconnect":
                method = "POST";
                break;
            case "/api/resource":
            case "/api/playlist":
                method = "POST, DELETE";
                break;
            case "/api/apk":
                method = "DELETE";
                break;
            default:
                throw new HttpError(404, "接口不存在");
        }
        requireMethod(request, method);
        JSONObject body = readJson(socket, input, request);
        switch (path) {
            case "/api/pair":
                String token = pair(body, socket.getInetAddress().getHostAddress());
                sendSuccess(socket, new JSONObject().put("token", token));
                return;
            case "/api/profile":
                throw new HttpError(400, "观看规则已改为全盒子统一管理，请刷新家长页面");
            case "/api/box":
                store.updateBox(body);
                break;
            case "/api/playlist":
                if ("DELETE".equals(request.method)) store.deletePlaylist(requiredString(body, "id"));
                else store.savePlaylist(body);
                break;
            case "/api/cover":
                sendSuccess(socket, store.coverImage(requiredString(body, "cover")));
                return;
            case "/api/resource":
                if ("DELETE".equals(request.method)) store.deleteResource(body);
                else {
                    if ("baiduDrive".equals(body.optString("kind"))) {
                        JSONObject binding = body.optJSONObject("baidu");
                        if (binding == null) throw new IllegalArgumentException("请先选择网盘文件夹或视频");
                        BaiduDrive.validateBinding(binding);
                        JSONObject previous = null;
                        if (!body.optString("id").isEmpty()) previous = store.resource(body.optString("fromChild", body.optString("child", "family")), body.optString("id"));
                        JSONObject old = previous == null ? null : previous.optJSONObject("baidu");
                        if (previous == null || !"baiduDrive".equals(previous.optString("kind"))
                                || old == null || !binding.optString("path").equals(old.optString("path"))
                                || !binding.optString("fsId").equals(old.optString("fsId"))
                                || binding.optBoolean("isDir") != old.optBoolean("isDir") || binding.optInt("category") != old.optInt("category"))
                            body.put("baidu", baidu.binding(binding.optString("path"), binding.optString("fsId")));
                    }
                    store.saveResource(body);
                }
                break;
            case "/api/baidu/config":
                baidu.configure(body); break;
            case "/api/baidu/authorize":
                sendSuccess(socket, baidu.authorize()); return;
            case "/api/baidu/poll":
                sendSuccess(socket, baidu.pollAuthorization()); return;
            case "/api/baidu/list":
                sendSuccess(socket, baidu.list(body.optString("path"), body.optInt("start", 0))); return;
            case "/api/baidu/disconnect":
                baidu.disconnect(); break;
            case "/api/resource/order":
                store.reorderResources(body);
                break;
            case "/api/apps":
                store.setAppAllowed(body);
                break;
            case "/api/select":
                store.selectChild(requiredString(body, "child"));
                break;
            case "/api/extra-time":
                Object minutes = body.opt("minutes");
                if (!(minutes instanceof Number) || ((Number) minutes).doubleValue() != 5) {
                    throw new HttpError(400, "每次只能加时 5 分钟");
                }
                store.addExtraTime("", 5);
                break;
            case "/api/apk":
                deleteUpload(requiredString(body, "id"));
                break;
            case "/api/apk/install":
                Upload upload = uploads.get(requiredString(body, "id"));
                if (upload == null) throw new HttpError(404, "安装包不存在");
                synchronized (upload) {
                    refreshInstalledStatus(upload);
                    if (!"installed".equals(upload.status)) {
                        if (!upload.file.isFile() || upload.file.length() != upload.size) {
                            throw new HttpError(409, "安装包文件已不可用，请重新上传");
                        }
                        String previous = upload.status;
                        upload.status = "awaiting_confirmation";
                        try {
                            launcher.install(upload.file, upload.id);
                        } catch (RuntimeException error) {
                            upload.status = previous;
                            throw new HttpError(500, "无法打开电视安装确认页，请在电视上重试");
                        }
                    }
                }
                sendSuccess(socket, upload.toJson());
                return;
            default:
                throw new HttpError(404, "接口不存在");
        }
        sendSuccess(socket, snapshot());
    }

    private JSONObject snapshot() throws JSONException {
        JSONObject result = store.snapshot();
        List<Upload> ordered = new ArrayList<>(uploads.values());
        Collections.sort(ordered, new Comparator<Upload>() {
            @Override public int compare(Upload first, Upload second) {
                return first.id.compareTo(second.id);
            }
        });
        JSONArray records = new JSONArray();
        for (Upload upload : ordered) {
            synchronized (upload) {
                refreshInstalledStatus(upload);
                records.put(upload.toJson());
            }
        }
        result.put("uploads", records);
        result.put("baidu", baidu.status());
        result.put("capabilities", new JSONObject()
                .put("silentInstall", false).put("externalAppLimits", MouseNavigationService.isEnabled(context)));
        return result;
    }

    private void streamVideo(Socket socket, Request request, String id) throws IOException, HttpError {
        if (!id.matches("[a-f0-9-]{36}")) throw new HttpError(404, "播放地址无效");
        if (mediaConnections.incrementAndGet() > 2) {
            mediaConnections.decrementAndGet(); throw new HttpError(429, "请稍后重新播放");
        }
        HttpURLConnection source = null;
        try {
            deadlines.put(socket, SystemClock.elapsedRealtime() + 30000);
            boolean head = "HEAD".equals(request.method);
            source = baidu.openStream(id, request.headers.get("range"), head);
            int status = source.getResponseCode();
            StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(status)
                    .append(status == 206 ? " Partial Content" : status == 416 ? " Range Not Satisfiable" : " OK")
                    .append("\r\nConnection: close\r\nCache-Control: no-store\r\nAccept-Ranges: bytes\r\n");
            for (String name : new String[]{"Content-Type", "Content-Length", "Content-Range"}) {
                String value = source.getHeaderField(name);
                if (value != null && !value.contains("\r") && !value.contains("\n")) headers.append(name).append(": ").append(value).append("\r\n");
            }
            OutputStream output = socket.getOutputStream();
            output.write(headers.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
            if (!head && status != 416) try (InputStream video = source.getInputStream()) {
                byte[] buffer = new byte[64 * 1024]; int n;
                while ((n = video.read(buffer)) != -1) {
                    deadlines.put(socket, SystemClock.elapsedRealtime() + 30000);
                    output.write(buffer, 0, n);
                }
            }
            output.flush();
        } finally {
            if (source != null) source.disconnect();
            mediaConnections.decrementAndGet();
        }
    }

    private String pair(JSONObject body, String address) throws HttpError {
        long now = SystemClock.elapsedRealtime();
        synchronized (pairingLock) {
            Iterator<Map.Entry<String, ArrayDeque<Long>>> iterator = badPairings.entrySet().iterator();
            while (iterator.hasNext()) {
                ArrayDeque<Long> attempts = iterator.next().getValue();
                while (!attempts.isEmpty() && now - attempts.peekFirst() >= PAIR_WINDOW) {
                    attempts.removeFirst();
                }
                if (attempts.isEmpty()) iterator.remove();
            }
            ArrayDeque<Long> attempts = badPairings.get(address);
            if (attempts != null && attempts.size() >= 5) {
                throw new HttpError(429, "配对错误次数过多，请在一分钟后重试");
            }
            Object supplied = body.opt("code");
            // 配对码按电视原值精确匹配，不接受数字转换、空白裁剪或前缀。
            if (!(supplied instanceof String) || !MessageDigest.isEqual(
                    ((String) supplied).getBytes(StandardCharsets.UTF_8),
                    store.pairingCode().getBytes(StandardCharsets.UTF_8))) {
                if (attempts == null) {
                    if (badPairings.size() >= 128) {
                        throw new HttpError(429, "配对请求过多，请稍后重试");
                    }
                    attempts = new ArrayDeque<>();
                    badPairings.put(address, attempts);
                }
                attempts.addLast(now);
                throw new HttpError(401, "配对码不正确，请查看电视上的配对码");
            }
            badPairings.remove(address);
            Iterator<Map.Entry<String, Long>> tokens = sessions.entrySet().iterator();
            while (tokens.hasNext()) {
                if (tokens.next().getValue() <= now) tokens.remove();
            }
            if (sessions.size() >= 32) {
                String oldest = null;
                long earliest = Long.MAX_VALUE;
                for (Map.Entry<String, Long> session : sessions.entrySet()) {
                    if (session.getValue() < earliest) {
                        oldest = session.getKey();
                        earliest = session.getValue();
                    }
                }
                sessions.remove(oldest);
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.encodeToString(bytes,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            sessions.put(token, now + TOKEN_LIFETIME);
            return token;
        }
    }

    private void requireToken(Request request) throws HttpError {
        String authorization = request.headers.get("authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new HttpError(401, "请先使用电视配对码连接");
        }
        String token = authorization.substring(7);
        synchronized (pairingLock) {
            Long expires = sessions.get(token);
            if (expires == null || expires <= SystemClock.elapsedRealtime()) {
                sessions.remove(token);
                throw new HttpError(401, "配对已失效，请重新输入电视配对码");
            }
        }
    }

    private Upload receiveApk(Socket socket, InputStream input, Request request)
            throws IOException, HttpError {
        requireLength(request, MAX_APK);
        String mediaType = mediaType(request);
        if (!mediaType.isEmpty() && !"application/octet-stream".equals(mediaType)
                && !"application/vnd.android.package-archive".equals(mediaType)) {
            throw new HttpError(415, "请直接上传 APK 文件内容");
        }
        String checksum = request.headers.get("x-sha256");
        if (checksum != null && !checksum.matches("[a-fA-F0-9]{64}")) {
            throw new HttpError(400, "X-SHA256 必须是 64 位十六进制校验值");
        }
        String id = UUID.randomUUID().toString();
        File temporary = new File(uploadDirectory, id + ".part.apk");
        File destination = new File(uploadDirectory, id + ".apk");
        synchronized (uploadLock) {
            if (!uploadDirectory.isDirectory() && !uploadDirectory.mkdirs()) {
                throw new HttpError(500, "无法创建安装包保存目录");
            }
            File[] retained = uploadDirectory.listFiles((directory, name) ->
                    name.endsWith(".apk") || name.endsWith(".part"));
            if (retained == null) throw new HttpError(500, "无法读取安装包保存目录");
            // 未完成的上传也占用名额，重启或并发请求不能突破最多三份的磁盘上限。
            if (retained.length >= MAX_UPLOADS) {
                throw new HttpError(409, "电视已保留 3 个安装包，请先在电视上清理后再上传");
            }
            if (!temporary.createNewFile()) throw new HttpError(500, "无法创建临时安装包");
        }
        boolean saved = false;
        try {
            MessageDigest digest = sha256();
            long remaining = request.length;
            long deadline = SystemClock.elapsedRealtime() + 5 * 60 * 1000L;
            byte[] buffer = new byte[32 * 1024];
            try (OutputStream output = new FileOutputStream(temporary)) {
                while (remaining > 0) {
                    int count = readBefore(socket, input, buffer, 0,
                            (int) Math.min(buffer.length, remaining), deadline);
                    if (count == -1) throw new HttpError(400, "安装包上传不完整");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    remaining -= count;
                }
            }
            String actualChecksum = hex(digest.digest());
            if (checksum != null && !actualChecksum.equalsIgnoreCase(checksum)) {
                throw new HttpError(422, "安装包校验失败，请重新上传");
            }
            PackageInfo archive = inspectApk(temporary);
            Upload upload = new Upload(id, destination, archive, request.length, actualChecksum,
                    packageLabel(archive, temporary));
            if (!temporary.renameTo(destination)) {
                throw new HttpError(500, "无法保存安装包，请检查电视剩余空间");
            }
            saved = true;
            refreshInstalledStatus(upload);
            uploads.put(id, upload);
            return upload;
        } finally {
            // 只清理本请求自己创建的临时文件，绝不清理其他上传或任意路径。
            if (!saved) temporary.delete();
        }
    }

    @SuppressWarnings("deprecation")
    private PackageInfo inspectApk(File file) throws HttpError {
        try (ZipFile archive = new ZipFile(file)) {
            if (archive.getEntry("AndroidManifest.xml") == null) {
                throw new HttpError(422, "文件不是有效的 Android 安装包");
            }
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                    file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (info == null || info.packageName == null || info.packageName.isEmpty()
                    || info.applicationInfo == null || info.signatures == null
                    || info.signatures.length == 0) {
                throw new HttpError(422, "无法解析安装包或验证安装包签名");
            }
            return info;
        } catch (IOException | RuntimeException error) {
            throw new HttpError(422, "安装包损坏或格式不受支持");
        }
    }

    private String packageLabel(PackageInfo info, File file) {
        ApplicationInfo application = info.applicationInfo;
        application.sourceDir = file.getAbsolutePath();
        application.publicSourceDir = file.getAbsolutePath();
        CharSequence value = application.loadLabel(context.getPackageManager());
        String label = value == null ? info.packageName : value.toString();
        return label.length() > 120 ? label.substring(0, 120) : label;
    }

    private void restoreUploads() {
        File[] files = uploadDirectory.listFiles((directory, name) ->
                name.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.apk"));
        if (files == null) return;
        int retained = 0;
        for (File file : files) {
            if (!running || retained >= MAX_UPLOADS) break;
            try {
                File expected = new File(uploadDirectory.getCanonicalFile(), file.getName());
                if (!file.isFile() || !file.getCanonicalFile().equals(expected)
                        || file.length() <= 0 || file.length() > MAX_APK) continue;
                PackageInfo info = inspectApk(file);
                String id = file.getName().substring(0, file.getName().length() - 4);
                Upload upload = new Upload(id, file, info, file.length(), null,
                        packageLabel(info, file));
                refreshInstalledStatus(upload);
                uploads.put(id, upload);
                retained++;
            } catch (IOException | HttpError | RuntimeException ignored) {
                // 重启时不展示无法校验的旧文件，也不擅自删除它们。
            }
        }
    }

    private void deleteUpload(String id) throws HttpError, IOException {
        Upload upload = uploads.get(id);
        if (upload == null) throw new HttpError(404, "安装包不存在");
        synchronized (upload) {
            synchronized (uploadLock) {
                File expected = new File(uploadDirectory.getCanonicalFile(), upload.id + ".apk");
                // 删除的是已校验的自有安装包；不会卸载应用，也不会按客户端路径删文件。
                if (!upload.file.getCanonicalFile().equals(expected)) {
                    throw new HttpError(409, "安装包路径已变化，无法删除");
                }
                if (upload.file.exists() && !upload.file.delete()) {
                    throw new HttpError(500, "无法删除安装包，请检查电视存储空间");
                }
                uploads.remove(id, upload);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void refreshInstalledStatus(Upload upload) {
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(
                    upload.packageName, PackageManager.GET_SIGNATURES);
            if (versionCode(installed) >= upload.versionCode
                    && sameSignatures(installed.signatures, upload.signatures)) {
                upload.status = "installed";
                return;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // 安装确认页返回并不能证明安装完成，以系统实际包信息为准。
        }
        if ("installed".equals(upload.status)) upload.status = "uploaded";
    }

    private static boolean sameSignatures(Signature[] actual, Signature[] expected) {
        if (actual == null || expected == null || actual.length != expected.length) return false;
        for (Signature signature : expected) {
            boolean found = false;
            for (Signature candidate : actual) {
                if (signature.equals(candidate)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static Request readRequest(Socket socket, InputStream input)
            throws IOException, HttpError {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int end = 0;
        long deadline = SystemClock.elapsedRealtime() + 10 * 1000;
        byte[] one = new byte[1];
        while (end != 4) {
            if (header.size() >= MAX_HEADER) throw new HttpError(431, "请求头超过 16KB");
            if (readBefore(socket, input, one, 0, 1, deadline) == -1) {
                throw new HttpError(400, "请求头不完整");
            }
            int value = one[0] & 255;
            if ((value < 32 && value != '\r' && value != '\n' && value != '\t') || value > 126) {
                throw new HttpError(400, "请求头包含无效字符");
            }
            header.write(value);
            int wanted = end == 0 || end == 2 ? '\r' : '\n';
            end = value == wanted ? end + 1 : value == '\r' ? 1 : 0;
        }
        String[] lines = new String(header.toByteArray(), StandardCharsets.US_ASCII).split("\r\n");
        String[] requestLine = lines[0].split(" ", -1);
        if (requestLine.length != 3 || !requestLine[0].matches("[A-Z]+")
                || !requestLine[1].startsWith("/") || requestLine[1].contains("#")
                || requestLine[1].contains("?") || requestLine[1].contains("\\")) {
            throw new HttpError(400, "请求路径或方法不正确，接口不接受查询参数");
        }
        if (!"HTTP/1.1".equals(requestLine[2]) && !"HTTP/1.0".equals(requestLine[2])) {
            throw new HttpError(505, "HTTP 版本不受支持");
        }
        Map<String, String> headers = new HashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            int colon = line.indexOf(':');
            if (colon <= 0 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
                throw new HttpError(400, "请求头格式不正确");
            }
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            if (headers.containsKey(key)) throw new HttpError(400, "不允许重复请求头");
            String value = line.substring(colon + 1).trim();
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new HttpError(400, "请求头格式不正确");
            }
            headers.put(key, value);
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new HttpError(400, "上传必须使用 Content-Length，不接受分块传输");
        }
        if (headers.containsKey("expect")) throw new HttpError(417, "不支持 Expect 请求头");
        String encoding = headers.get("content-encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
            throw new HttpError(415, "不接受压缩请求内容");
        }
        long length = -1;
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            if (!contentLength.matches("[0-9]{1,12}")) {
                throw new HttpError(400, "Content-Length 不正确");
            }
            length = Long.parseLong(contentLength);
        }
        String host = headers.get("host");
        if (host == null || host.isEmpty()) throw new HttpError(400, "缺少 Host 请求头");
        return new Request(requestLine[0], requestLine[1], host, headers, length);
    }

    private static void validateHost(String host, Socket socket) throws IOException, HttpError {
        String address;
        String port = null;
        if (host.startsWith("[")) {
            int bracket = host.indexOf(']');
            if (bracket < 0) throw new HttpError(403, "Host 必须是电视的局域网地址");
            address = host.substring(1, bracket);
            if (!address.matches("[a-fA-F0-9:]+") || !address.contains(":")) {
                throw new HttpError(403, "Host 必须是电视的局域网地址");
            }
            if (bracket + 1 < host.length()) {
                if (host.charAt(bracket + 1) != ':') {
                    throw new HttpError(403, "Host 格式不正确");
                }
                port = host.substring(bracket + 2);
            }
        } else {
            int colon = host.indexOf(':');
            address = colon < 0 ? host : host.substring(0, colon);
            if (colon >= 0) port = host.substring(colon + 1);
        }
        if (port != null && !Integer.toString(PORT).equals(port)) {
            throw new HttpError(403, "Host 端口不正确");
        }
        if ("localhost".equalsIgnoreCase(address)) return;
        boolean ipv4 = address.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}");
        if (!ipv4 && !address.contains(":")) {
            throw new HttpError(403, "请使用电视显示的 IP 地址访问");
        }
        // 仅对已验证的数字地址解析，不能让 Host 触发 DNS 查询或 DNS 重绑定。
        InetAddress requested;
        try {
            requested = InetAddress.getByName(address);
        } catch (IOException error) {
            throw new HttpError(403, "Host 地址不正确");
        }
        if (requested.isLoopbackAddress()) return;
        if (requested.equals(socket.getLocalAddress())) return;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            Enumeration<InetAddress> addresses = interfaces.nextElement().getInetAddresses();
            while (addresses.hasMoreElements()) {
                if (requested.equals(addresses.nextElement())) return;
            }
        }
        throw new HttpError(403, "Host 必须是当前电视的本地 IP 地址");
    }

    private static boolean isLanAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) return false;
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()) return true;
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private static JSONObject readJson(Socket socket, InputStream input, Request request)
            throws IOException, HttpError, JSONException {
        // 仅保存内容时接受手机压缩后的封面；其他 JSON 接口保留原来的 64KB 上限。
        requireLength(request, "/api/resource".equals(request.path) && "POST".equals(request.method) ? 384 * 1024 : MAX_JSON);
        if (!"application/json".equals(mediaType(request))) {
            throw new HttpError(415, "请求内容必须是 application/json");
        }
        byte[] bytes = new byte[(int) request.length];
        int offset = 0;
        long deadline = SystemClock.elapsedRealtime() + 15 * 1000;
        while (offset < bytes.length) {
            int count = readBefore(socket, input, bytes, offset, bytes.length - offset, deadline);
            if (count == -1) throw new HttpError(400, "JSON 请求不完整");
            offset += count;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new HttpError(400, "JSON 必须使用 UTF-8 编码");
        }
        JSONTokener tokener = new JSONTokener(text);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0) {
            throw new HttpError(400, "请求内容必须是单个 JSON 对象");
        }
        return (JSONObject) value;
    }

    private static int readBefore(Socket socket, InputStream input, byte[] bytes,
                                  int offset, int count, long deadline) throws IOException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new SocketTimeoutException("request deadline");
        socket.setSoTimeout((int) Math.min(IDLE_TIMEOUT, remaining));
        return input.read(bytes, offset, count);
    }

    private static void requireLength(Request request, long maximum) throws HttpError {
        if (request.length < 0) throw new HttpError(411, "必须提供 Content-Length");
        if (request.length == 0) throw new HttpError(400, "请求内容不能为空");
        if (request.length > maximum) {
            throw new HttpError(413, maximum == MAX_APK ? "安装包不能超过 100MB" : "请求内容超过大小限制");
        }
    }

    private static void requireEmptyBody(Request request) throws HttpError {
        if (request.length > 0) throw new HttpError(400, "此接口不接受请求内容");
    }

    private static void requireMethod(Request request, String methods) throws HttpError {
        for (String method : methods.split(", ")) {
            if (method.equals(request.method)) return;
        }
        HttpError error = new HttpError(405, "此接口只接受 " + methods);
        error.allow = methods;
        throw error;
    }

    private static String requiredString(JSONObject body, String key) throws HttpError {
        Object value = body.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new HttpError(400, "缺少有效字段：" + key);
        }
        return (String) value;
    }

    private static String mediaType(Request request) {
        String contentType = request.headers.get("content-type");
        return contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private void serveAsset(Socket socket, String path) throws IOException, HttpError {
        String file = "/".equals(path) ? "parent.html" : path.substring(1);
        String type = file.endsWith(".js") ? "application/javascript; charset=utf-8"
                : file.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
        // 文件名仅来自上方固定路由白名单，不允许浏览应用私有目录。
        try (InputStream asset = context.getAssets().open(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = asset.read(buffer)) != -1) {
                if (bytes.size() + count > 1024 * 1024) {
                    throw new HttpError(500, "家长页面资源过大");
                }
                bytes.write(buffer, 0, count);
            }
            send(socket, 200, type, bytes.toByteArray(), null);
        } catch (java.io.FileNotFoundException error) {
            throw new HttpError(404, "家长页面资源不存在");
        }
    }

    private void sendSuccess(Socket socket, Object data) throws IOException, JSONException {
        sendJson(socket, 200, new JSONObject().put("code", 0)
                .put("data", data).put("message", ""));
    }

    private void sendJson(Socket socket, int status, JSONObject value) throws IOException {
        send(socket, status, "application/json; charset=utf-8",
                value.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private void sendError(Socket socket, HttpError error) {
        try {
            JSONObject body = new JSONObject().put("code", error.status)
                    .put("data", JSONObject.NULL).put("message", error.getMessage());
            send(socket, error.status, "application/json; charset=utf-8",
                    body.toString().getBytes(StandardCharsets.UTF_8), error.allow);
        } catch (IOException | JSONException ignored) {
            // 客户端断开时直接关闭连接，不保存请求头或 token。
        }
    }

    private void send(Socket socket, int status, String type, byte[] body, String allow)
            throws IOException {
        // Socket 的读取超时不限制写入；独立看门狗关闭不接收响应的慢客户端。
        deadlines.replace(socket, SystemClock.elapsedRealtime() + 15 * 1000L);
        String headers = "HTTP/1.1 " + status + " " + statusText(status) + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n"
                + "Connection: close\r\nCache-Control: private, no-store\r\nPragma: no-cache\r\n"
                + "X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n"
                + "Referrer-Policy: no-referrer\r\nContent-Security-Policy: " + CSP + "\r\n"
                + (allow == null ? "" : "Allow: " + allow + "\r\n")
                + (status == 429 ? "Retry-After: 60\r\n" : "")
                + (status == 401 ? "WWW-Authenticate: Bearer\r\n" : "") + "\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            case 417: return "Expectation Failed";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 505: return "HTTP Version Not Supported";
            default: return "Internal Server Error";
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("设备不支持 SHA-256", error);
        }
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            result[index * 2] = alphabet[(bytes[index] & 255) >>> 4];
            result[index * 2 + 1] = alphabet[bytes[index] & 15];
        }
        return new String(result);
    }

    private static void closeQuietly(Closeable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (IOException ignored) {
            // 生命周期结束时不再向已断开的连接写入响应。
        }
    }

    private static final class Request {
        final String method;
        final String path;
        final String host;
        final Map<String, String> headers;
        final long length;

        Request(String method, String path, String host, Map<String, String> headers, long length) {
            this.method = method;
            this.path = path;
            this.host = host;
            this.headers = headers;
            this.length = length;
        }
    }

    private static final class HttpError extends Exception {
        final int status;
        String allow;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class Upload {
        final String id;
        final File file;
        final String label;
        final String packageName;
        final String versionName;
        final long versionCode;
        final Signature[] signatures;
        final long size;
        final String checksum;
        volatile String status = "uploaded";

        Upload(String id, File file, PackageInfo info, long size, String checksum, String label) {
            this.id = id;
            this.file = file;
            this.label = label;
            this.packageName = info.packageName;
            this.versionName = info.versionName;
            this.versionCode = ParentServer.versionCode(info);
            this.signatures = info.signatures.clone();
            this.size = size;
            this.checksum = checksum;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject().put("id", id).put("label", label)
                    .put("packageName", packageName)
                    .put("versionName", versionName == null ? JSONObject.NULL : versionName)
                    .put("size", size).put("status", status);
            if (checksum != null) result.put("sha256", checksum);
            return result;
        }
    }
}
