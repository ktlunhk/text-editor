package com.example.droidpadplus;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Simple hand-drawn line-art icons for the toolbar (New, Open, Save, Save
 * All, Undo, Redo, Print). Drawn with plain Canvas/Path/Paint so they work
 * on every API level this app supports (minSdk 16) without needing vector
 * drawables, AppCompat, or bundled image assets.
 */
public class ToolbarIcon extends Drawable {

    public static final int NEW = 0;
    public static final int OPEN = 1;
    public static final int SAVE = 2;
    public static final int SAVE_ALL = 3;
    public static final int UNDO = 4;
    public static final int REDO = 5;
    public static final int PRINT = 6;

    private final int kind;
    private final int size;
    private final Paint paint;

    public ToolbarIcon(int kind, int sizePx, int color) {
        this.kind = kind;
        this.size = sizePx;
        this.paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.paint.setColor(color);
        this.paint.setStyle(Paint.Style.STROKE);
        this.paint.setStrokeWidth(Math.max(1.5f, sizePx / 11f));
        this.paint.setStrokeCap(Paint.Cap.ROUND);
        this.paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void draw(Canvas canvas) {
        RectF b = new RectF(getBounds());
        float inset = b.width() * 0.12f;
        b.inset(inset, inset);
        switch (kind) {
            case NEW:
                drawNew(canvas, b);
                break;
            case OPEN:
                drawOpen(canvas, b);
                break;
            case SAVE:
                drawSave(canvas, b);
                break;
            case SAVE_ALL:
                drawSaveAll(canvas, b);
                break;
            case UNDO:
                drawCurvedArrow(canvas, b, true);
                break;
            case REDO:
                drawCurvedArrow(canvas, b, false);
                break;
            case PRINT:
                drawPrint(canvas, b);
                break;
            default:
                break;
        }
    }

    private void drawNew(Canvas canvas, RectF b) {
        float foldSize = b.width() * 0.28f;
        Path page = new Path();
        page.moveTo(b.left, b.top);
        page.lineTo(b.right - foldSize, b.top);
        page.lineTo(b.right, b.top + foldSize);
        page.lineTo(b.right, b.bottom);
        page.lineTo(b.left, b.bottom);
        page.close();
        canvas.drawPath(page, paint);

        Path fold = new Path();
        fold.moveTo(b.right - foldSize, b.top);
        fold.lineTo(b.right - foldSize, b.top + foldSize);
        fold.lineTo(b.right, b.top + foldSize);
        canvas.drawPath(fold, paint);

        float cx = b.left + b.width() * 0.5f;
        float cy = b.top + b.height() * 0.68f;
        float armH = b.width() * 0.16f;
        float armV = b.height() * 0.14f;
        canvas.drawLine(cx - armH, cy, cx + armH, cy, paint);
        canvas.drawLine(cx, cy - armV, cx, cy + armV, paint);
    }

    private void drawOpen(Canvas canvas, RectF b) {
        float tabH = b.height() * 0.18f;
        Path folder = new Path();
        folder.moveTo(b.left, b.top + tabH);
        folder.lineTo(b.left, b.top);
        folder.lineTo(b.left + b.width() * 0.4f, b.top);
        folder.lineTo(b.left + b.width() * 0.5f, b.top + tabH);
        folder.lineTo(b.right, b.top + tabH);
        folder.lineTo(b.right, b.bottom);
        folder.lineTo(b.left, b.bottom);
        folder.close();
        canvas.drawPath(folder, paint);
    }

    private void drawSave(Canvas canvas, RectF b) {
        canvas.drawRoundRect(b, b.width() * 0.08f, b.width() * 0.08f, paint);
        float notchW = b.width() * 0.34f;
        float notchH = b.height() * 0.30f;
        RectF notch = new RectF(b.right - notchW, b.top, b.right, b.top + notchH);
        canvas.drawRect(notch, paint);
        float slotY = b.bottom - b.height() * 0.22f;
        canvas.drawLine(b.left + b.width() * 0.22f, slotY, b.right - b.width() * 0.22f, slotY, paint);
    }

    private void drawSaveAll(Canvas canvas, RectF b) {
        float offset = b.width() * 0.18f;
        RectF back = new RectF(b.left, b.top, b.right - offset, b.bottom - offset);
        RectF front = new RectF(b.left + offset, b.top + offset, b.right, b.bottom);
        canvas.drawRoundRect(back, back.width() * 0.1f, back.width() * 0.1f, paint);
        canvas.drawRoundRect(front, front.width() * 0.1f, front.width() * 0.1f, paint);
        float slotY = front.bottom - front.height() * 0.24f;
        canvas.drawLine(front.left + front.width() * 0.22f, slotY, front.right - front.width() * 0.22f, slotY, paint);
    }

    private void drawCurvedArrow(Canvas canvas, RectF b, boolean pointingLeft) {
        float cx = b.centerX();
        float cy = b.centerY() + b.height() * 0.08f;
        float radius = b.width() * 0.38f;
        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        float startAngle = pointingLeft ? -20f : 200f;
        float sweep = pointingLeft ? -230f : 230f;

        Path arc = new Path();
        arc.addArc(oval, startAngle, sweep);
        canvas.drawPath(arc, paint);

        float endAngle = startAngle + sweep;
        double rad = Math.toRadians(endAngle);
        float endX = (float) (cx + radius * Math.cos(rad));
        float endY = (float) (cy + radius * Math.sin(rad));
        float headLen = b.width() * 0.24f;
        double tangent = rad + (pointingLeft ? -Math.PI / 2 : Math.PI / 2);
        double a1 = tangent + Math.toRadians(150);
        double a2 = tangent - Math.toRadians(150);
        canvas.drawLine(endX, endY,
                endX + headLen * (float) Math.cos(a1), endY + headLen * (float) Math.sin(a1), paint);
        canvas.drawLine(endX, endY,
                endX + headLen * (float) Math.cos(a2), endY + headLen * (float) Math.sin(a2), paint);
    }

    private void drawPrint(Canvas canvas, RectF b) {
        float bodyTop = b.top + b.height() * 0.32f;
        float bodyBottom = b.top + b.height() * 0.72f;
        canvas.drawRect(new RectF(b.left, bodyTop, b.right, bodyBottom), paint);
        canvas.drawRect(new RectF(b.left + b.width() * 0.2f, b.top, b.right - b.width() * 0.2f, bodyTop + 1), paint);
        canvas.drawRect(new RectF(b.left + b.width() * 0.16f, bodyBottom - 1, b.right - b.width() * 0.16f, b.bottom), paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
