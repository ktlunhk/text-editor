package com.example.droidpadplus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.OverScroller;

/**
 * EditText that reports scrolling and layout-size changes so the line-number
 * gutter can stay locked to the editor on old Android versions supported by AIDE.
 */
public class SyncedEditText extends EditText {

    public interface Listener {
        void onEditorScrolled(int scrollY);
        void onEditorSizeChanged();
    }

    private Listener listener;
    private int textVersion = 0;

    /** Incremented on every text change; lets helpers cache work per document state. */
    public int getTextVersion() {
        return textVersion;
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        textVersion++;
    }

    public SyncedEditText(Context context) {
        super(context);
        initThumb();
        initEnterKeepsIndent();
    }

    public SyncedEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        initThumb();
        initEnterKeepsIndent();
    }

    public SyncedEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initThumb();
        initEnterKeepsIndent();
    }

    /**
     * Pressing Enter with the cursor in front of the first character of a line (after the
     * indentation) would leave the indentation on the upper line and push the text to the new
     * line with none. Keep the indentation with the text instead. Enter at the end of a line
     * is untouched.
     */
    private void initEnterKeepsIndent() {
        InputFilter enter = new InputFilter() {
            public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
                                       int dstart, int dend) {
                if (end - start != 1 || source.charAt(start) != '\n') return null;
                int ls = dstart;
                while (ls > 0 && dest.charAt(ls - 1) != '\n') ls--;
                if (ls == dstart) return null; // cursor at column 1: the text keeps its own indent
                for (int i = ls; i < dstart; i++) {
                    char c = dest.charAt(i);
                    if (c != ' ' && c != '\t') return null; // cursor is after real text
                }
                boolean hasText = false;
                for (int i = dend; i < dest.length(); i++) {
                    char c = dest.charAt(i);
                    if (c == '\n') break;
                    if (c != ' ' && c != '\t' && c != '\r') {
                        hasText = true;
                        break;
                    }
                }
                if (!hasText) return null; // nothing to move to the new line
                return "\n" + dest.subSequence(ls, dstart).toString();
            }
        };
        InputFilter[] old = getFilters();
        InputFilter[] all = new InputFilter[old.length + 1];
        System.arraycopy(old, 0, all, 0, old.length);
        all[old.length] = enter;
        setFilters(all);
    }

    public void setEditorListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
        showThumb();
        if (listener != null) listener.onEditorScrolled(vert);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (listener != null && (w != oldw || h != oldh)) listener.onEditorSizeChanged();
    }

    // ---- Horizontal panning (used when word wrap is off) ----
    private boolean hScrollEnabled;
    private boolean inTouch;
    private boolean allowX;
    private boolean hDragging;
    private boolean vDecided;
    private float downX, downY, lastX;
    private int slop = -1;
    private int cacheLen = -1, cacheLines = -1, cacheWidth = -1, cacheMax = 0;

    @Override
    public void setHorizontallyScrolling(boolean whether) {
        super.setHorizontallyScrolling(whether);
        hScrollEnabled = whether;
    }

    /** Largest legal horizontal scroll offset for the current text. */
    private int maxScrollX() {
        if (!hScrollEnabled) return 0;
        Layout l = getLayout();
        if (l == null) return 0;
        int len = getText().length();
        int lines = l.getLineCount();
        int w = getWidth();
        if (len == cacheLen && lines == cacheLines && w == cacheWidth) return cacheMax;
        float widest = 0;
        for (int i = 0; i < lines; i++) {
            float lw = l.getLineWidth(i);
            if (lw > widest) widest = lw;
        }
        int max = (int) Math.ceil(widest) + getPaddingLeft() + getPaddingRight() - w;
        if (max < 0) max = 0;
        cacheLen = len;
        cacheLines = lines;
        cacheWidth = w;
        cacheMax = max;
        return max;
    }

    @Override
    public void scrollTo(int x, int y) {
        // While a finger is down, only our own code may change the horizontal offset,
        // so diagonal vertical drags don't drift sideways.
        if (inTouch && !allowX) x = getScrollX();
        super.scrollTo(x, y);
    }

    private void setHScroll(int x) {
        int max = maxScrollX();
        if (x < 0) x = 0;
        if (x > max) x = max;
        allowX = true;
        try {
            scrollTo(x, getScrollY());
        } finally {
            allowX = false;
        }
    }

    // ---- Cheap line/column lookup (cached, incremental) ----
    private final char[] lcBuf = new char[4096];
    private int lcVer = -1;
    private int lcOff = 0;
    private int lcNl = 0;

    private int countNewlines(CharSequence t, int from, int to) {
        int n = 0;
        int i = from;
        while (i < to) {
            int e = Math.min(to, i + lcBuf.length);
            TextUtils.getChars(t, i, e, lcBuf, 0);
            int len = e - i;
            for (int k = 0; k < len; k++) {
                if (lcBuf[k] == '\n') n++;
            }
            i = e;
        }
        return n;
    }

    /** 1-based {line, column} at pos. Moves from the last lookup, so nearby cursors cost almost nothing. */
    public int[] lineColAt(int pos) {
        CharSequence t = getText();
        int len = t.length();
        if (pos < 0) pos = 0;
        if (pos > len) pos = len;
        if (lcVer != textVersion) {
            lcVer = textVersion;
            lcOff = 0;
            lcNl = 0;
        }
        if (pos >= lcOff) {
            lcNl += countNewlines(t, lcOff, pos);
        } else {
            lcNl -= countNewlines(t, pos, lcOff);
        }
        lcOff = pos;
        int i = pos;
        while (i > 0 && t.charAt(i - 1) != '\n') i--;
        return new int[]{lcNl + 1, pos - i + 1};
    }

    // ---- Jump helpers (used by the gutter double-tap) ----
    public void jumpToTop() {
        stopFling();
        scrollTo(getScrollX(), 0);
    }

    public void jumpToEnd() {
        stopFling();
        scrollTo(getScrollX(), maxScrollY());
    }

    // ---- Fling (momentum) ----
    private OverScroller flinger;
    private VelocityTracker velocity;
    private boolean swallowGesture;

    private void stopFling() {
        if (flinger != null && !flinger.isFinished()) {
            flinger.abortAnimation();
        }
    }

    private boolean isFlinging() {
        return flinger != null && !flinger.isFinished();
    }

    private void startFling(float velocityY) {
        if (flinger == null) flinger = new OverScroller(getContext());
        int max = maxScrollY();
        if (max <= 0) return;
        // Same idea as the drag gain: longer files travel further per flick.
        float v = -velocityY * maxGain();
        flinger.fling(0, getScrollY(), 0, (int) v, 0, 0, 0, max);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (flinger != null && flinger.computeScrollOffset()) {
            scrollTo(getScrollX(), flinger.getCurrY());
            postInvalidateOnAnimation();
        } else {
            super.computeScroll();
        }
    }

    // ---- Zoom anchoring ----
    // Changing the text size reflows the document. Without help, the scroll offset stays the
    // same in pixels (wrong line) and TextView's pre-draw then scrolls to the cursor. So we
    // remember which line is at the top, block the cursor auto-scroll, and put that line back.
    private boolean zoomAnchoring;
    private int anchorOff = -1;
    private float anchorFrac;

    /** Sets the text size (sp) and keeps the line at the top of the view where it was. */
    public void setZoomTextSize(float sp) {
        float wantPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                getResources().getDisplayMetrics());
        if (Math.abs(getTextSize() - wantPx) < 0.01f) return; // nothing will re-layout
        Layout l = getLayout();
        if (l != null && l.getLineCount() > 0) {
            int sy = getScrollY();
            int line = l.getLineForVertical(sy);
            int top = l.getLineTop(line);
            int h = l.getLineBottom(line) - top;
            anchorOff = l.getLineStart(line);
            float f = h > 0 ? (sy - top) / (float) h : 0f;
            anchorFrac = f < 0f ? 0f : (f > 1f ? 1f : f);
        } else {
            anchorOff = -1;
        }
        stopFling();
        zoomAnchoring = true;
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    @Override
    public boolean bringPointIntoView(int offset) {
        // Don't let the cursor (maybe far away) steal the scroll position while zooming.
        if (zoomAnchoring) return false;
        return super.bringPointIntoView(offset);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (anchorOff >= 0) restoreAnchor();
    }

    private void restoreAnchor() {
        Layout l = getLayout();
        if (l == null || l.getLineCount() == 0) return;
        int off = Math.min(anchorOff, getText().length());
        anchorOff = -1;
        int line = l.getLineForOffset(off);
        int top = l.getLineTop(line);
        int h = l.getLineBottom(line) - top;
        int y = top + (int) (anchorFrac * h);
        int max = maxScrollY();
        if (y > max) y = max;
        if (y < 0) y = 0;
        scrollTo(getScrollX(), y);
        if (listener != null) listener.onEditorScrolled(getScrollY());
    }

    // ---- Thumb fade ----
    private static final long THUMB_HOLD_MS = 900;
    private static final long THUMB_FADE_MS = 350;
    private long thumbVisibleUntil = 0;

    private void showThumb() {
        thumbVisibleUntil = SystemClock.uptimeMillis() + THUMB_HOLD_MS + THUMB_FADE_MS;
    }

    /** 0..1 visibility of the thumb right now (1 while held, fading to 0). */
    private float thumbVisibility() {
        if (thumbDragging) return 1f;
        long remaining = thumbVisibleUntil - SystemClock.uptimeMillis();
        if (remaining <= 0) return 0f;
        if (remaining >= THUMB_FADE_MS) return 1f;
        return remaining / (float) THUMB_FADE_MS;
    }

    // ---- Draggable fast-scroll thumb (right edge) ----
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF thumbRect = new RectF();
    private boolean thumbDragging;
    private float thumbGrabOffset;
    private float density = 1f;

    private void initThumb() {
        density = getResources().getDisplayMetrics().density;
        // Replace the passive system scrollbar with our draggable one.
        setVerticalScrollBarEnabled(false);
    }

    private int maxScrollY() {
        Layout l = getLayout();
        if (l == null) return 0;
        int contentH = l.getHeight() + getCompoundPaddingTop() + getCompoundPaddingBottom();
        int max = contentH - getHeight();
        return max > 0 ? max : 0;
    }

    private float thumbHeight(int max) {
        int viewH = getHeight();
        int contentH = max + viewH;
        float h = viewH * (float) viewH / contentH;
        float min = 48f * density;
        if (h < min) h = min;
        if (h > viewH) h = viewH;
        return h;
    }

    /** Thumb top in view coordinates (not scrolled content coordinates). */
    private float thumbTop(int max, float thumbH) {
        float track = getHeight() - thumbH;
        if (max <= 0 || track <= 0) return 0;
        return track * Math.min(1f, Math.max(0f, getScrollY() / (float) max));
    }

    private final IndentGuides guides = new IndentGuides();

    /** Enables indent guides; tagMode = HTML/XML tag-pair highlighting. */
    public void configureGuides(boolean enabled, boolean tagMode, boolean dark, int defaultIndent) {
        if (guides.configure(enabled, tagMode, dark, defaultIndent)) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        zoomAnchoring = false; // pre-draw (cursor auto-scroll) has already run
        // Drawn after the text: guides only sit in the blank indent area, and this way the
        // current-line background cannot hide them.
        guides.draw(canvas, this, textVersion, density);
        int max = maxScrollY();
        if (max <= 0) return;
        float vis = thumbVisibility();
        if (vis <= 0f) return;
        float h = thumbHeight(max);
        float top = thumbTop(max, h);
        float w = (thumbDragging ? 12f : 5f) * density;
        float right = getScrollX() + getWidth() - 2f * density;
        thumbRect.set(right - w, getScrollY() + top, right, getScrollY() + top + h);
        int baseAlpha = thumbDragging ? 0xDD : 0x99;
        int rgb = thumbDragging ? 0xBB86FC : 0xA0A0A0;
        thumbPaint.setColor(((int) (baseAlpha * vis) << 24) | rgb);
        canvas.drawRoundRect(thumbRect, w / 2f, w / 2f, thumbPaint);
        if (vis < 1f) postInvalidateOnAnimation(); // keep animating the fade-out
        else if (!thumbDragging) postInvalidateDelayed(THUMB_HOLD_MS + 20);
    }

    /** Returns true if the touch starts on (or right next to) the thumb. */
    private boolean hitThumb(MotionEvent ev) {
        int max = maxScrollY();
        if (max <= 0) return false;
        float h = thumbHeight(max);
        float top = thumbTop(max, h);
        float pad = 16f * density;
        float zone = (thumbVisibility() > 0f ? 36f : 24f) * density;
        return ev.getX() >= getWidth() - zone
                && ev.getY() >= top - pad && ev.getY() <= top + h + pad;
    }

    private boolean handleThumbTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (ev.getPointerCount() == 1 && hitThumb(ev)) {
                int max = maxScrollY();
                float h = thumbHeight(max);
                thumbGrabOffset = ev.getY() - thumbTop(max, h);
                thumbDragging = true;
                stopFling();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            return false;
        }
        if (!thumbDragging) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            int max = maxScrollY();
            float h = thumbHeight(max);
            float track = getHeight() - h;
            float frac = track > 0 ? (ev.getY() - thumbGrabOffset) / track : 0f;
            if (frac < 0f) frac = 0f;
            if (frac > 1f) frac = 1f;
            scrollTo(getScrollX(), (int) (frac * max));
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            thumbDragging = false;
            invalidate();
            return true;
        }
        return true;
    }

    // ---- Finger scrolling with size/speed-based gain ----
    private boolean vDragging;
    private float lastY;
    private float scrollYf;
    private float dragSpeed;      // smoothed finger speed, dp per ms
    private long lastMoveTime;
    private int activePointerId = -1;

    /** Max extra speed multiplier for this document: bigger files scroll faster on a quick drag. */
    private float maxGain() {
        int max = maxScrollY();
        float screens = (max + getHeight()) / (float) Math.max(1, getHeight());
        float g = screens / 10f;
        if (g < 1f) g = 1f;
        if (g > 8f) g = 8f;
        return g;
    }

    /** Slow drags stay 1:1 for precise reading; quick drags are amplified up to maxGain(). */
    private float gainForSpeed(float dpPerMs, float maxGain) {
        float t = (dpPerMs - 0.3f) / (1.5f - 0.3f);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return 1f + (maxGain - 1f) * t;
    }

    private void cancelSuper(MotionEvent ev) {
        MotionEvent c = MotionEvent.obtain(ev);
        c.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(c);
        c.recycle();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (handleThumbTouch(ev)) return true;
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
        if (slop < 0) slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            inTouch = true;
            hDragging = false;
            vDragging = false;
            vDecided = false;
            downX = lastX = ev.getX();
            downY = lastY = ev.getY();
            activePointerId = ev.getPointerId(0);
            dragSpeed = 0f;
            lastMoveTime = ev.getEventTime();
            showThumb();
            // A touch that stops a fling must not also place the cursor.
            swallowGesture = isFlinging();
            if (swallowGesture) {
                stopFling();
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (ev.getPointerId(0) != activePointerId) {
                // The tracked finger changed (e.g. after a pinch): re-anchor, don't jump.
                activePointerId = ev.getPointerId(0);
                lastX = ev.getX();
                lastY = ev.getY();
                lastMoveTime = ev.getEventTime();
                return true;
            }
            if (swallowGesture && !vDragging && !hDragging && !vDecided) {
                float sdx = Math.abs(ev.getX() - downX);
                float sdy = Math.abs(ev.getY() - downY);
                if (sdx <= slop && sdy <= slop) return true;
            }
            if (hDragging) {
                float x = ev.getX();
                setHScroll(getScrollX() + (int) (lastX - x));
                lastX = x;
                return true;
            }
            if (vDragging) {
                float y = ev.getY();
                long now = ev.getEventTime();
                long dt = Math.max(1, now - lastMoveTime);
                float dy = lastY - y;
                float inst = Math.abs(dy) / density / dt;
                dragSpeed = dragSpeed * 0.6f + inst * 0.4f;
                float gain = gainForSpeed(dragSpeed, maxGain());
                int max = maxScrollY();
                scrollYf += dy * gain;
                if (scrollYf < 0f) scrollYf = 0f;
                if (scrollYf > max) scrollYf = max;
                scrollTo(getScrollX(), (int) scrollYf);
                lastY = y;
                lastMoveTime = now;
                return true;
            }
            if (!vDecided) {
                float dx = Math.abs(ev.getX() - downX);
                float dy = Math.abs(ev.getY() - downY);
                if (hScrollEnabled && dx > slop && dx > dy) {
                    if (maxScrollY() >= 0 && maxScrollX() > 0) {
                        hDragging = true;
                        lastX = ev.getX();
                        cancelSuper(ev);
                        return true;
                    }
                } else if (dy > slop) {
                    vDecided = true;
                    // Leave an active text selection to the system; otherwise take over.
                    if ((swallowGesture || getSelectionStart() == getSelectionEnd()) && maxScrollY() > 0) {
                        vDragging = true;
                        scrollYf = getScrollY();
                        lastY = ev.getY();
                        lastMoveTime = ev.getEventTime();
                        cancelSuper(ev);
                        return true;
                    }
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            inTouch = false;
            boolean wasV = vDragging;
            boolean wasSwallow = swallowGesture;
            swallowGesture = false;
            if (wasV && action == MotionEvent.ACTION_UP && velocity != null) {
                velocity.computeCurrentVelocity(1000, 12000f * density);
                float vy = velocity.getYVelocity();
                if (Math.abs(vy) > 300f * density) {
                    startFling(vy);
                }
            }
            if (velocity != null) {
                velocity.recycle();
                velocity = null;
            }
            if (hDragging || vDragging) {
                hDragging = false;
                vDragging = false;
                return true;
            }
            if (wasSwallow) return true;
        }
        return super.onTouchEvent(ev);
    }
}
