package com.texteditor;

import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Represents one open editor tab with keystroke-level undo/redo.
 */
public class EditorTab {

    public String title;
    public Uri uri;
    public boolean modified;
    public View rootView;
    public EditText editor;
    public LineNumberView lineNumbers;
    public String encoding;
    public String language;
    public boolean languageManual;
    public int lineColorNormal;
    public int lineColorCurrent;
    /** Saved scroll position when switching tabs. */
    public int savedScrollY;
    /** Saved cursor when switching tabs. */
    public int savedCursor;

    /** One text change: at 'start', 'removed' was replaced by 'inserted'. */
    private static class Edit {
        int start;
        String removed;
        String inserted;
        long time;
        Edit(int start, String removed, String inserted, long time) {
            this.start = start;
            this.removed = removed;
            this.inserted = inserted;
            this.time = time;
        }
    }

    private static final int UNDO_LIMIT = 1000;
    private ArrayList undoStack;   // of Edit
    private ArrayList redoStack;   // of Edit
    private boolean isUndoRedo;
    private int pendingStart;
    private String pendingRemoved = "";

    public EditorTab(String title) {
        this.title = title;
        this.uri = null;
        this.modified = false;
        this.encoding = "UTF-8";
        this.language = "text";
        this.languageManual = false;
        this.lineColorNormal = Color.parseColor("#888888");
        this.lineColorCurrent = Color.parseColor("#BB86FC");
        this.undoStack = new ArrayList();
        this.redoStack = new ArrayList();
        this.isUndoRedo = false;
    }

    public String getContent() {
        if (editor != null) {
            return editor.getText().toString();
        }
        return "";
    }

    public void setContent(String content) {
        if (editor != null) {
            isUndoRedo = true;
            bulkLoad = true;
            try {
                editor.setText(content);
            } finally {
                bulkLoad = false;
                isUndoRedo = false;
            }
            undoStack.clear();
            redoStack.clear();
            updateLineNumbers();
        }
    }

    /** Called from TextWatcher.beforeTextChanged: remember what is about to be replaced. */
    public void beforeChange(CharSequence s, int start, int count) {
        if (isUndoRedo || bulkLoad) {
            return;
        }
        pendingStart = start;
        pendingRemoved = count > 0 ? s.subSequence(start, start + count).toString() : "";
    }

    /** Called from TextWatcher.onTextChanged: record only the changed part (not the whole text). */
    public void afterChange(CharSequence s, int start, int count) {
        if (isUndoRedo || bulkLoad) {
            return;
        }
        String inserted = count > 0 ? s.subSequence(start, start + count).toString() : "";
        if (pendingRemoved.length() == 0 && inserted.length() == 0) {
            return;
        }
        pushEdit(start, pendingRemoved, inserted);
        pendingRemoved = "";
    }

    private void pushEdit(int start, String removed, String inserted) {
        long now = System.currentTimeMillis();
        redoStack.clear();
        // One undo step per keystroke (no merging).
        undoStack.add(new Edit(start, removed, inserted, now));
        while (undoStack.size() > UNDO_LIMIT) {
            undoStack.remove(0);
        }
    }

    public void undo() {
        if (undoStack.size() == 0 || editor == null) {
            return;
        }
        Edit e = (Edit) undoStack.remove(undoStack.size() - 1);
        if (!applyEdit(e.start, e.inserted.length(), e.removed)) {
            undoStack.clear();
            redoStack.clear();
            return;
        }
        redoStack.add(e);
        modified = true;
        updateLineNumbers();
    }

    public void redo() {
        if (redoStack.size() == 0 || editor == null) {
            return;
        }
        Edit e = (Edit) redoStack.remove(redoStack.size() - 1);
        if (!applyEdit(e.start, e.removed.length(), e.inserted)) {
            undoStack.clear();
            redoStack.clear();
            return;
        }
        undoStack.add(e);
        modified = true;
        updateLineNumbers();
    }

    /** Replaces [start, start+oldLen) with text, touching only that range; cursor goes after it. */
    private boolean applyEdit(int start, int oldLen, String text) {
        android.text.Editable ed = editor.getText();
        if (start < 0 || start + oldLen > ed.length()) {
            return false;
        }
        isUndoRedo = true;
        try {
            ed.replace(start, start + oldLen, text);
            editor.setSelection(Math.min(ed.length(), start + text.length()));
        } finally {
            isUndoRedo = false;
        }
        return true;
    }

    /** True while setContent() is loading text; lets the TextWatcher skip per-change work. */
    public boolean bulkLoad;
    /** Char range currently carrying syntax-highlight spans (large files only highlight what is near the viewport). */
    public int hlStart = 0;
    public int hlEnd = 0;
    private int lastTotalLines = 1;
    private int lastVer = -1;

    /** Refreshes the gutter: recount lines only if the text changed, then repaint visible rows. */
    public void updateLineNumbers() {
        if (lineNumbers == null || editor == null) {
            return;
        }
        if (lineNumbers.getVisibility() != View.VISIBLE) {
            return;
        }
        int ver = (editor instanceof SyncedEditText) ? ((SyncedEditText) editor).getTextVersion() : -1;
        if (ver == -1 || ver != lastVer) {
            CharSequence text = editor.getText();
            int n = text.length();
            int lines = 1;
            for (int i = 0; i < n; i++) {
                if (text.charAt(i) == '\n') lines++;
            }
            lastTotalLines = lines;
            lastVer = ver;
        }
        lineNumbers.attach(editor);
        lineNumbers.setColors(lineColorNormal, lineColorCurrent);
        updateGutterWidth(lastTotalLines);
        lineNumbers.invalidate();
    }

    private static final int GUTTER_MIN_DIGITS = 2;

    /**
     * Resizes the line-number gutter so it is exactly wide enough for the
     * current font size and the number of digits needed for the largest
     * line number, plus the gutter's own padding. Because the gutter uses a
     * monospace typeface, every digit has the same advance width, so a
     * string of any digit is a valid width sample. Only touches
     * LayoutParams when the target width actually changes, to avoid
     * triggering redundant layout/size-changed callbacks.
     */
    private void updateGutterWidth(int totalLogicalLines) {
        int digits = String.valueOf(Math.max(totalLogicalLines, 1)).length();
        if (digits < GUTTER_MIN_DIGITS) digits = GUTTER_MIN_DIGITS;

        StringBuilder widthSample = new StringBuilder();
        for (int i = 0; i < digits; i++) widthSample.append('0');
        float textWidth = lineNumbers.getPaint().measureText(widthSample.toString());

        int desiredWidth = Math.round(textWidth)
                + lineNumbers.getPaddingLeft()
                + lineNumbers.getPaddingRight()
                + 2; // small buffer so glyphs never clip or wrap at the edge

        android.view.ViewGroup.LayoutParams lp = lineNumbers.getLayoutParams();
        if (lp != null && lp.width != desiredWidth) {
            lp.width = desiredWidth;
            lineNumbers.setLayoutParams(lp);
        }
    }

    public void syncLineNumberScroll() {
        if (lineNumbers != null) {
            lineNumbers.invalidate();
        }
    }

}
