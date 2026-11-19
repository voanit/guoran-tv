package cn.guoran.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.View;
import android.widget.FrameLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 视频缩略图异步加载并限制内存；百度尚未生成时显示家长设置的课程封面。 */
public final class DriveThumbnailView extends FrameLayout {
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final String id;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap bitmap;
    private boolean requested;

    public static int clearMemoryCache() {
        int bytes = CACHE.size(); CACHE.evictAll(); return bytes;
    }

    public static int memoryCacheBytes() { return CACHE.size(); }

    public DriveThumbnailView(Context context, String id, String cover) {
        super(context); this.id = id;
        addView(new PosterView(context, cover), new LayoutParams(-1, -1));
        setFocusable(false); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (bitmap == null) {
            super.dispatchDraw(canvas);
            // 只读取即将进入屏幕的卡片，100 集目录不会同时下载全部缩略图。
            if (!requested && !id.isEmpty()) { requested = true; load(); }
            return;
        }
        float scale = Math.max(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        canvas.drawBitmap(bitmap, null, new RectF((getWidth() - width) / 2, (getHeight() - height) / 2,
                (getWidth() + width) / 2, (getHeight() + height) / 2), paint);
    }

    private void load() {
        Bitmap cached = CACHE.get(id);
        if (cached != null) { bitmap = cached; invalidate(); return; }
        final BaiduDrive drive = BaiduDrive.get(getContext());
        WORKER.execute(() -> {
            Bitmap image = null;
            byte[] bytes = drive.thumbnail(id);
            if (bytes != null) {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 800) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.RGB_565;
                    image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                }
            }
            if (image == null) return;
            CACHE.put(id, image); final Bitmap result = image;
            post(() -> { bitmap = result; invalidate(); });
        });
    }
}
