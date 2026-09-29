package com.example.droidpadplus;

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
    public TextView lineNumbers;
    public String encoding;
    public String language;
    public boolean languageManual;
    public int lineColorNormal;
    public int lineColorCurrent;
    /** Saved scroll position when switching tabs. */
    public int savedScrollY;
    /** Saved cursor when switching tabs. */
    public int savedCursor;

    private static final int UNDO_LIMIT = 80;
    private ArrayList undoStack;
    private ArrayList redoStack;
    private boolean isUndoRedo;

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
        // Initial empty state
        this.undoStack.add("");
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
            lastGutterText = null;
            undoStack.clear();
            redoStack.clear();
            undoStack.add(content);
            updateLineNumbers();
        }
    }

    public void onTextChanged(String content) {
        if (isUndoRedo) {
            return;
        }
        // Avoid pushing identical consecutive states
        if (undoStack.size() > 0) {
            String last = (String) undoStack.get(undoStack.size() - 1);
            if (last.equals(content)) {
                return;
            }
        }
        undoStack.add(content);
        while (undoStack.size() > UNDO_LIMIT) {
            undoStack.remove(0);
        }
        redoStack.clear();
    }

    public void undo() {
        if (undoStack.size() <= 1 || editor == null) {
            return;
        }
        String current = (String) undoStack.remove(undoStack.size() - 1);
        redoStack.add(current);
        String previous = (String) undoStack.get(undoStack.size() - 1);
        isUndoRedo = true;
        int sel = editor.getSelectionStart();
        editor.setText(previous);
        int newLen = previous.length();
        if (sel < 0) sel = 0;
        editor.setSelection(Math.min(sel, newLen));
        isUndoRedo = false;
        updateLineNumbers();
        modified = true;
    }

    public void redo() {
        if (redoStack.size() == 0 || editor == null) {
            return;
        }
        String next = (String) redoStack.remove(redoStack.size() - 1);
        undoStack.add(next);
        isUndoRedo = true;
        int sel = editor.getSelectionStart();
        editor.setText(next);
        int newLen = next.length();
        if (sel < 0) sel = 0;
        editor.setSelection(Math.min(sel, newLen));
        isUndoRedo = false;
        updateLineNumbers();
        modified = true;
    }

    /** True while setContent() is loading text; lets the TextWatcher skip per-change work. */
    public boolean bulkLoad;
    /** Char range currently carrying syntax-highlight spans (large files only highlight what is near the viewport). */
    public int hlStart = 0;
    public int hlEnd = 0;
    private String lastGutterText = null;
    private int lastCurStart = -1;
    private int lastCurEnd = -1;
    private int lastTotalLines = -1;

    public void updateLineNumbers() {
        if (lineNumbers == null || editor == null) {
            return;
        }
        if (lineNumbers.getVisibility() != View.VISIBLE) {
            return;
        }

        // Work on the Editable directly - no toString() copy of the whole document.
        CharSequence text = editor.getText();
        int textLen = text.length();
        int pos = editor.getSelectionStart();
        if (pos < 0) pos = 0;
        if (pos > textLen) pos = textLen;

        android.text.Layout layout = editor.getLayout();
        StringBuilder sb = new StringBuilder();
        int curStart = -1;
        int curEnd = -1;
        int totalLogicalLines = 1;

        if (layout != null) {
            // One gutter row per VISUAL row; wrapped continuation rows stay blank.
            int visualLines = layout.getLineCount();
            int curVisual = -1;
            try {
                curVisual = layout.getLineForOffset(Math.min(pos, layout.getText().length()));
            } catch (Exception e) {
                curVisual = -1;
            }
            int logical = 0;
            int numStart = 0;
            int numEnd = 0;
            for (int v = 0; v < visualLines; v++) {
                if (v > 0) sb.append('\n');
                int lineStart = layout.getLineStart(v);
                boolean first = (lineStart == 0)
                        || (lineStart > 0 && lineStart <= textLen
                        && text.charAt(lineStart - 1) == '\n');
                if (first) {
                    logical++;
                    numStart = sb.length();
                    sb.append(logical);
                    numEnd = sb.length();
                }
                if (v == curVisual) {
                    curStart = numStart;
                    curEnd = numEnd;
                }
            }
            if (logical < 1) logical = 1;
            totalLogicalLines = logical;
        } else {
            // Layout not ready yet: logical lines only.
            int cur = 1;
            int lines = 1;
            for (int i = 0; i < textLen; i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                    if (i < pos) cur++;
                }
            }
            for (int i = 1; i <= lines; i++) {
                if (i > 1) sb.append('\n');
                int st = sb.length();
                sb.append(i);
                if (i == cur) {
                    curStart = st;
                    curEnd = sb.length();
                }
            }
            totalLogicalLines = lines;
        }

        String gutter = sb.toString();
        // Nothing changed -> skip the expensive TextView relayout entirely.
        if (lastGutterText != null && lastGutterText.equals(gutter)
                && lastCurStart == curStart && lastCurEnd == curEnd) {
            updateGutterWidth(totalLogicalLines);
            syncLineNumberScroll();
            return;
        }
        lastGutterText = gutter;
        lastCurStart = curStart;
        lastCurEnd = curEnd;
        lastTotalLines = totalLogicalLines;

        // Only 2 spans in total (base colour + current line) instead of one per line.
        SpannableStringBuilder ssb = new SpannableStringBuilder(gutter);
        if (gutter.length() > 0) {
            ssb.setSpan(new ForegroundColorSpan(lineColorNormal),
                    0, gutter.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (curStart >= 0 && curEnd > curStart && curEnd <= gutter.length()) {
            ssb.setSpan(new ForegroundColorSpan(lineColorCurrent),
                    curStart, curEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new StyleSpan(Typeface.BOLD),
                    curStart, curEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        lineNumbers.setText(ssb);
        updateGutterWidth(totalLogicalLines);
        syncLineNumberScroll();
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
        if (lineNumbers != null && editor != null) {
            lineNumbers.scrollTo(0, editor.getScrollY());
        }
    }

}
