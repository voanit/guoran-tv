package cn.guoran.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** 使用统一的 24 格图标坐标，避免字符字体导致返回、首页与播放图标上下错位。 */
public final class ControlIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private String icon;

    public ControlIconView(Context context, String icon, String description) {
        super(context); this.icon = icon; setContentDescription(description);
        paint.setColor(Color.WHITE); paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setIcon(String value) {
        icon = value; setContentDescription(value.equals("pause") ? "暂停" : "播放"); invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight()) * .54f;
        canvas.save(); canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2); canvas.scale(size / 24, size / 24);
        paint.setAlpha(isPressed() ? 160 : 240); paint.setStyle(Paint.Style.STROKE); path.reset();
        switch (icon) {
            case "back":
                path.moveTo(15, 4); path.lineTo(7, 12); path.lineTo(15, 20); break;
            case "home":
                path.moveTo(3, 10); path.lineTo(12, 3); path.lineTo(21, 10);
                path.moveTo(5, 9); path.lineTo(5, 21); path.lineTo(19, 21); path.lineTo(19, 9);
                path.moveTo(9, 21); path.lineTo(9, 14); path.lineTo(15, 14); path.lineTo(15, 21); break;
            case "folder":
                path.moveTo(3, 6); path.lineTo(10, 6); path.lineTo(12, 9); path.lineTo(21, 9);
                path.lineTo(21, 20); path.lineTo(3, 20); path.close(); break;
            case "pause":
                paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(6, 4, 10, 20, 1, 1, paint);
                canvas.drawRoundRect(14, 4, 18, 20, 1, 1, paint); break;
            case "rewind":
            case "forward":
                if (icon.equals("rewind")) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                path.moveTo(3, 5); path.lineTo(12, 12); path.lineTo(3, 19); path.close();
                path.moveTo(13, 5); path.lineTo(22, 12); path.lineTo(13, 19); path.close();
                paint.setStyle(Paint.Style.FILL); break;
            default:
                path.moveTo(7, 4); path.lineTo(20, 12); path.lineTo(7, 20); path.close(); paint.setStyle(Paint.Style.FILL); break;
        }
        canvas.drawPath(path, paint); canvas.restore();
    }
}
