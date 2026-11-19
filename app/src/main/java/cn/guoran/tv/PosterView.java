package cn.guoran.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.util.LruCache;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** 家长上传封面与内置海报都离线显示；封面不代表已经配置影片地址。 */
public final class PosterView extends View {
    private static final LruCache<String, Bitmap> POSTERS = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final String cover;
    private final Bitmap poster;

    public static int clearMemoryCache() {
        int bytes = POSTERS.size(); POSTERS.evictAll(); return bytes;
    }

    public static int memoryCacheBytes() { return POSTERS.size(); }

    public PosterView(Context context, String cover) {
        this(context, cover, false);
    }

    public PosterView(Context context, String cover, boolean large) {
        super(context);
        this.cover = cover;
        this.poster = loadPoster(context, cover, large);
        paint.setFilterBitmap(true);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (poster != null) {
            // 等比铺满，避免海报人物被拉伸；相同封面在主推和片单中复用位图。
            float scale = Math.max(getWidth() / (float) poster.getWidth(), getHeight() / (float) poster.getHeight());
            float width = poster.getWidth() * scale, height = poster.getHeight() * scale;
            canvas.drawBitmap(poster, null, new RectF((getWidth() - width) / 2, (getHeight() - height) / 2,
                    (getWidth() + width) / 2, (getHeight() + height) / 2), paint);
            return;
        }
        canvas.save();
        canvas.scale(getWidth() / 360f, getHeight() / 230f);
        switch (cover) {
            case "frozen": frozen(canvas); break;
            case "sofia": sofia(canvas); break;
            case "bluey": bluey(canvas); break;
            case "blocks": blocks(canvas); break;
            case "dragon": dragon(canvas); break;
            case "space": space(canvas); break;
            case "code": code(canvas); break;
            default: course(canvas); break;
        }
        canvas.restore();
    }

