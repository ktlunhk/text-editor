package com.example.droidpadplus;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;

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

    public SyncedEditText(Context context) {
        super(context);
    }

    public SyncedEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SyncedEditText(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setEditorListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
        if (listener != null) listener.onEditorScrolled(vert);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (listener != null && (w != oldw || h != oldh)) listener.onEditorSizeChanged();
    }
}
