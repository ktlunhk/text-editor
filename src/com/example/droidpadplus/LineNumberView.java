package com.example.droidpadplus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Line-number gutter that draws ONLY the rows currently visible, using the editor's own
 * Layout for positions. It extends TextView purely so the existing calls
 * (setTextSize, setTypeface, setBackgroundColor, setVisibility ...) keep working; its text
 * stays empty, so a big file no longer means a giant gutter string that must be rebuilt
 * and re-laid-out on every edit, scroll or zoom.
 */
public class LineNumberView extends TextView {

    private EditText editor;
    private int colorNormal = 0xFF888888;
    private int colorCurrent = 0xFFFFFFFF;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final char[] buf = new char[4096];

    // Cached newline count so a scroll frame only counts the newlines it moved over.
    private int anchorOff = 0;
    private int anchorNl = 0;
    private int anchorVer = -1;

    private GestureDetector tapDetector;

    public LineNumberView(Context context) {
        super(context);
        initTap(context);
    }

    public LineNumberView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initTap(context);
    }

    public LineNumberView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initTap(context);
    }

    /** Double-tap the gutter: upper half jumps to the top of the file, lower half to the end. */
    private void initTap(Context context) {
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            public boolean onDown(MotionEvent e) {
                return true;
            }

            public boolean onDoubleTap(MotionEvent e) {
                if (editor instanceof SyncedEditText) {
                    SyncedEditText se = (SyncedEditText) editor;
                    if (e.getY() < getHeight() / 2f) {
                        se.jumpToTop();
                    } else {
                        se.jumpToEnd();
                    }
                }
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (tapDetector != null) {
            tapDetector.onTouchEvent(ev);
        }
        return true;
    }

    public void attach(EditText editor) {
        this.editor = editor;
    }

    public void setColors(int normal, int current) {
        colorNormal = normal;
        colorCurrent = current;
    }

    private int countNewlines(CharSequence t, int from, int to) {
        int n = 0;
        int i = from;
        while (i < to) {
            int e = Math.min(to, i + buf.length);
            TextUtils.getChars(t, i, e, buf, 0);
            int len = e - i;
            for (int k = 0; k < len; k++) {
                if (buf[k] == '\n') n++;
            }
            i = e;
        }
        return n;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (editor == null) return;
        Layout l = editor.getLayout();
        if (l == null || l.getLineCount() == 0) return;
        CharSequence text = editor.getText();
        int textLen = text.length();

        int scrollY = editor.getScrollY();
        int padTop = editor.getExtendedPaddingTop();
        int topLayoutY = Math.max(0, scrollY - padTop);
        int bottomLayoutY = Math.max(0, scrollY - padTop + getHeight());
        int first = l.getLineForVertical(topLayoutY);
        int last = Math.min(l.getLineCount() - 1, l.getLineForVertical(bottomLayoutY));

        int ver = (editor instanceof SyncedEditText) ? ((SyncedEditText) editor).getTextVersion() : -2;
        if (ver != anchorVer || ver == -2) {
            anchorOff = 0;
            anchorNl = 0;
            anchorVer = ver;
        }
        int ls = Math.min(l.getLineStart(first), textLen);
        int nl;
        if (ls >= anchorOff) {
            nl = anchorNl + countNewlines(text, anchorOff, ls);
        } else {
            nl = anchorNl - countNewlines(text, ls, Math.min(anchorOff, textLen));
        }
        anchorOff = ls;
        anchorNl = nl;

        // Visual row where the cursor's logical line begins.
        int sel = editor.getSelectionStart();
        int curStartRow = -1;
        if (sel >= 0 && sel <= textLen) {
            int cv = l.getLineForOffset(sel);
            while (cv > 0) {
                int s = l.getLineStart(cv);
                if (s <= 0 || s > textLen || text.charAt(s - 1) == '\n') break;
                cv--;
            }
            curStartRow = cv;
        }

        paint.setTypeface(getTypeface());
        paint.setTextSize(getTextSize());
        paint.setTextAlign(Paint.Align.RIGHT);
        float x = getWidth() - getPaddingRight();

        int cur = nl + 1;
        for (int v = first; v <= last; v++) {
            int s = l.getLineStart(v);
            boolean start = (s <= 0) || (s <= textLen && text.charAt(s - 1) == '\n');
            if (v > first && start) cur++;
            if (!start) continue;
            boolean isCur = (v == curStartRow);
            paint.setColor(isCur ? colorCurrent : colorNormal);
            paint.setFakeBoldText(isCur);
            float y = padTop + l.getLineBaseline(v) - scrollY;
            canvas.drawText(String.valueOf(cur), x, y, paint);
        }
    }
}