    private static synchronized Bitmap loadPoster(Context context, String cover, boolean large) {
        boolean custom = cover.matches("custom-[a-f0-9]{64}");
        if (!custom && !cover.matches("hero-frozen|frozen|sofia|bluey|dragon|blocks|space")) return null;
        String key = cover + (large ? "-hero" : "-card");
        Bitmap cached = POSTERS.get(key);
        if (cached != null) return cached;
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            File file = custom ? new File(new File(context.getFilesDir(), "covers"), cover + ".jpg") : null;
            if (custom) BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            else try (InputStream input = context.getAssets().open("posters/" + cover + ".jpg")) { BitmapFactory.decodeStream(input, null, options); }
            // 海报卡片使用缩略图；大量家长封面不能全部以主推大图常驻盒子内存。
            int limit = large ? 1600 : 400;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > limit * 2) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.RGB_565;
            if (custom) bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            else try (InputStream input = context.getAssets().open("posters/" + cover + ".jpg")) { bitmap = BitmapFactory.decodeStream(input, null, options); }
        } catch (IOException ignored) { }
        if (bitmap != null) POSTERS.put(key, bitmap);
        return bitmap;
    }

    private void background(Canvas c, String top, String bottom) {
        paint.setShader(new LinearGradient(0, 0, 360, 230, color(top), color(bottom), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 360, 230, paint);
        paint.setShader(null);
    }

    private static int color(String value) { return Color.parseColor(value); }

    private void fill(String value) {
        paint.setColor(color(value));
        paint.setStyle(Paint.Style.FILL);
    }

    private void round(Canvas c, float x, float y, float w, float h, float r, String value) {
        fill(value);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), r, r, paint);
    }

    private void circle(Canvas c, float x, float y, float radius, String value) {
        fill(value);
        c.drawCircle(x, y, radius, paint);
    }

    private void polygon(Canvas c, String value, float... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        path.close();
        fill(value);
        c.drawPath(path, paint);
    }

    private void line(Canvas c, float x, float y, float endX, float endY, float width, String value) {
        fill(value);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x, y, endX, endY, paint);
    }

    private void star(Canvas c, float x, float y, float r, String value) {
        polygon(c, value, x, y - r, x + r * .28f, y - r * .28f, x + r, y,
                x + r * .28f, y + r * .28f, x, y + r, x - r * .28f, y + r * .28f,
                x - r, y, x - r * .28f, y - r * .28f);
    }

    private void snow(Canvas c, float x, float y, float r) {
        for (int i = 0; i < 3; i++) {
            double a = i * Math.PI / 3;
            float dx = (float) Math.cos(a) * r, dy = (float) Math.sin(a) * r;
            line(c, x - dx, y - dy, x + dx, y + dy, 1.4f, "#DFFFFF");
        }
    }

    private void frozen(Canvas c) {
        background(c, "#BEEBFA", "#718DE0");
        circle(c, 90, 50, 74, "#DDF7FF");
        polygon(c, "#ACDDEC", 0, 158, 60, 106, 128, 153, 198, 100, 262, 159, 360, 102, 360, 230, 0, 230);
        polygon(c, "#CEEFFF", 0, 185, 65, 142, 129, 178, 201, 135, 294, 182, 360, 149, 360, 230, 0, 230);
        round(c, 41, 100, 31, 91, 2, "#DEF7FF");
        round(c, 75, 76, 37, 116, 2, "#ECFAFF");
        round(c, 116, 118, 27, 75, 2, "#D9F4FE");
        polygon(c, "#9CCCEB", 35, 101, 56, 67, 78, 101);
        polygon(c, "#83B9E5", 68, 77, 94, 31, 119, 77);
        polygon(c, "#9BC5EC", 110, 119, 129, 88, 149, 119);
        round(c, 87, 128, 15, 64, 7, "#8EB8E2");
        for (int i = 0; i < 3; i++) round(c, 87, 89 + i * 13, 6, 8, 3, "#A6D5EF");
        princess(c, 259, 120, "#8AD7F0", "#DAF9FF", "#F7E8B1", true);
        snow(c, 186, 46, 9); snow(c, 318, 33, 13); snow(c, 158, 102, 5); snow(c, 337, 113, 6);
        star(c, 218, 54, 5, "#FFFFFF"); star(c, 310, 77, 4, "#FFFFFF");
    }

    private void princess(Canvas c, float x, float y, String dress, String trim, String hair, boolean braid) {
        c.save();
        c.translate(x, y);
        polygon(c, "#73B5D455", -14, 8, -61, 99, 62, 99, 23, 3);
        polygon(c, dress, -12, 13, -45, 107, 49, 107, 12, 13);
        polygon(c, trim, -5, 26, -15, 106, 6, 106, 7, 26);
        line(c, -8, 7, -35, 24, 8, "#F5D8C5");
        line(c, 9, 8, 31, -10, 8, "#F5D8C5");
        line(c, -11, 6, 9, 6, 20, dress);
        circle(c, 0, -23, 23, hair);
        circle(c, 0, -17, 17, "#FADECA");
        polygon(c, hair, -19, -20, -13, -44, 8, -47, 22, -28, 6, -31, -4, -24);
        if (braid) {
            for (int i = 0; i < 5; i++) circle(c, 14 + i * 2, -3 + i * 8, 6 - i * .4f, hair);
            star(c, 25, 33, 4, "#C7FCFF");
        } else {
            round(c, -25, -22, 12, 47, 6, hair);
            round(c, 15, -22, 12, 47, 6, hair);
        }
        circle(c, -6, -17, 1.6f, "#536482"); circle(c, 6, -17, 1.6f, "#536482");
        line(c, -2, -8, 3, -8, 1.8f, "#D08E8B");
        c.restore();
    }

    private void sofia(Canvas c) {
        background(c, "#DDC9F0", "#A188CF");
        circle(c, 280, 29, 76, "#EFDAF6");
        polygon(c, "#C1A3DD", 0, 166, 67, 138, 133, 163, 209, 134, 296, 169, 360, 145, 360, 230, 0, 230);
        round(c, 45, 95, 82, 104, 8, "#E9D5F1");
        polygon(c, "#A98AD0", 39, 96, 84, 51, 133, 96);
        round(c, 71, 145, 29, 56, 15, "#AF8BCD");
        round(c, 51, 83, 21, 70, 2, "#F2E4FA");
        polygon(c, "#B596D8", 46, 85, 62, 55, 77, 85);
        round(c, 112, 80, 19, 78, 2, "#F2E4FA");
        polygon(c, "#B596D8", 105, 81, 121, 51, 136, 81);
        princess(c, 254, 119, "#916AC5", "#D5C0EA", "#7D4F4D", false);
        polygon(c, "#F8D685", 236, 76, 231, 59, 245, 65, 252, 52, 261, 65, 276, 59, 271, 77);
        circle(c, 253, 67, 3.5f, "#C08BCF");
        for (int i = 0; i < 6; i++) star(c, 155 + (i % 3) * 55, 28 + (i / 3) * 43, 3 + (i % 2), "#FFF1CE");
        star(c, 324, 129, 8, "#FFF0C6");
    }

    private void bluey(Canvas c) {
        background(c, "#9BC8ED", "#6B9CD8");
        circle(c, 302, 38, 44, "#FFDF92");
        round(c, 26, 33, 94, 19, 12, "#D9EDFA");
        circle(c, 67, 32, 17, "#D9EDFA");
        polygon(c, "#9CBDA7", 0, 177, 73, 145, 161, 183, 240, 151, 360, 177, 360, 230, 0, 230);
        circle(c, 48, 158, 31, "#7FAE97");
        round(c, 43, 161, 8, 58, 4, "#8C8471");
        puppy(c, 201, 137, 1.07f, "#5C91C3", "#355A91", "#B0D7EA");
        puppy(c, 288, 164, .72f, "#D7A26C", "#B77850", "#F7D9A4");
    }

    private void puppy(Canvas c, float x, float y, float scale, String coat, String dark, String light) {
        c.save(); c.translate(x, y); c.scale(scale, scale);
        round(c, -30, -3, 60, 83, 21, coat);
        round(c, -33, -58, 66, 65, 20, coat);
        polygon(c, coat, -31, -43, -33, -90, -6, -57);
        polygon(c, coat, 6, -57, 33, -90, 31, -43);
        polygon(c, dark, -27, -55, -27, -78, -13, -58);
        polygon(c, dark, 13, -58, 27, -78, 27, -55);
        round(c, -30, -39, 26, 33, 9, dark);
        round(c, 6, -39, 25, 33, 9, dark);
        round(c, -20, -26, 41, 30, 13, light);
        circle(c, -12, -29, 7, "#FFFDF4"); circle(c, 14, -29, 7, "#FFFDF4");
        circle(c, -10, -29, 3, "#263E56"); circle(c, 12, -29, 3, "#263E56");
        round(c, -7, -15, 15, 10, 5, "#233E55");
        round(c, -17, 16, 34, 40, 16, light);
        round(c, -43, 5, 15, 43, 7, coat); round(c, 28, 4, 15, 43, 7, coat);
        round(c, -25, 65, 20, 18, 6, dark); round(c, 7, 65, 20, 18, 6, dark);
        c.restore();
    }

    private void blocks(Canvas c) {
        background(c, "#183D3D", "#2A5F4A");
        circle(c, 275, 55, 45, "#6C9970");
        for (int i = 0; i < 5; i++) {
            float x = i * 84 - 22, y = 117 + ((i * 3) % 5) * 8;
            polygon(c, "#497E59", x, y, x + 43, y - 23, x + 87, y, x + 43, y + 23);
            polygon(c, "#2A4F39", x, y, x + 43, y + 23, x + 43, 230, x, 230);
            polygon(c, "#396844", x + 43, y + 23, x + 87, y, x + 87, 230, x + 43, 230);
        }
        round(c, 195, 93, 65, 61, 3, "#9EBC75");
        round(c, 204, 106, 15, 16, 1, "#203B35"); round(c, 237, 106, 15, 16, 1, "#203B35");
        round(c, 222, 122, 13, 19, 1, "#203B35");
        round(c, 214, 135, 29, 9, 1, "#203B35");
        round(c, 204, 154, 48, 45, 2, "#789C59");
        round(c, 204, 190, 18, 38, 1, "#536C43"); round(c, 235, 190, 18, 38, 1, "#536C43");
        star(c, 78, 47, 6, "#CBE891");
        round(c, 114, 53, 17, 17, 1, "#6C9D67"); round(c, 129, 39, 10, 10, 1, "#85B976");
        label(c, "BUILD YOUR WORLD", 24, 45, 12, "#D6F5BB");
    }

    private void dragon(Canvas c) {
        background(c, "#172B48", "#47718B");
        circle(c, 274, 54, 43, "#BAD5DB");
        polygon(c, "#294C69", 0, 190, 92, 80, 192, 192, 271, 109, 360, 201, 360, 230, 0, 230);
        polygon(c, "#507A88", 0, 226, 100, 149, 191, 229, 282, 169, 360, 220, 360, 230, 0, 230);
        polygon(c, "#273A50", 112, 100, 63, 46, 126, 65, 154, 109, 217, 51, 207, 95, 171, 129);
        polygon(c, "#1A2C3F", 127, 101, 89, 87, 124, 74, 153, 106, 183, 115, 170, 147, 136, 137, 119, 158, 87, 155, 117, 140);
        polygon(c, "#1A2C3F", 171, 122, 204, 103, 238, 110, 249, 123, 233, 133, 198, 133);
        polygon(c, "#1A2C3F", 207, 110, 207, 96, 218, 111, 229, 111, 237, 97, 237, 117);
        circle(c, 228, 119, 3.2f, "#C6E78A");
        line(c, 131, 133, 94, 163, 9, "#24384C"); line(c, 94, 163, 57, 160, 7, "#24384C");
        star(c, 48, 41, 3, "#CEE7E9"); star(c, 171, 29, 2, "#CEE7E9");
        label(c, "ADVENTURE AWAITS", 24, 45, 12, "#D3EAF2");
    }

    private void space(Canvas c) {
        background(c, "#13213C", "#35517B");
        for (int i = 0; i < 22; i++) circle(c, 15 + (i * 67) % 330, 13 + (i * 43) % 198, i % 3 == 0 ? 2 : 1, "#BCD4E5");
        circle(c, 259, 95, 65, "#618998"); circle(c, 250, 84, 62, "#83B4B5");
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(10); paint.setColor(color("#CFBA8A"));
        c.save(); c.rotate(-24, 251, 95); c.drawOval(new RectF(156, 69, 346, 117), paint); c.restore();
        paint.setStyle(Paint.Style.FILL);
        circle(c, 224, 60, 15, "#9BC7C3"); circle(c, 272, 112, 18, "#6BA0A6");
        c.save(); c.rotate(27, 129, 130);
        polygon(c, "#EFD5A1", 116, 153, 139, 153, 129, 200);
        polygon(c, "#A4C7C8", 109, 117, 95, 157, 117, 149);
        polygon(c, "#A4C7C8", 145, 117, 160, 157, 137, 149);
        round(c, 111, 85, 34, 72, 17, "#E4EEE8");
        polygon(c, "#C9DFD7", 112, 99, 128, 68, 144, 99);
        circle(c, 128, 115, 10, "#3D647B"); circle(c, 128, 115, 6, "#91C4CD");
        c.restore();
        star(c, 61, 71, 9, "#D8EFBC"); star(c, 329, 175, 7, "#E6D8B3");
        label(c, "STAY CURIOUS", 24, 40, 12, "#D7E9E5");
    }

    private void code(Canvas c) {
        background(c, "#223647", "#477675");
        for (int i = 0; i < 9; i++) line(c, i * 46, 0, i * 46, 230, 1, "#0BFFFFFF");
        for (int i = 0; i < 6; i++) line(c, 0, i * 45, 360, i * 45, 1, "#0BFFFFFF");
        round(c, 110, 52, 194, 130, 13, "#B2CDCB");
        round(c, 118, 60, 178, 111, 7, "#1B3541");
        round(c, 192, 181, 29, 17, 2, "#96B9B9"); round(c, 159, 196, 96, 8, 4, "#C4D8D1");
        line(c, 145, 91, 134, 102, 4, "#BFE795"); line(c, 134, 102, 145, 113, 4, "#BFE795");
        line(c, 161, 87, 152, 119, 3, "#78BCCA");
        line(c, 169, 91, 180, 102, 4, "#BFE795"); line(c, 180, 102, 169, 113, 4, "#BFE795");
        round(c, 135, 135, 87, 4, 2, "#84A9B2"); round(c, 135, 145, 120, 4, 2, "#4D7783");
        round(c, 50, 139, 49, 49, 12, "#9DC19B");
        circle(c, 65, 158, 4, "#274C4C"); circle(c, 84, 158, 4, "#274C4C");
        line(c, 66, 175, 83, 175, 3, "#274C4C");
        star(c, 316, 41, 6, "#DDE7BC");
        label(c, "MAKE SOMETHING", 24, 37, 12, "#D1E7D1");
    }

    private void course(Canvas c) {
        background(c, "#E2DAC8", "#AABDB4");
        circle(c, 292, 42, 65, "#EAE6D8");
        polygon(c, "#FCF5DF", 96, 74, 177, 85, 177, 184, 91, 167);
        polygon(c, "#E4EDD7", 177, 85, 258, 74, 264, 167, 177, 184);
        line(c, 177, 87, 177, 184, 3, "#8AA28B");
        for (int i = 0; i < 4; i++) {
            line(c, 108, 101 + i * 16, 156, 109 + i * 16, 3, "#BBC5A8");
            line(c, 198, 108 + i * 16, 246, 101 + i * 16, 3, "#A1B8A1");
        }
        star(c, 284, 139, 16, "#D0A969");
        circle(c, 61, 120, 13, "#839E8D");
        line(c, 61, 134, 61, 190, 5, "#6D8877");
        polygon(c, "#9BB29A", 61, 163, 34, 147, 40, 174, 61, 179);
    }

    private void label(Canvas c, String value, float x, float y, float size, String valueColor) {
        fill(valueColor);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextSize(size);
        c.drawText(value, x, y, paint);
    }
}
