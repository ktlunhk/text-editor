package com.texteditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

public class MainActivity extends Activity {

	private static final int REQUEST_OPEN = 1001;
	private static final int REQUEST_SAVE_AS = 1002;

	// ---- Popup UI tuning (change these to adjust all dialogs / menus at once) ----
	/** Button width in dp. 0 = wrap content to text. */
	private static final int POPUP_BTN_WIDTH_DP = 0;
	/** Button height in dp. 0 = wrap content. */
	private static final int POPUP_BTN_HEIGHT_DP = 36;
	/** Button corner radius in dp. */
	private static final float POPUP_BTN_CORNER_DP = 8f;
	/** Font size (sp) for text inside popup buttons. */
	private static final int POPUP_BTN_FONT_SP = 13;
	/** Font size (sp) for popup dialog headings/titles. */
	private static final int POPUP_HEADING_FONT_SP = 16;
	/** Font size (sp) for content text inside popup dialogs (messages, inputs, checkboxes). */
	private static final int POPUP_CONTENT_FONT_SP = 13;
	/** Font size (sp) for items in dropdown popup menus. */
	private static final int POPUP_MENU_ITEM_FONT_SP = 13;
	/** Horizontal / vertical padding inside buttons (dp). */
	private static final int POPUP_BTN_PAD_DP = 8;
	private static final int RECENT_MAX = 8;
	private static final int UNDO_LIMIT = 80;

	private LinearLayout tabContainer;
	private FrameLayout editorContainer;
	private TextView statusLineCol;
	private TextView statusEol;
	private TextView statusEncoding;
	private TextView statusLanguage;

	private ArrayList tabs;
	private int currentTabIndex;
	private int pendingCloseIndex = -1;
	private boolean wordWrap;
	private boolean showGuides = true;
	private boolean showLineNumbers;
	private boolean darkTheme;
	private boolean keepScreenOn;
	private boolean softTabs;
	private int tabSize;
	private float fontSize;
	private ArrayList recentUris;

	// Remember last Find / Replace / Find All inputs
	private String lastFindQuery = "";
	private String lastReplaceText = "";
	private boolean lastFindRegex = false;
	private boolean lastFindCase = false;
	private boolean lastFindInSel = false;
	private String lastFindAllQuery = "";
	private boolean lastFindAllRegex = false;
	private boolean lastFindAllCase = false;

	private SharedPreferences prefs;
	private Handler handler;
	private Runnable scrollHighlighter;
	private Runnable zoomFinisher;
	private long lastZoomApply;
	private float lastAppliedSize = 0f;
	private Runnable lineNumberUpdater;
	private Runnable highlightUpdater;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Force dark look - no ActionBar
		requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

		prefs = getSharedPreferences("droidpad_prefs", MODE_PRIVATE);
		wordWrap = prefs.getBoolean("word_wrap", true);
		showGuides = prefs.getBoolean("indent_guides", true);
		showLineNumbers = prefs.getBoolean("line_numbers", true);
		darkTheme = prefs.getBoolean("dark_theme", true);
		keepScreenOn = prefs.getBoolean("keep_screen_on", false);
		softTabs = prefs.getBoolean("soft_tabs", true);
		tabSize = prefs.getInt("tab_size", 4);
		if (tabSize < 2)
			tabSize = 2;
		if (tabSize > 8)
			tabSize = 8;
		fontSize = prefs.getFloat("font_size", 14f);
		recentUris = new ArrayList();
		loadRecentFiles();
		// Keep pinch zoom within a gutter-safe range.
		if (fontSize < 8f)
			fontSize = 8f;
		if (fontSize > 20f)
			fontSize = 20f;

		setContentView(R.layout.activity_main);

		tabContainer = findViewById(R.id.tab_container);
		editorContainer = findViewById(R.id.editor_container);
		statusLineCol = findViewById(R.id.status_line_col);
		statusEol = findViewById(R.id.status_eol);
		statusEncoding = findViewById(R.id.status_encoding);
		statusLanguage = findViewById(R.id.status_language);

		tabs = new ArrayList();
		currentTabIndex = -1;
		handler = new Handler();

		lineNumberUpdater = new Runnable() {
			public void run() {
				if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
					EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
					tab.updateLineNumbers();
					updateCursorStatus(tab);
				}
			}
		};

		highlightUpdater = new Runnable() {
			public void run() {
				if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
					EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
					if (!tab.languageManual && tab.uri == null) {
						String guessed = SyntaxHighlighter.detectFromContent(tab.getContent());
						if (!guessed.equals(tab.language)) {
							tab.language = guessed;
							updateLanguageStatus(tab);
						}
					}
					applyHighlighting(tab);
				}
			}
		};

		scrollHighlighter = new Runnable() {
			public void run() {
				if (currentTabIndex < 0 || currentTabIndex >= tabs.size()) {
					return;
				}
				EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
				if (tab.editor == null || "text".equals(tab.language)
						|| tab.editor.getText().length() <= SyntaxHighlighter.FULL_HIGHLIGHT_LIMIT) {
					return;
				}
				int[] w = computeHighlightWindow(tab);
				if (w == null) {
					return;
				}
				// Still inside the already-highlighted window? Nothing to do.
				if (w[0] >= tab.hlStart && w[1] <= tab.hlEnd) {
					return;
				}
				applyHighlighting(tab);
			}
		};

		zoomFinisher = new Runnable() {
			public void run() {
				// Gesture settled: bring every tab + the gutter + prefs up to date once.
				for (int i = 0; i < tabs.size(); i++) {
					applyFontSize((EditorTab) tabs.get(i));
				}
				prefs.edit().putFloat("font_size", fontSize).apply();
				lastAppliedSize = fontSize;
				if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
					EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
					tab.updateLineNumbers();
					scrollHighlighter.run();
				}
			}
		};

		// Wire toolbar buttons
		try {
			setupToolbar();
		} catch (Exception e) {
			android.util.Log.e("MainActivity", "setupToolbar failed", e);
		}

		// Wire top menu
		try {
			setupTopMenu();
		} catch (Exception e) {
			android.util.Log.e("MainActivity", "setupTopMenu failed", e);
		}

		// Open initial untitled tab
		try {
			createNewTab();
			applyTheme();
			applyKeepScreenOn();
		} catch (Exception e) {
			android.util.Log.e("MainActivity", "init editor failed", e);
			Toast.makeText(this, "Init error: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}

		// Runtime storage permission (needed to overwrite file:// and path-based files)
		if (android.os.Build.VERSION.SDK_INT >= 23
				&& checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
						!= android.content.pm.PackageManager.PERMISSION_GRANTED) {
			requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
					android.Manifest.permission.READ_EXTERNAL_STORAGE}, 2001);
		}

		// Handle intent if opened with a file
		Intent intent = getIntent();
		if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
			Uri data = intent.getData();
			if (data != null) {
				openUri(data);
			}
		}
	}

	private void setupToolbar() {
		View btnNew = findViewById(R.id.btn_new);
		if (btnNew == null)
			return;
		btnNew.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				requestNewTab();
			}
		});

		findViewById(R.id.btn_open).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				openFile();
			}
		});

		findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				saveCurrent(false);
			}
		});

		findViewById(R.id.btn_save_all).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				saveCurrent(false);
			}
		});

		findViewById(R.id.btn_undo).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (currentTabIndex >= 0) {
					EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
					tab.undo();
					updateTabTitle(tab);
					updateCursorStatus(tab);
					applyHighlighting(tab);
				}
			}
		});

		findViewById(R.id.btn_redo).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (currentTabIndex >= 0) {
					EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
					tab.redo();
					updateTabTitle(tab);
					updateCursorStatus(tab);
					applyHighlighting(tab);
				}
			}
		});

		findViewById(R.id.btn_print).setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				printCurrent();
			}
		});
	}

	private void setupTopMenu() {
		View vFile = findViewById(R.id.menu_file);
		if (vFile != null) {
			vFile.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					showFileMenu(v);
				}
			});
		}

		View vSearch = findViewById(R.id.menu_search);
		if (vSearch != null) {
			vSearch.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					showSearchMenu(v);
				}
			});
		}

		View vUtility = findViewById(R.id.menu_utility);
		if (vUtility != null) {
			vUtility.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					showUtilityMenu(v);
				}
			});
		}

		View vOptions = findViewById(R.id.menu_options);
		if (vOptions != null) {
			vOptions.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					showSettings(v);
				}
			});
		}
	}

	private void showDropdownMenu(View anchor, final String[] items, final DialogInterface.OnClickListener click) {
		if (anchor == null || items == null) {
			return;
		}
		final int bg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int textCol = darkTheme ? Color.parseColor("#E0E0E0") : Color.parseColor("#1A1A1A");
		final int divider = darkTheme ? Color.parseColor("#3A3A3A") : Color.parseColor("#DDDDDD");

		ListView list = new ListView(this);
		list.setBackgroundColor(bg);
		list.setDivider(new android.graphics.drawable.ColorDrawable(divider));
		list.setDividerHeight(1);
		list.setPadding(0, 4, 0, 4);

		ArrayAdapter adapter = new ArrayAdapter(this, android.R.layout.simple_list_item_1, items) {
			public View getView(int position, View convertView, ViewGroup parent) {
				View v = super.getView(position, convertView, parent);
				v.setBackgroundColor(bg);
				if (v instanceof TextView) {
					TextView tv = (TextView) v;
					tv.setTextColor(textCol);
					tv.setTextSize(POPUP_MENU_ITEM_FONT_SP);
					tv.setPadding(24, 16, 24, 16);
				} else if (v instanceof ViewGroup) {
					ViewGroup vg = (ViewGroup) v;
					for (int i = 0; i < vg.getChildCount(); i++) {
						View c = vg.getChildAt(i);
						if (c instanceof TextView) {
							TextView tv = (TextView) c;
							tv.setTextColor(textCol);
							tv.setTextSize(POPUP_MENU_ITEM_FONT_SP);
							tv.setPadding(24, 16, 24, 16);
						}
					}
				}
				return v;
			}
		};

		list.setAdapter(adapter);

		// Measure width from longest item
		int width = (int) (180 * getResources().getDisplayMetrics().density);
		android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
		int maxW = (int) (dm.widthPixels * 0.7f);
		if (width > maxW)
			width = maxW;

		final PopupWindow popup = new PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
		popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
		popup.setOutsideTouchable(true);
		popup.setFocusable(true);
		if (android.os.Build.VERSION.SDK_INT >= 21) {
			popup.setElevation(12f);
		}

		list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				popup.dismiss();
				if (click != null) {
					click.onClick(null, position);
				}
			}
		});

		popup.showAsDropDown(anchor, 0, 0);
	}

	private void showFileMenu(final View anchor) {
		final String[] items = new String[]{"New", "Open...", "Recent files   >", "Save", "Save As...", "Save All",
				"Share as text", "Share as file", "Print...", "Close Tab", "Close All Tabs"};
		showDropdownMenu(anchor, items, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				if (which == 0)
					requestNewTab();
				else if (which == 1)
					openFile();
				else if (which == 2)
					showRecentFilesMenu(anchor);
				else if (which == 3)
					saveCurrent(false);
				else if (which == 4)
					saveCurrent(true);
				else if (which == 5)
					saveCurrent(false);
				else if (which == 6)
					shareAsText();
				else if (which == 7)
					shareAsFile();
				else if (which == 8)
					printCurrent();
				else if (which == 9) {
					if (currentTabIndex >= 0)
						closeTab(currentTabIndex);
				}
				else if (which == 10)
					closeAllTabs();
			}
		});
	}

	private void showSearchMenu(View anchor) {
		final String[] items = new String[]{"Find / Replace...", "Find All...", "Go to Line..."};
		showDropdownMenu(anchor, items, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				if (which == 0)
					showFindReplaceDialog();
				else if (which == 1)
					showFindAllDialog();
				else if (which == 2)
					showGotoDialog();
			}
		});
	}

	private void showUtilityMenu(View anchor) {
		final String[] items = new String[]{"Duplicate Line", "Move Line Up", "Move Line Down", "Toggle Comment",
				"Uppercase", "Lowercase", "Document Stats"};
		showDropdownMenu(anchor, items, new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				if (which == 0)
					duplicateLine();
				else if (which == 1)
					moveLine(-1);
				else if (which == 2)
					moveLine(1);
				else if (which == 3)
					toggleComment();
				else if (which == 4)
					convertCase(true);
				else if (which == 5)
					convertCase(false);
				else if (which == 6)
					showDocumentStats();
			}
		});
	}

	private void createNewTab() {
		final EditorTab tab = new EditorTab("Untitled");
		View page = getLayoutInflater().inflate(R.layout.editor_page, null);
		tab.rootView = page;
		tab.editor = page.findViewById(R.id.editor);
		tab.lineNumbers = page.findViewById(R.id.line_numbers);

		if (tab.editor instanceof SyncedEditText) {
			((SyncedEditText) tab.editor).setEditorListener(new SyncedEditText.Listener() {
				public void onEditorScrolled(int scrollY) {
					tab.syncLineNumberScroll();
					if (scrollHighlighter != null) {
						handler.removeCallbacks(scrollHighlighter);
						handler.postDelayed(scrollHighlighter, 120);
					}
				}
				public void onEditorSizeChanged() {
					tab.editor.post(new Runnable() {
						public void run() {
							tab.updateLineNumbers();
						}
					});
				}
			});
		}

		applyEditorSettings(tab);
		tab.language = "text";
		tab.languageManual = false;
		applyHighlighting(tab);

		tab.editor.addTextChangedListener(new TextWatcher() {
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				tab.beforeChange(s, start, count);
			}
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				tab.afterChange(s, start, count);
			}
			public void afterTextChanged(Editable s) {
				if (tab.bulkLoad) {
					return;
				}
				if (!tab.modified) {
					tab.modified = true;
					updateTabTitle(tab);
				}
				handler.removeCallbacks(lineNumberUpdater);
				handler.postDelayed(lineNumberUpdater, 150);
				handler.removeCallbacks(highlightUpdater);
				handler.postDelayed(highlightUpdater, 300);
			}
		});

		// Update cursor position on selection / key / touch
		tab.editor.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				updateCursorStatus(tab);
				tab.updateLineNumbers();
			}
		});

		tab.editor.setOnKeyListener(new View.OnKeyListener() {
			public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
				handler.removeCallbacks(lineNumberUpdater);
				handler.postDelayed(lineNumberUpdater, 50);
				return false;
			}
		});

		// Two-finger pinch to zoom font size in the editing area
		setupPinchZoom(tab);

		tabs.add(tab);
		addTabButton(tab);
		switchToTab(tabs.size() - 1);
	}

	private void addTabButton(final EditorTab tab) {
		LinearLayout tabBtn = new LinearLayout(this);
		tabBtn.setOrientation(LinearLayout.HORIZONTAL);
		tabBtn.setGravity(android.view.Gravity.CENTER_VERTICAL);
		tabBtn.setPadding(16, 0, 8, 0);

		TextView title = new TextView(this);
		title.setText(tab.title);
		title.setTextColor(darkTheme ? Color.parseColor("#B0B0B0") : Color.parseColor("#555555"));
		title.setTextSize(13);
		title.setPadding(4, 0, 8, 0);
		tabBtn.addView(title);

		TextView close = new TextView(this);
		close.setText("X");
		close.setTextColor(Color.parseColor("#888888"));
		close.setTextSize(12);
		close.setPadding(8, 4, 8, 4);
		tabBtn.addView(close);

		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.MATCH_PARENT);
		lp.setMargins(2, 0, 2, 0);
		tabBtn.setLayoutParams(lp);

		// Store title TextView for later updates
		tabBtn.setTag(title);

		tabBtn.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				int index = tabs.indexOf(tab);
				if (index >= 0) {
					switchToTab(index);
				}
			}
		});

		close.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				int index = tabs.indexOf(tab);
				if (index >= 0) {
					closeTab(index);
				}
			}
		});

		tabContainer.addView(tabBtn);
		updateTabButtonStyles();
	}

	private void switchToTab(int index) {
		if (index < 0 || index >= tabs.size()) {
			return;
		}
		// Save scroll/cursor of current tab before switching
		if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
			EditorTab prev = (EditorTab) tabs.get(currentTabIndex);
			if (prev.editor != null) {
				prev.savedScrollY = prev.editor.getScrollY();
				int c = prev.editor.getSelectionStart();
				prev.savedCursor = c >= 0 ? c : 0;
			}
		}
		currentTabIndex = index;
		editorContainer.removeAllViews();
		final EditorTab tab = (EditorTab) tabs.get(index);
		editorContainer.addView(tab.rootView);
		updateTabButtonStyles();
		updateCursorStatus(tab);
		updateLanguageStatus(tab);
		tab.editor.requestFocus();
		tab.editor.post(new Runnable() {
			public void run() {
				try {
					int len = tab.editor.getText().length();
					int c = tab.savedCursor;
					if (c < 0)
						c = 0;
					if (c > len)
						c = len;
					tab.editor.setSelection(c);
					tab.editor.scrollTo(0, tab.savedScrollY);
					tab.syncLineNumberScroll();
				} catch (Exception ignored) {
				}
				tab.updateLineNumbers();
				highlightExtras(tab);
			}
		});
	}

	private void updateTabButtonStyles() {
		if (tabContainer == null)
			return;
		int tabActiveBg = darkTheme ? Color.parseColor("#2D2D2D") : Color.parseColor("#FFFFFF");
		int tabActiveText = Color.parseColor("#BB86FC");
		int tabIdleText = darkTheme ? Color.parseColor("#B0B0B0") : Color.parseColor("#555555");
		int closeColor = darkTheme ? Color.parseColor("#888888") : Color.parseColor("#999999");
		for (int i = 0; i < tabContainer.getChildCount(); i++) {
			View btn = tabContainer.getChildAt(i);
			TextView title = null;
			if (btn.getTag() instanceof TextView) {
				title = (TextView) btn.getTag();
			}
			if (i == currentTabIndex) {
				btn.setBackgroundColor(tabActiveBg);
				if (title != null)
					title.setTextColor(tabActiveText);
			} else {
				btn.setBackgroundColor(Color.TRANSPARENT);
				if (title != null)
					title.setTextColor(tabIdleText);
			}
			if (btn instanceof ViewGroup) {
				ViewGroup vg = (ViewGroup) btn;
				for (int c = 0; c < vg.getChildCount(); c++) {
					View child = vg.getChildAt(c);
					if (child instanceof TextView) {
						TextView tv = (TextView) child;
						String tx = tv.getText() != null ? tv.getText().toString() : "";
						if ("×".equals(tx) || "✕".equals(tx)) {
							tv.setTextColor(closeColor);
						}
					}
				}
			}
		}
	}

	private void updateTabTitle(EditorTab tab) {
		int index = tabs.indexOf(tab);
		if (index >= 0 && index < tabContainer.getChildCount()) {
			LinearLayout btn = (LinearLayout) tabContainer.getChildAt(index);
			TextView title = (TextView) btn.getTag();
			if (title != null) {
				String t = tab.title;
				if (tab.modified) {
					t = "\u2022 " + t; // bullet = unsaved
				}
				title.setText(t);
			}
		}
	}

	private void setupPinchZoom(final EditorTab tab) {
		if (tab == null || tab.editor == null) {
			return;
		}
		final ScaleGestureDetector detector = new ScaleGestureDetector(this,
				new ScaleGestureDetector.SimpleOnScaleGestureListener() {
					public boolean onScale(ScaleGestureDetector detector) {
						float newSize = fontSize * detector.getScaleFactor();
						if (newSize < 8f) {
							newSize = 8f;
						}
						if (newSize > 45f) {
							newSize = 45f;
						}
						fontSize = newSize; // accumulate every event so nothing is lost
						// A text-size change reflows the whole document, so apply it at most
						// ~12x/second to the tab being pinched; everything else waits for the
						// gesture to settle.
						long now = System.currentTimeMillis();
						// Reflowing a big document is expensive: at most ~6x/second, and only
						// for a visible change in size.
						if (now - lastZoomApply >= 160 && Math.abs(fontSize - lastAppliedSize) >= 0.4f) {
							lastAppliedSize = fontSize;
							lastZoomApply = now;
							applyFontSize(tab);
						}
						handler.removeCallbacks(zoomFinisher);
						handler.postDelayed(zoomFinisher, 150);
						return true;
					}
				});

		tab.editor.setOnTouchListener(new View.OnTouchListener() {
			public boolean onTouch(View v, android.view.MotionEvent event) {
				detector.onTouchEvent(event);
				// Let EditText handle single-finger scroll/selection
				// When two fingers are used, consume move events after detector
				if (event.getPointerCount() > 1) {
					return true;
				}
				// Still update cursor/line highlight on touch up
				if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
					handler.removeCallbacks(lineNumberUpdater);
					handler.postDelayed(lineNumberUpdater, 50);
				}
				return false;
			}
		});
	}

	private void applyFontSize(EditorTab tab) {
		if (tab == null || tab.editor == null) {
			return;
		}
		// Gutter first: new font size, then resize its width to fit the digits. Doing it
		// before the editor change means both are laid out together in one pass.
		if (tab.lineNumbers != null) {
			tab.lineNumbers.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
			tab.updateLineNumbers();
		}
		// Keep the same line at the top of the screen while the text reflows.
		if (tab.editor instanceof SyncedEditText) {
			((SyncedEditText) tab.editor).setZoomTextSize(fontSize);
		} else {
			tab.editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
		}
	}

	private void applyEditorSettings(final EditorTab tab) {
		if (tab == null || tab.editor == null) {
			return;
		}
		tab.editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
		if (tab.lineNumbers != null) {
			tab.lineNumbers.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
			tab.lineNumbers.setTypeface(Typeface.MONOSPACE);
		}
		tab.editor.setTypeface(Typeface.MONOSPACE);

		// Theme colors applied immediately
		if (darkTheme) {
			tab.editor.setBackgroundColor(Color.parseColor("#1E1E1E"));
			tab.editor.setTextColor(Color.parseColor("#D4D4D4"));
			tab.lineColorNormal = Color.parseColor("#888888");
			tab.lineColorCurrent = Color.parseColor("#FFFFFF");
			if (tab.lineNumbers != null) {
				tab.lineNumbers.setBackgroundColor(Color.parseColor("#252525"));
				tab.lineNumbers.setTextColor(Color.parseColor("#888888"));
			}
			if (tab.rootView != null) {
				tab.rootView.setBackgroundColor(Color.parseColor("#1E1E1E"));
			}
		} else {
			tab.editor.setBackgroundColor(Color.parseColor("#FFFFFF"));
			tab.editor.setTextColor(Color.parseColor("#1A1A1A"));
			tab.lineColorNormal = Color.parseColor("#888888");
			tab.lineColorCurrent = Color.parseColor("#7B1FA2");
			if (tab.lineNumbers != null) {
				tab.lineNumbers.setBackgroundColor(Color.parseColor("#F0F0F0"));
				tab.lineNumbers.setTextColor(Color.parseColor("#666666"));
			}
			if (tab.rootView != null) {
				tab.rootView.setBackgroundColor(Color.parseColor("#FFFFFF"));
			}
		}

		syncGuides(tab);

		// Wrap off: SyncedEditText pans long lines with a horizontal drag.
		if (wordWrap) {
			tab.editor.setHorizontallyScrolling(false);
			tab.editor.scrollTo(0, tab.editor.getScrollY());
		} else {
			tab.editor.setHorizontallyScrolling(true);
		}

		if (tab.lineNumbers != null) {
			if (showLineNumbers) {
				tab.lineNumbers.setVisibility(View.VISIBLE);
				tab.updateLineNumbers();
				tab.editor.post(new Runnable() {
					public void run() {
						tab.updateLineNumbers();
					}
				});
			} else {
				tab.lineNumbers.setVisibility(View.GONE);
			}
		}
	}

	private void applySettingsToAllTabs() {
		for (int i = 0; i < tabs.size(); i++) {
			EditorTab tab = (EditorTab) tabs.get(i);
			applyEditorSettings(tab);
		}
	}

	/** {visStart, visEnd, winStart, winEnd} char offsets, or null while the layout isn't built yet. */
	private int[] computeHighlightWindow(EditorTab tab) {
		android.text.Layout l = tab.editor.getLayout();
		if (l == null || l.getLineCount() == 0) {
			return null;
		}
		int count = l.getLineCount();
		int top = tab.editor.getScrollY();
		int bottom = top + tab.editor.getHeight();
		int first = l.getLineForVertical(top);
		int last = l.getLineForVertical(bottom);
		int margin = 60; // visual lines highlighted above/below the screen
		int ws = l.getLineStart(Math.max(0, first - margin));
		int we = l.getLineEnd(Math.min(count - 1, last + margin));
		return new int[]{l.getLineStart(first), l.getLineEnd(last), ws, we};
	}

	private void syncGuides(EditorTab tab) {
		if (tab != null && tab.editor instanceof SyncedEditText) {
			((SyncedEditText) tab.editor).configureGuides(showGuides, "html".equals(tab.language), darkTheme,
					tabSize);
		}
	}

	private void applyHighlighting(EditorTab tab) {
		if (tab == null || tab.editor == null) {
			return;
		}
		syncGuides(tab);
		android.text.Editable editable = tab.editor.getText();
		if (editable.length() <= SyntaxHighlighter.FULL_HIGHLIGHT_LIMIT) {
			SyntaxHighlighter.highlight(editable, tab.language, darkTheme);
			tab.hlStart = 0;
			tab.hlEnd = Integer.MAX_VALUE;
		} else {
			int[] w = computeHighlightWindow(tab);
			if (w == null) {
				// Layout not built yet: highlight the top of the file now, redo once laid out.
				w = new int[]{0, 0, 0, Math.min(editable.length(), 6000)};
				if (currentTabIndex >= 0 && currentTabIndex < tabs.size() && tabs.get(currentTabIndex) == tab) {
					handler.removeCallbacks(highlightUpdater);
					handler.postDelayed(highlightUpdater, 150);
				}
			}
			SyntaxHighlighter.highlightRange(editable, tab.language, darkTheme, w[2], w[3]);
			tab.hlStart = w[2];
			tab.hlEnd = w[3];
		}
		highlightExtras(tab);
	}

	private void updateLanguageStatus(EditorTab tab) {
		if (statusLanguage == null) {
			return;
		}
		String name = SyntaxHighlighter.displayName(tab != null ? tab.language : null);
		if (tab != null && tab.languageManual) {
			name = name + " [L]"; // locked language
		}
		statusLanguage.setText(name);
	}

	private void applyTheme() {
		int bgMain = darkTheme ? Color.parseColor("#1A1A1A") : Color.parseColor("#F5F5F5");
		int bgBar = darkTheme ? Color.parseColor("#121212") : Color.parseColor("#E8E8E8");
		int bgMid = darkTheme ? Color.parseColor("#1E1E1E") : Color.parseColor("#FFFFFF");
		int bgTool = darkTheme ? Color.parseColor("#252525") : Color.parseColor("#EEEEEE");
		int textPrimary = darkTheme ? Color.parseColor("#FFFFFF") : Color.parseColor("#1A1A1A");
		int textSecondary = darkTheme ? Color.parseColor("#B0B0B0") : Color.parseColor("#555555");
		int textMuted = darkTheme ? Color.parseColor("#AAAAAA") : Color.parseColor("#666666");
		int textTool = darkTheme ? Color.parseColor("#E0E0E0") : Color.parseColor("#333333");
		int accent = Color.parseColor("#BB86FC");
		int tabActiveBg = darkTheme ? Color.parseColor("#2D2D2D") : Color.parseColor("#FFFFFF");
		int tabActiveText = accent;
		int tabIdleText = textSecondary;
		int closeColor = darkTheme ? Color.parseColor("#888888") : Color.parseColor("#999999");

		View root = findViewById(android.R.id.content);
		if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0) {
			((ViewGroup) root).getChildAt(0).setBackgroundColor(bgMain);
		} else if (root != null) {
			root.setBackgroundColor(bgMain);
		}

		//TextView appTitle = findViewById(R.id.app_title);
		//if (appTitle != null) {
			//appTitle.setTextColor(textPrimary);
			//appTitle.setBackgroundColor(bgBar);
		//}

		int[] menuIds = new int[]{R.id.menu_file, R.id.menu_search, R.id.menu_utility, R.id.menu_options};
		for (int i = 0; i < menuIds.length; i++) {
			View v = findViewById(menuIds[i]);
			if (v instanceof TextView) {
				((TextView) v).setTextColor(textSecondary);
			}
		}
		View topMenu = findViewById(R.id.top_menu);
		if (topMenu != null) {
			topMenu.setBackgroundColor(bgMid);
		}

		View toolbarScroll = findViewById(R.id.toolbar_scroll);
		if (toolbarScroll != null) {
			toolbarScroll.setBackgroundColor(bgTool);
		}
		View toolbar = findViewById(R.id.toolbar);
		if (toolbar != null) {
			toolbar.setBackgroundColor(bgTool);
		}
		int[] toolIds = new int[]{R.id.btn_new, R.id.btn_open, R.id.btn_save, R.id.btn_save_all, R.id.btn_undo,
				R.id.btn_redo, R.id.btn_print};
		int[] toolIconKinds = new int[]{ToolbarIcon.NEW, ToolbarIcon.OPEN, ToolbarIcon.SAVE, ToolbarIcon.SAVE_ALL,
				ToolbarIcon.UNDO, ToolbarIcon.REDO, ToolbarIcon.PRINT};
		int iconSizePx = (int) (20 * getResources().getDisplayMetrics().density);
		for (int i = 0; i < toolIds.length; i++) {
			View v = findViewById(toolIds[i]);
			if (v instanceof TextView) {
				TextView tv = (TextView) v;
				tv.setTextColor(textTool);
				tv.setCompoundDrawablesWithIntrinsicBounds(null,
						new ToolbarIcon(toolIconKinds[i], iconSizePx, textTool), null, null);
			}
		}

		View tabScroll = findViewById(R.id.tab_scroll);
		if (tabScroll != null) {
			tabScroll.setBackgroundColor(bgMid);
		}
		if (tabContainer != null) {
			tabContainer.setBackgroundColor(bgMid);
			for (int i = 0; i < tabContainer.getChildCount(); i++) {
				View btn = tabContainer.getChildAt(i);
				TextView title = null;
				TextView close = null;
				if (btn instanceof ViewGroup) {
					ViewGroup vg = (ViewGroup) btn;
					for (int c = 0; c < vg.getChildCount(); c++) {
						View child = vg.getChildAt(c);
						if (child instanceof TextView) {
							TextView tv = (TextView) child;
							String tx = tv.getText() != null ? tv.getText().toString() : "";
							if ("×".equals(tx) || "x".equals(tx) || "X".equals(tx)) {
								close = tv;
							} else if (title == null) {
								title = tv;
							}
						}
					}
				}
				if (btn.getTag() instanceof TextView) {
					title = (TextView) btn.getTag();
				}
				if (i == currentTabIndex) {
					btn.setBackgroundColor(tabActiveBg);
					if (title != null) {
						title.setTextColor(tabActiveText);
					}
				} else {
					btn.setBackgroundColor(Color.TRANSPARENT);
					if (title != null) {
						title.setTextColor(tabIdleText);
					}
				}
				if (close != null) {
					close.setTextColor(closeColor);
				}
			}
		}

		View editorHost = findViewById(R.id.editor_container);
		if (editorHost != null) {
			editorHost.setBackgroundColor(bgMid);
		}

		View statusBar = findViewById(R.id.status_bar_container);
		if (statusBar != null) {
			statusBar.setBackgroundColor(bgBar);
		}
		int[] statusIds = new int[]{R.id.status_line_col, R.id.status_eol, R.id.status_encoding, R.id.status_language};
		for (int i = 0; i < statusIds.length; i++) {
			View v = findViewById(statusIds[i]);
			if (v instanceof TextView) {
				((TextView) v).setTextColor(textMuted);
			}
		}

		applySettingsToAllTabs();
		updateTabButtonStyles();
		for (int i = 0; i < tabs.size(); i++) {
			applyHighlighting((EditorTab) tabs.get(i));
		}
	}

	private void updateCursorStatus(EditorTab tab) {
		if (tab == null || tab.editor == null) {
			statusLineCol.setText("Ln 1, Col 1");
			return;
		}
		int pos = tab.editor.getSelectionStart();
		int end = tab.editor.getSelectionEnd();
		if (pos < 0)
			pos = 0;
		int line = 1;
		int col = 1;
		if (tab.editor instanceof SyncedEditText) {
			int[] lc = ((SyncedEditText) tab.editor).lineColAt(pos);
			line = lc[0];
			col = lc[1];
		} else {
			CharSequence content = tab.editor.getText();
			for (int i = 0; i < pos && i < content.length(); i++) {
				if (content.charAt(i) == '\n') {
					line++;
					col = 1;
				} else {
					col++;
				}
			}
		}
		String msg = "Ln " + line + ", Col " + col;
		if (end > pos) {
			msg = msg + " (" + (end - pos) + " sel)";
		}
		statusLineCol.setText(msg);
		highlightExtras(tab);
	}

	private void closeTab(int index) {
		if (index < 0 || index >= tabs.size())
			return;
		EditorTab tab = (EditorTab) tabs.get(index);
		// Always check modified BEFORE the special last-tab reset.
		if (tab.modified) {
			confirmClose(index);
			return;
		}
		doCloseTab(index);
	}

	/** Close every tab; asks once if some have unsaved changes. */
	private void closeAllTabs() {
		int modifiedCount = 0;
		for (int i = 0; i < tabs.size(); i++) {
			if (((EditorTab) tabs.get(i)).modified) {
				modifiedCount++;
			}
		}
		if (modifiedCount == 0) {
			closeUnmodifiedTabs();
			return;
		}
		showStyledDialog("Unsaved changes",
				modifiedCount + (modifiedCount == 1 ? " tab has" : " tabs have") + " unsaved changes. Close all tabs?",
				null,
				new String[]{"Cancel", "Discard", "Save All"},
				new DialogAction[]{
						null,
						new DialogAction() {
							public boolean run() {
								for (int i = 0; i < tabs.size(); i++) {
									((EditorTab) tabs.get(i)).modified = false;
								}
								closeUnmodifiedTabs();
								return true;
							}
						},
						new DialogAction() {
							public boolean run() {
								int skipped = 0;
								for (int i = 0; i < tabs.size(); i++) {
									EditorTab t = (EditorTab) tabs.get(i);
									if (t.modified && t.uri != null) {
										writeToUri(t, t.uri);
									}
									if (t.modified) {
										skipped++;
									}
								}
								closeUnmodifiedTabs();
								if (skipped > 0) {
									Toast.makeText(MainActivity.this, skipped
											+ (skipped == 1 ? " tab was" : " tabs were") + " left open (not saved)",
											Toast.LENGTH_LONG).show();
								}
								return true;
							}
						}
				});
	}

	/** Closes all tabs without unsaved changes; the last remaining tab is reset to Untitled. */
	private void closeUnmodifiedTabs() {
		for (int i = tabs.size() - 1; i >= 0; i--) {
			EditorTab t = (EditorTab) tabs.get(i);
			if (t.modified) {
				continue;
			}
			if (tabs.size() > 1) {
				tabs.remove(i);
				tabContainer.removeViewAt(i);
			} else {
				currentTabIndex = 0;
				doCloseTab(0);
			}
		}
		if (currentTabIndex >= tabs.size()) {
			currentTabIndex = tabs.size() - 1;
		}
		if (currentTabIndex < 0) {
			currentTabIndex = 0;
		}
		switchToTab(currentTabIndex);
		updateTabButtonStyles();
	}

	private void confirmClose(final int index) {
		// Use the same custom panel, spacing, colours and rounded buttons as Go to line.
		final int dlgBg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final float radius = 16f * getResources().getDisplayMetrics().density;

		android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
		panel.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		panel.setCornerRadius(radius);
		panel.setColor(dlgBg);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(32, 24, 32, 20);
		layout.setBackgroundDrawable(panel);

		TextView title = new TextView(this);
		title.setText("Unsaved changes");
		title.setTextColor(dlgText);
		title.setTextSize(POPUP_HEADING_FONT_SP);
		title.setPadding(0, 0, 0, 16);
		layout.addView(title);

		TextView message = new TextView(this);
		message.setText("Save changes before closing?");
		message.setTextColor(dlgText);
		message.setTextSize(POPUP_CONTENT_FONT_SP);
		message.setPadding(0, 0, 0, 20);
		layout.addView(message);

		LinearLayout buttons = new LinearLayout(this);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setGravity(android.view.Gravity.END);
		LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		btnLp.leftMargin = (int) (12 * getResources().getDisplayMetrics().density);
		btnLp = popupButtonLayoutParams(btnLp);

		final Dialog dialog = new Dialog(this);
		dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
		dialog.setCancelable(true);

		Button btnClose = new Button(this);
		btnClose.setText("No");
		styleGreyRoundButton(btnClose);
		btnClose.setLayoutParams(btnLp);
		btnClose.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				dialog.dismiss();
				doCloseTab(index);
			}
		});
		buttons.addView(btnClose);

		Button btnSave = new Button(this);
		btnSave.setText("Yes");
		styleGreyRoundButton(btnSave);
		btnSave.setLayoutParams(btnLp);
		btnSave.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				if (index < 0 || index >= tabs.size()) {
					dialog.dismiss();
					return;
				}
				switchToTab(index);
				EditorTab closingTab = (EditorTab) tabs.get(index);
				if (closingTab.uri == null) {
					pendingCloseIndex = index;
					dialog.dismiss();
					saveCurrent(true);
				} else {
					writeToUri(closingTab, closingTab.uri);
					if (!closingTab.modified) {
						dialog.dismiss();
						doCloseTab(index);
					}
				}
			}
		});
		buttons.addView(btnSave);
		layout.addView(buttons);

		dialog.setContentView(layout);
		dialog.show();
		if (dialog.getWindow() != null) {
			android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
			winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
			winBg.setCornerRadius(radius);
			winBg.setColor(dlgBg);
			dialog.getWindow().setBackgroundDrawable(winBg);
			android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
			wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
			dialog.getWindow().setAttributes(wlp);
		}
		makeDialogDraggableDialog(dialog, title);
	}

	private void doCloseTab(int index) {
		if (index < 0 || index >= tabs.size())
			return;
		hapticTick();
		if (tabs.size() == 1) {
			EditorTab tab = (EditorTab) tabs.get(0);
			tab.setContent("");
			tab.title = "Untitled";
			tab.uri = null;
			tab.modified = false;
			tab.language = "text";
			tab.languageManual = false;
			updateTabTitle(tab);
			updateCursorStatus(tab);
			applyHighlighting(tab);
			updateLanguageStatus(tab);
			return;
		}
		tabs.remove(index);
		tabContainer.removeViewAt(index);
		if (currentTabIndex >= tabs.size()) {
			currentTabIndex = tabs.size() - 1;
		}
		if (currentTabIndex >= 0) {
			switchToTab(currentTabIndex);
		}
		updateTabButtonStyles();
	}

	private String fileNameFromUri(Uri uri) {
		if (uri == null) {
			return "Untitled";
		}
		String name = null;
		// Try DISPLAY_NAME from MediaStore / DocumentsContract
		try {
			android.database.Cursor cursor = getContentResolver().query(uri,
					new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
			if (cursor != null) {
				try {
					if (cursor.moveToFirst()) {
						int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
						if (idx >= 0) {
							name = cursor.getString(idx);
						}
					}
				} finally {
					cursor.close();
				}
			}
		} catch (Exception ignored) {
		}
		if (name == null || name.length() == 0) {
			name = uri.getLastPathSegment();
		}
		if (name == null || name.length() == 0) {
			return "Untitled";
		}
		try {
			name = java.net.URLDecoder.decode(name, "UTF-8");
		} catch (Exception ignored) {
		}
		if (name.regionMatches(true, 0, "primary:", 0, 8))
			name = name.substring(8);
		int colon = name.indexOf(':');
		if (colon >= 0 && colon < 12 && colon < name.length() - 1 && name.lastIndexOf('/', colon) < 0) {
			name = name.substring(colon + 1);
		}
		// Strip any remaining path separators
		int slash = name.lastIndexOf('/');
		if (slash >= 0 && slash < name.length() - 1) {
			name = name.substring(slash + 1);
		}
		slash = name.lastIndexOf('\\');
		if (slash >= 0 && slash < name.length() - 1) {
			name = name.substring(slash + 1);
		}
		// SAF often uses "primary:folder/file.txt" or "primary%3Afolder%2Ffile.txt"
		colon = name.lastIndexOf(':');
		if (colon >= 0 && colon < name.length() - 1) {
			name = name.substring(colon + 1);
			int s2 = name.lastIndexOf('/');
			if (s2 >= 0 && s2 < name.length() - 1) {
				name = name.substring(s2 + 1);
			}
		}
		if (name.length() == 0) {
			return "Untitled";
		}
		return name;
	}

	private void openFile() {
		if (!hasStoragePermission()) {
			openFileWithSystemPicker();
			return;
		}
		java.io.File start = null;
		String p = prefs.getString("last_open_dir", null);
		if (p != null) {
			start = new java.io.File(p);
		}
		if ((start == null || !start.isDirectory()) && currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
			start = folderOf(((EditorTab) tabs.get(currentTabIndex)).uri);
		}
		showOpenBrowser(start);
	}

	private static String formatFileSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format("%.2f KB", bytes / 1024.0);
		}
		if (bytes < 1024L * 1024 * 1024) {
			return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
		}
		return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
	}

	/** In-app multi-select file chooser: folders to navigate, files with size and a checkbox. */
	private void showOpenBrowser(java.io.File start) {
		final java.io.File root = android.os.Environment.getExternalStorageDirectory();
		final java.io.File[] cur = new java.io.File[1];
		cur[0] = (start != null && start.isDirectory()) ? start : root;
		final java.util.HashSet checked = new java.util.HashSet();
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final int dlgHint = Color.parseColor("#888888");
		final int dlgField = darkTheme ? Color.parseColor("#1E1E1E") : Color.parseColor("#F0F0F0");
		final float dens = getResources().getDisplayMetrics().density;

		LinearLayout body = new LinearLayout(this);
		body.setOrientation(LinearLayout.VERTICAL);

		final TextView pathView = new TextView(this);
		pathView.setTextColor(dlgText);
		pathView.setTextSize(POPUP_CONTENT_FONT_SP);
		pathView.setPadding(0, 0, 0, 8);
		body.addView(pathView);

		Button selectAll = new Button(this);
		selectAll.setText("Select all");
		styleGreyRoundButton(selectAll);
		LinearLayout.LayoutParams saLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		saLp.gravity = android.view.Gravity.END;
		saLp.bottomMargin = 12;
		selectAll.setLayoutParams(popupButtonLayoutParams(saLp));
		body.addView(selectAll);

		final ListView list = new ListView(this);
		list.setBackgroundColor(dlgField);
		body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				(int) (320 * dens)));
		LinearLayout.LayoutParams bodyGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		bodyGap.bottomMargin = 20;
		body.setLayoutParams(bodyGap);

		// Row model: null = "up" row, otherwise a folder or file
		final java.util.ArrayList rows = new java.util.ArrayList();
		final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
			public int getCount() {
				return rows.size();
			}

			public Object getItem(int position) {
				return rows.get(position);
			}

			public long getItemId(int position) {
				return position;
			}

			public View getView(int position, View convertView, ViewGroup parent) {
				java.io.File f = (java.io.File) rows.get(position);
				LinearLayout row = new LinearLayout(MainActivity.this);
				row.setOrientation(LinearLayout.HORIZONTAL);
				row.setGravity(android.view.Gravity.CENTER_VERTICAL);
				row.setPadding(24, 18, 24, 18);

				LinearLayout texts = new LinearLayout(MainActivity.this);
				texts.setOrientation(LinearLayout.VERTICAL);
				TextView name = new TextView(MainActivity.this);
				name.setTextColor(dlgText);
				name.setTextSize(POPUP_CONTENT_FONT_SP);
				texts.addView(name);
				row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

				if (f == null) {
					// Up row: an arrow icon instead of text
					texts.removeAllViews();
					texts.addView(createUpIcon(dlgText));
				} else if (f.isDirectory()) {
					name.setText(f.getName() + "/");
				} else {
					name.setText(f.getName());
					TextView size = new TextView(MainActivity.this);
					size.setTextColor(dlgHint);
					size.setTextSize(POPUP_CONTENT_FONT_SP - 3);
					size.setText(formatFileSize(f.length()));
					texts.addView(size);
					android.widget.CheckBox cb = new android.widget.CheckBox(MainActivity.this);
					cb.setFocusable(false);
					cb.setClickable(false);
					cb.setChecked(checked.contains(f.getAbsolutePath()));
					row.addView(cb);
				}
				return row;
			}
		};
		list.setAdapter(adapter);

		final Button[] openBtn = new Button[1];
		final Runnable updateOpenLabel = new Runnable() {
			public void run() {
				if (openBtn[0] != null) {
					openBtn[0].setText(checked.isEmpty() ? "Open" : "Open(" + checked.size() + ")");
				}
			}
		};

		final Runnable refresh = new Runnable() {
			public void run() {
				rows.clear();
				if (!cur[0].equals(root) && cur[0].getParentFile() != null) {
					rows.add(null);
				}
				java.io.File[] kids = cur[0].listFiles();
				java.util.ArrayList dirs = new java.util.ArrayList();
				java.util.ArrayList files = new java.util.ArrayList();
				if (kids != null) {
					for (int i = 0; i < kids.length; i++) {
						if (kids[i].isDirectory()) {
							if (!kids[i].getName().startsWith(".")) {
								dirs.add(kids[i]);
							}
						} else {
							files.add(kids[i]);
						}
					}
				}
				java.util.Comparator byName = new java.util.Comparator() {
					public int compare(Object a, Object b) {
						return ((java.io.File) a).getName().compareToIgnoreCase(((java.io.File) b).getName());
					}
				};
				java.util.Collections.sort(dirs, byName);
				java.util.Collections.sort(files, byName);
				rows.addAll(dirs);
				rows.addAll(files);
				pathView.setText(cur[0].getAbsolutePath());
				adapter.notifyDataSetChanged();
				list.setSelection(0);
			}
		};
		refresh.run();

		list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
			public void onItemClick(android.widget.AdapterView p, View v, int position, long id) {
				java.io.File f = (java.io.File) rows.get(position);
				if (f == null) {
					cur[0] = cur[0].getParentFile();
					refresh.run();
				} else if (f.isDirectory()) {
					cur[0] = f;
					refresh.run();
				} else {
					String path = f.getAbsolutePath();
					if (checked.contains(path)) {
						checked.remove(path);
					} else {
						checked.add(path);
					}
					adapter.notifyDataSetChanged();
					updateOpenLabel.run();
				}
			}
		});

		selectAll.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				boolean allChecked = true;
				int fileCount = 0;
				for (int i = 0; i < rows.size(); i++) {
					java.io.File f = (java.io.File) rows.get(i);
					if (f != null && !f.isDirectory()) {
						fileCount++;
						if (!checked.contains(f.getAbsolutePath())) {
							allChecked = false;
						}
					}
				}
				if (fileCount == 0) {
					return;
				}
				for (int i = 0; i < rows.size(); i++) {
					java.io.File f = (java.io.File) rows.get(i);
					if (f != null && !f.isDirectory()) {
						if (allChecked) {
							checked.remove(f.getAbsolutePath());
						} else {
							checked.add(f.getAbsolutePath());
						}
					}
				}
				adapter.notifyDataSetChanged();
				updateOpenLabel.run();
			}
		});

		showStyledDialog("Open files", null, body, null,
				new String[]{"Cancel", "Open"},
				new DialogAction[]{
						null,
						new DialogAction() {
							public boolean run() {
								if (checked.isEmpty()) {
									Toast.makeText(MainActivity.this, "No file selected", Toast.LENGTH_SHORT).show();
									return false;
								}
								prefs.edit().putString("last_open_dir", cur[0].getAbsolutePath()).commit();
								java.util.ArrayList paths = new java.util.ArrayList(checked);
								java.util.Collections.sort(paths);
								for (int i = 0; i < paths.size(); i++) {
									openUri(Uri.fromFile(new java.io.File((String) paths.get(i))));
								}
								return true;
							}
						}
				});
		openBtn[0] = lastDialogButtons[1];
	}

	private void openFileWithSystemPicker() {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		intent.setType("*/*");
		intent.addCategory(Intent.CATEGORY_OPENABLE);
		intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		try {
			startActivityForResult(intent, REQUEST_OPEN);
		} catch (Exception e) {
			// Fallback for older devices
			try {
				Intent intent2 = new Intent(Intent.ACTION_GET_CONTENT);
				intent2.setType("*/*");
				intent2.addCategory(Intent.CATEGORY_OPENABLE);
				intent2.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
				startActivityForResult(Intent.createChooser(intent2, "Open file"), REQUEST_OPEN);
			} catch (Exception e2) {
				Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show();
			}
		}
	}

	private void openUri(final Uri uri) {
		try {
			getContentResolver().takePersistableUriPermission(uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		} catch (Exception ignored) {
		}
		Toast.makeText(this, "Loading...", Toast.LENGTH_SHORT).show();
		// Read the file on a worker thread so the UI never blocks on I/O.
		new Thread(new Runnable() {
			public void run() {
				String loaded = null;
				String error = null;
				try {
					InputStream is = getContentResolver().openInputStream(uri);
					if (is == null) {
						error = "Failed to open file";
					} else {
						InputStreamReader reader = new InputStreamReader(is, "UTF-8");
						StringBuilder sb = new StringBuilder();
						char[] buf = new char[16384];
						int n;
						while ((n = reader.read(buf)) != -1) {
							sb.append(buf, 0, n);
						}
						reader.close();
						is.close();
						// Normalise CRLF / CR to LF (same as the old readLine loop did)
						loaded = sb.toString().replace("\r\n", "\n").replace('\r', '\n');
					}
				} catch (Exception e) {
					error = "Failed to open: " + e.getMessage();
				} catch (OutOfMemoryError oom) {
					error = "File too large for available memory";
				}
				final String fLoaded = loaded;
				final String fError = error;
				runOnUiThread(new Runnable() {
					public void run() {
						if (fError != null) {
							Toast.makeText(MainActivity.this, fError, Toast.LENGTH_LONG).show();
						} else {
							finishOpen(uri, fLoaded);
						}
					}
				});
			}
		}).start();
	}

	/** UI-thread half of openUri(): puts already-read text into a tab. */
	private void finishOpen(Uri uri, String content) {
		try {
			EditorTab tab;
			if (tabs.size() == 1) {
				EditorTab first = (EditorTab) tabs.get(0);
				if (!first.modified && first.uri == null && first.getContent().length() == 0) {
					tab = first;
				} else {
					createNewTab();
					tab = (EditorTab) tabs.get(tabs.size() - 1);
				}
			} else {
				createNewTab();
				tab = (EditorTab) tabs.get(tabs.size() - 1);
			}

			// Set the language BEFORE loading so text is highlighted exactly once.
			tab.uri = uri;
			java.io.File savedFile = uriToFile(uri);
			if (savedFile != null && savedFile.getParentFile() != null) {
				prefs.edit().putString("last_save_dir", savedFile.getParentFile().getAbsolutePath()).commit();
			}
			tab.title = fileNameFromUri(uri);
			tab.language = SyntaxHighlighter.detectLanguage(tab.title);
			tab.languageManual = false;
			tab.setContent(content);
			tab.modified = false;
			updateTabTitle(tab);
			applyHighlighting(tab);
			switchToTab(tabs.indexOf(tab));
			addRecentUri(uri);
		} catch (Exception e) {
			Toast.makeText(this, "Failed to open: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
	}

	/** Document URI that makes the Save As picker open in the same folder as the given file. */
	private Uri folderHintFor(Uri uri) {
		if (uri == null) {
			return null;
		}
		try {
			if ("content".equals(uri.getScheme())) {
				return uri; // the picker opens the folder containing this document
			}
			java.io.File f = uriToFile(uri);
			if (f != null) {
				String root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
				String path = f.getAbsolutePath();
				if (path.startsWith(root + "/")) {
					return android.provider.DocumentsContract.buildDocumentUri(
							"com.android.externalstorage.documents",
							"primary:" + path.substring(root.length() + 1));
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private boolean hasStoragePermission() {
		if (android.os.Build.VERSION.SDK_INT < 23) {
			return true;
		}
		return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
				== android.content.pm.PackageManager.PERMISSION_GRANTED;
	}

	/** Last folder a file was saved to, or null if unknown / gone. */
	private java.io.File lastSaveDir() {
		String p = prefs.getString("last_save_dir", null);
		if (p == null) {
			return null;
		}
		java.io.File f = new java.io.File(p);
		return (f.isDirectory() && f.canWrite()) ? f : null;
	}

	private void saveCurrent(boolean forceSaveAs) {
		if (currentTabIndex < 0 || currentTabIndex >= tabs.size()) {
			return;
		}
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		if (tab.uri != null && !forceSaveAs) {
			writeToUri(tab, tab.uri);
			return;
		}
		if (!hasStoragePermission()) {
			launchSavePicker(tab);
			return;
		}
		java.io.File dir = tab.uri != null ? folderOf(tab.uri) : null;
		if (dir == null) {
			dir = lastSaveDir();
		}
		if (dir != null) {
			showSaveAsDialog(tab, dir);
		} else {
			showFolderBrowser(tab, null);
		}
	}

	/** Real folder of the file behind this URI, or null if unknown. */
	private java.io.File folderOf(Uri uri) {
		java.io.File f = uriToFile(uri);
		if (f == null) {
			return null;
		}
		java.io.File dir = f.getParentFile();
		return (dir != null && dir.isDirectory()) ? dir : null;
	}

	/** Buttons of the most recently built styled dialog (same order as the labels). */
	private Button[] lastDialogButtons;

	private interface DialogAction {
		/** Return true to close the dialog, false to keep it open. */
		boolean run();
	}

	/** Dialog with the same panel, fonts, colours and rounded buttons as Find / Go to line. */
	private Dialog showStyledDialog(String titleText, String messageText, final EditText input,
			String[] labels, final DialogAction[] actions) {
		return showStyledDialog(titleText, messageText, null, input, labels, actions);
	}

	private Dialog showStyledDialog(String titleText, String messageText, View body, final EditText input,
			String[] labels, final DialogAction[] actions) {
		final int dlgBg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final int dlgHint = Color.parseColor("#888888");
		final int dlgField = darkTheme ? Color.parseColor("#1E1E1E") : Color.parseColor("#F0F0F0");
		final float radius = 16f * getResources().getDisplayMetrics().density;

		android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
		panel.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		panel.setCornerRadius(radius);
		panel.setColor(dlgBg);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(32, 24, 32, 20);
		layout.setBackgroundDrawable(panel);

		TextView title = new TextView(this);
		title.setText(titleText);
		title.setTextColor(dlgText);
		title.setTextSize(POPUP_HEADING_FONT_SP);
		title.setPadding(0, 0, 0, 16);
		layout.addView(title);

		if (messageText != null) {
			TextView message = new TextView(this);
			message.setText(messageText);
			message.setTextColor(dlgText);
			message.setTextSize(POPUP_CONTENT_FONT_SP);
			message.setPadding(0, 0, 0, 20);
			layout.addView(message);
		}

		if (body != null) {
			layout.addView(body);
		}

		if (input != null) {
			input.setTextColor(dlgText);
			input.setTextSize(POPUP_CONTENT_FONT_SP);
			input.setHintTextColor(dlgHint);
			input.setBackgroundColor(dlgField);
			input.setPadding(16, 14, 16, 14);
			input.setSingleLine(true);
			LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT);
			ilp.bottomMargin = 20;
			input.setLayoutParams(ilp);
			layout.addView(input);
		}

		LinearLayout buttons = new LinearLayout(this);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setGravity(android.view.Gravity.END);

		LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		btnLp.leftMargin = (int) (12 * getResources().getDisplayMetrics().density);
		btnLp = popupButtonLayoutParams(btnLp);

		final Dialog dialog = new Dialog(this);
		dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
		dialog.setCancelable(true);

		Button[] made = new Button[labels.length];
		for (int i = 0; i < labels.length; i++) {
			final DialogAction action = actions[i];
			Button b = new Button(this);
			made[i] = b;
			b.setText(labels[i]);
			styleGreyRoundButton(b);
			b.setLayoutParams(btnLp);
			b.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					boolean close = true;
					if (action != null) {
						close = action.run();
					}
					if (close) {
						dialog.dismiss();
					}
				}
			});
			buttons.addView(b);
		}
		lastDialogButtons = made;
		layout.addView(buttons);

		dialog.setContentView(layout);
		if (dialog.getWindow() != null) {
			android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
			winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
			winBg.setCornerRadius(radius);
			winBg.setColor(dlgBg);
			dialog.getWindow().setBackgroundDrawable(winBg);
			android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
			wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
			dialog.getWindow().setAttributes(wlp);
			if (input != null) {
				dialog.getWindow().setSoftInputMode(
						android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
			}
		}
		dialog.show();
		makeDialogDraggableDialog(dialog, title);
		if (input != null) {
			input.requestFocus();
			input.selectAll();
		}
		return dialog;
	}

	/** Styled "File exists" confirmation. Cancel runs onCancel (may be null). */
	private void confirmOverwrite(String name, final DialogAction onOverwrite, final DialogAction onCancel) {
		showStyledDialog("File exists",
				"\"" + name + "\" already exists in this folder. Overwrite it?", null,
				new String[]{"Cancel", "Overwrite"},
				new DialogAction[]{onCancel, onOverwrite});
	}

	/**
	 * Save As in the same folder. The typed name is written directly, so an existing file with
	 * that name is overwritten after confirmation (the system picker would create "name (1).txt").
	 */
	private void showSaveAsDialog(final EditorTab tab, final java.io.File dir) {
		final EditText input = new EditText(this);
		input.setText(tab.title.equals("Untitled") ? "untitled.txt" : tab.title);
		showStyledDialog("Save As (" + dir.getName() + ")", null, input,
				new String[]{"Cancel", "Browse...", "Save"},
				new DialogAction[]{
						null,
						new DialogAction() {
							public boolean run() {
								showFolderBrowser(tab, dir);
								return true;
							}
						},
						new DialogAction() {
							public boolean run() {
								final String name = input.getText().toString().trim();
								if (name.length() == 0 || name.indexOf('/') >= 0) {
									Toast.makeText(MainActivity.this, "Invalid file name", Toast.LENGTH_SHORT).show();
									return false;
								}
								final java.io.File target = new java.io.File(dir, name);
								java.io.File current = uriToFile(tab.uri);
								boolean sameAsCurrent = current != null
										&& current.getAbsolutePath().equals(target.getAbsolutePath());
								if (target.exists() && !sameAsCurrent) {
									confirmOverwrite(name, new DialogAction() {
										public boolean run() {
											saveAsTarget(tab, target);
											return true;
										}
									}, new DialogAction() {
										public boolean run() {
											showSaveAsDialog(tab, dir);
											return true;
										}
									});
								} else {
									saveAsTarget(tab, target);
								}
								return true;
							}
						}
				});
	}

	/** Up-arrow icon shared by the folder and file browsers (same size and colour rules). */
	private View createUpIcon(final int color) {
		View v = new View(this) {
			protected void onDraw(android.graphics.Canvas c) {
				android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
				p.setColor(color);
				p.setStyle(android.graphics.Paint.Style.STROKE);
				p.setStrokeWidth(getWidth() / 10f);
				p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
				p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
				float w = getWidth();
				float h = getHeight();
				c.drawLine(w * 0.5f, h * 0.85f, w * 0.5f, h * 0.15f, p);
				android.graphics.Path head = new android.graphics.Path();
				head.moveTo(w * 0.22f, h * 0.42f);
				head.lineTo(w * 0.5f, h * 0.15f);
				head.lineTo(w * 0.78f, h * 0.42f);
				c.drawPath(head, p);
			}
		};
		int size = (int) (20 * getResources().getDisplayMetrics().density);
		v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
		return v;
	}

	/** In-app folder chooser for shared storage (no system permission prompt). */
	private void showFolderBrowser(final EditorTab tab, java.io.File start) {
		final java.io.File root = android.os.Environment.getExternalStorageDirectory();
		final java.io.File[] cur = new java.io.File[1];
		cur[0] = (start != null && start.isDirectory()) ? start : root;
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final int dlgField = darkTheme ? Color.parseColor("#1E1E1E") : Color.parseColor("#F0F0F0");
		final float dens = getResources().getDisplayMetrics().density;

		LinearLayout body = new LinearLayout(this);
		body.setOrientation(LinearLayout.VERTICAL);

		final TextView pathView = new TextView(this);
		pathView.setTextColor(dlgText);
		pathView.setTextSize(POPUP_CONTENT_FONT_SP);
		pathView.setPadding(0, 0, 0, 12);
		body.addView(pathView);

		final ListView list = new ListView(this);
		list.setBackgroundColor(dlgField);
		body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				(int) (280 * dens)));
		LinearLayout.LayoutParams bodyGap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		bodyGap.bottomMargin = 20;
		body.setLayoutParams(bodyGap);

		final java.util.ArrayList names = new java.util.ArrayList();
		final boolean[] hasUp = new boolean[1];
		final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
			public int getCount() {
				return names.size();
			}

			public Object getItem(int position) {
				return names.get(position);
			}

			public long getItemId(int position) {
				return position;
			}

			public View getView(int position, View convertView, ViewGroup parent) {
				LinearLayout row = new LinearLayout(MainActivity.this);
				row.setOrientation(LinearLayout.HORIZONTAL);
				row.setGravity(android.view.Gravity.CENTER_VERTICAL);
				row.setPadding(24, 18, 24, 18);
				if (hasUp[0] && position == 0) {
					row.addView(createUpIcon(dlgText));
				} else {
					TextView tv = new TextView(MainActivity.this);
					tv.setTextColor(dlgText);
					tv.setTextSize(POPUP_CONTENT_FONT_SP);
					tv.setText(names.get(position) + "/");
					row.addView(tv);
				}
				return row;
			}
		};
		list.setAdapter(adapter);

		final Runnable refresh = new Runnable() {
			public void run() {
				names.clear();
				hasUp[0] = !cur[0].equals(root) && cur[0].getParentFile() != null;
				if (hasUp[0]) {
					names.add(".. (up)");
				}
				java.io.File[] kids = cur[0].listFiles();
				java.util.ArrayList dirs = new java.util.ArrayList();
				if (kids != null) {
					for (int i = 0; i < kids.length; i++) {
						if (kids[i].isDirectory() && !kids[i].getName().startsWith(".")) {
							dirs.add(kids[i].getName());
						}
					}
				}
				java.util.Collections.sort(dirs, String.CASE_INSENSITIVE_ORDER);
				names.addAll(dirs);
				pathView.setText(cur[0].getAbsolutePath());
				adapter.notifyDataSetChanged();
				list.setSelection(0);
			}
		};
		refresh.run();

		list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
			public void onItemClick(android.widget.AdapterView p, View v, int position, long id) {
				if (hasUp[0] && position == 0) {
					cur[0] = cur[0].getParentFile();
				} else {
					cur[0] = new java.io.File(cur[0], (String) names.get(position));
				}
				refresh.run();
			}
		});

		showStyledDialog("Choose folder", null, body, null,
				new String[]{"Cancel", "Select"},
				new DialogAction[]{
						null,
						new DialogAction() {
							public boolean run() {
								if (!cur[0].canWrite()) {
									Toast.makeText(MainActivity.this, "This folder is not writable", Toast.LENGTH_SHORT).show();
									return false;
								}
								showSaveAsDialog(tab, cur[0]);
								return true;
							}
						}
				});
	}

	private void saveAsTarget(EditorTab tab, java.io.File target) {
		writeToUri(tab, Uri.fromFile(target));
		if (pendingCloseIndex >= 0 && !tab.modified) {
			int closeIndex = pendingCloseIndex;
			pendingCloseIndex = -1;
			doCloseTab(closeIndex);
		}
	}

	/** Folder picker (never creates a file itself, so the system can't invent "name (1).txt"). */
	private void launchSavePicker(EditorTab tab) {
		Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
				| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		Uri startAt = folderHintFor(tab.uri);
		if (startAt != null && android.os.Build.VERSION.SDK_INT >= 26) {
			intent.putExtra("android.provider.extra.INITIAL_URI", startAt);
		}
		try {
			startActivityForResult(intent, REQUEST_SAVE_AS);
			Toast.makeText(this, "Choose the folder to save in", Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			Toast.makeText(this, "Cannot open folder picker", Toast.LENGTH_SHORT).show();
		}
	}

	/** Finds a file with this exact name directly inside the picked folder, or null. */
	private Uri findChildInTree(Uri treeUri, String name) {
		android.database.Cursor c = null;
		try {
			String parentId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
			Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
			c = getContentResolver().query(children, new String[]{
					android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
					android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
			while (c != null && c.moveToNext()) {
				if (name.equals(c.getString(1))) {
					return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
				}
			}
		} catch (Exception ignored) {
		} finally {
			if (c != null) {
				c.close();
			}
		}
		return null;
	}

	/** Name dialog for a folder chosen through the system folder picker. */
	private void showSaveAsTreeDialog(final EditorTab tab, final Uri treeUri) {
		final EditText input = new EditText(this);
		input.setText(tab.title.equals("Untitled") ? "untitled.txt" : tab.title);
		showStyledDialog("Save As", null, input,
				new String[]{"Cancel", "Save"},
				new DialogAction[]{
						null,
						new DialogAction() {
							public boolean run() {
								final String name = input.getText().toString().trim();
								if (name.length() == 0 || name.indexOf('/') >= 0) {
									Toast.makeText(MainActivity.this, "Invalid file name", Toast.LENGTH_SHORT).show();
									return false;
								}
								final Uri existing = findChildInTree(treeUri, name);
								if (existing != null) {
									confirmOverwrite(name, new DialogAction() {
										public boolean run() {
											saveAsUri(tab, existing);
											return true;
										}
									}, new DialogAction() {
										public boolean run() {
											showSaveAsTreeDialog(tab, treeUri);
											return true;
										}
									});
									return true;
								}
								try {
									String parentId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
									Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
									Uri created = android.provider.DocumentsContract.createDocument(
											getContentResolver(), parent, mimeForSaveName(name), name);
									if (created == null) {
										Toast.makeText(MainActivity.this, "Cannot create file here", Toast.LENGTH_LONG).show();
									} else {
										saveAsUri(tab, created);
									}
								} catch (Exception e) {
									Toast.makeText(MainActivity.this, "Cannot create file: " + e.getMessage(), Toast.LENGTH_LONG).show();
								}
								return true;
							}
						}
				});
	}

	private void saveAsUri(EditorTab tab, Uri uri) {
		writeToUri(tab, uri);
		if (pendingCloseIndex >= 0 && !tab.modified) {
			int closeIndex = pendingCloseIndex;
			pendingCloseIndex = -1;
			doCloseTab(closeIndex);
		}
	}

	/**
	 * MIME type for the Save As dialog. With "text/plain" the system picker appends ".txt" to names
	 * like "abc.java", so use the type of the real extension, or a generic type when it is unknown.
	 */
	private static String mimeForSaveName(String name) {
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			return "text/plain"; // no extension: let the picker add .txt
		}
		String ext = name.substring(dot + 1).toLowerCase();
		String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
		return mime != null ? mime : "application/octet-stream";
	}

	/**
	 * Maps a URI to the real file on disk, or null if it can't be found. Handles file://, document
	 * providers, the "_data" column used by many file managers, and finally the open file
	 * descriptor itself (/proc/self/fd), which reveals the real path for most file-backed providers.
	 */
	private java.io.File uriToFile(Uri uri) {
		if (uri == null) {
			return null;
		}
		try {
			String scheme = uri.getScheme();
			if ("file".equals(scheme)) {
				return new java.io.File(uri.getPath());
			}
			if (!"content".equals(scheme)) {
				return null;
			}
			String authority = uri.getAuthority();
			java.io.File f = null;

			// 1. External storage documents ("primary:Folder/file.txt")
			if ("com.android.externalstorage.documents".equals(authority)) {
				String docId = android.provider.DocumentsContract.getDocumentId(uri);
				int colon = docId.indexOf(':');
				if (colon > 0) {
					String volume = docId.substring(0, colon);
					String path = docId.substring(colon + 1);
					if ("primary".equalsIgnoreCase(volume)) {
						f = new java.io.File(android.os.Environment.getExternalStorageDirectory(), path);
					} else {
						f = new java.io.File("/storage/" + volume + "/" + path);
					}
				}
				return f;
			}

			// 2. Downloads documents with a raw path ("raw:/storage/emulated/0/Download/a.txt")
			if ("com.android.providers.downloads.documents".equals(authority)) {
				String docId = android.provider.DocumentsContract.getDocumentId(uri);
				if (docId != null && docId.startsWith("raw:")) {
					f = new java.io.File(docId.substring(4));
					if (f.isFile()) {
						return f;
					}
				}
			}

			// 3. "_data" column (MediaStore and many file-manager providers)
			android.database.Cursor c = null;
			try {
				c = getContentResolver().query(uri, new String[]{"_data"}, null, null, null);
				if (c != null && c.moveToFirst()) {
					String p = c.getString(0);
					if (p != null && p.startsWith("/")) {
						f = new java.io.File(p);
						if (f.isFile()) {
							return f;
						}
					}
				}
			} catch (Exception ignored) {
			} finally {
				if (c != null) {
					c.close();
				}
			}

			// 4. The open file descriptor's real path
			android.os.ParcelFileDescriptor pfd = null;
			try {
				pfd = getContentResolver().openFileDescriptor(uri, "r");
				if (pfd != null) {
					String p = new java.io.File("/proc/self/fd/" + pfd.getFd()).getCanonicalPath();
					if (p.startsWith("/") && !p.startsWith("/proc") && !p.startsWith("/dev")) {
						f = new java.io.File(p);
						if (f.isFile()) {
							return f;
						}
					}
				}
			} catch (Exception ignored) {
			} finally {
				if (pfd != null) {
					try {
						pfd.close();
					} catch (Exception ignored) {
					}
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	/** Opens a truncating output stream for the SAME file, with no prompt. Returns null if impossible. */
	private OutputStream openOverwriteStream(Uri uri) {
		OutputStream os = null;
		if (!"file".equals(uri.getScheme())) {
			String[] modes = new String[]{"wt", "rwt", "w"};
			for (int i = 0; i < modes.length && os == null; i++) {
				try {
					os = getContentResolver().openOutputStream(uri, modes[i]);
				} catch (Exception e) {
					os = null;
				}
			}
			if (os != null) {
				return os;
			}
		}
		// Fallback: write straight to the real file path (needs storage permission).
		java.io.File f = uriToFile(uri);
		if (f != null) {
			try {
				return new java.io.FileOutputStream(f, false);
			} catch (Exception e) {
				return null;
			}
		}
		return null;
	}

	private void writeToUri(EditorTab tab, Uri uri) {
		try {
			try {
				getContentResolver().takePersistableUriPermission(uri,
						Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
			} catch (Exception ignored) {
				// Not a persistable URI (or not needed)
			}

			OutputStream os = openOverwriteStream(uri);
			if (os == null) {
				// Only now, when the file truly cannot be overwritten, ask where to save.
				Toast.makeText(this, "Cannot write to this file. Choose where to save.", Toast.LENGTH_LONG).show();
				saveCurrent(true);
				return;
			}
			String enc = tab.encoding != null ? tab.encoding : "UTF-8";
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, enc));
			writer.write(tab.getContent());
			writer.flush();
			writer.close();
			os.close();

			tab.uri = uri;
			tab.title = fileNameFromUri(uri);
			tab.modified = false;
			updateTabTitle(tab);
			addRecentUri(uri);
			hapticTick();
			Toast.makeText(this, "Saved successfully", Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			Toast.makeText(this, "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (resultCode != RESULT_OK || data == null) {
			return;
		}
		int takeFlags = data.getFlags()
				& (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		if (takeFlags == 0) {
			takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
		}
		if (requestCode == REQUEST_OPEN) {
			// Support multi-select (ClipData) as well as a single URI
			android.content.ClipData clip = data.getClipData();
			if (clip != null && clip.getItemCount() > 0) {
				for (int i = 0; i < clip.getItemCount(); i++) {
					Uri u = clip.getItemAt(i).getUri();
					if (u == null)
						continue;
					try {
						getContentResolver().takePersistableUriPermission(u, takeFlags);
					} catch (Exception ignored) {
					}
					openUri(u);
				}
				return;
			}
			Uri uri = data.getData();
			if (uri == null)
				return;
			try {
				getContentResolver().takePersistableUriPermission(uri, takeFlags);
			} catch (Exception ignored) {
			}
			openUri(uri);
		} else if (requestCode == REQUEST_SAVE_AS) {
			Uri treeUri = data.getData();
			if (treeUri == null)
				return;
			try {
				getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
			} catch (Exception ignored) {
			}
			if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
				showSaveAsTreeDialog((EditorTab) tabs.get(currentTabIndex), treeUri);
			}
		}
	}

	private void makeDialogDraggableDialog(final Dialog dialog, final View dragView) {
		if (dialog == null || dialog.getWindow() == null || dragView == null) {
			return;
		}
		final android.view.Window window = dialog.getWindow();
		final float[] last = new float[2];
		final boolean[] dragging = new boolean[1];
		android.view.WindowManager.LayoutParams params = window.getAttributes();
		params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
		android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
		if (params.x == 0 && params.y == 0) {
			params.x = (int) (dm.widthPixels * 0.06f);
			params.y = (int) (dm.heightPixels * 0.18f);
		}
		window.setAttributes(params);
		View.OnTouchListener listener = new View.OnTouchListener() {
			public boolean onTouch(View v, android.view.MotionEvent event) {
				android.view.WindowManager.LayoutParams p = window.getAttributes();
				int action = event.getAction();
				if (action == android.view.MotionEvent.ACTION_DOWN) {
					last[0] = event.getRawX();
					last[1] = event.getRawY();
					dragging[0] = true;
					return true;
				} else if (action == android.view.MotionEvent.ACTION_MOVE && dragging[0]) {
					float dx = event.getRawX() - last[0];
					float dy = event.getRawY() - last[1];
					p.x = p.x + (int) dx;
					p.y = p.y + (int) dy;
					p.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
					window.setAttributes(p);
					last[0] = event.getRawX();
					last[1] = event.getRawY();
					return true;
				} else if (action == android.view.MotionEvent.ACTION_UP
						|| action == android.view.MotionEvent.ACTION_CANCEL) {
					dragging[0] = false;
					return true;
				}
				return false;
			}
		};
		dragView.setOnTouchListener(listener);
	}

	private android.graphics.drawable.GradientDrawable makeRoundGreyButton() {
		android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
		d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		float r = POPUP_BTN_CORNER_DP * getResources().getDisplayMetrics().density;
		d.setCornerRadius(r);
		if (darkTheme) {
			d.setColor(Color.parseColor("#4A4A4A"));
		} else {
			d.setColor(Color.parseColor("#BDBDBD"));
		}
		return d;
	}

	private void styleGreyRoundButton(Button btn) {
		btn.setBackgroundDrawable(makeRoundGreyButton());
		if (darkTheme) {
			btn.setTextColor(Color.parseColor("#F0F0F0"));
		} else {
			btn.setTextColor(Color.parseColor("#212121"));
		}
		if (android.os.Build.VERSION.SDK_INT >= 14) {
			btn.setAllCaps(false);
		}
		btn.setTextSize(POPUP_BTN_FONT_SP);
		float dens = getResources().getDisplayMetrics().density;
		int pad = (int) (POPUP_BTN_PAD_DP * dens);
		btn.setPadding(pad, pad, pad, pad);
		if (POPUP_BTN_HEIGHT_DP > 0) {
			int h = (int) (POPUP_BTN_HEIGHT_DP * dens);
			btn.setMinHeight(h);
			btn.setMinimumHeight(h);
		}
		if (POPUP_BTN_WIDTH_DP > 0) {
			int w = (int) (POPUP_BTN_WIDTH_DP * dens);
			btn.setMinWidth(w);
			btn.setMinimumWidth(w);
		}
	}

	/** Apply fixed width/height from constants when non-zero. */
	private LinearLayout.LayoutParams popupButtonLayoutParams(LinearLayout.LayoutParams base) {
		float dens = getResources().getDisplayMetrics().density;
		if (POPUP_BTN_WIDTH_DP > 0) {
			base.width = (int) (POPUP_BTN_WIDTH_DP * dens);
		}
		if (POPUP_BTN_HEIGHT_DP > 0) {
			base.height = (int) (POPUP_BTN_HEIGHT_DP * dens);
		}
		return base;
	}

	/** Make checkbox tick/box clearly visible on both light and dark themes. */
	private void stylePopupCheckBox(android.widget.CheckBox box) {
		box.setTextColor(darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A"));
		box.setTextSize(POPUP_CONTENT_FONT_SP);
		// Tint the checkbox indicator so it is not washed-out on light themes.
		// Guarded: some Holo / OEM builds are picky about button tint.
		if (android.os.Build.VERSION.SDK_INT >= 21) {
			try {
				int tint = darkTheme ? Color.parseColor("#90CAF9") : Color.parseColor("#1565C0");
				box.setButtonTintList(android.content.res.ColorStateList.valueOf(tint));
			} catch (Throwable ignored) {
			}
		}
	}

	private void showFindReplaceDialog() {
		if (currentTabIndex < 0)
			return;
		final EditorTab tab = (EditorTab) tabs.get(currentTabIndex);

		final int dlgBg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final int dlgHint = Color.parseColor("#888888");
		final int dlgField = darkTheme ? Color.parseColor("#1E1E1E") : Color.parseColor("#F0F0F0");
		final float radius = 16f * getResources().getDisplayMetrics().density;

		android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
		panel.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		panel.setCornerRadius(radius);
		panel.setColor(dlgBg);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(32, 20, 32, 20);
		layout.setBackgroundDrawable(panel);

		TextView title = new TextView(this);
		title.setText("Find / Replace");
		title.setTextColor(dlgText);
		title.setTextSize(POPUP_HEADING_FONT_SP);
		title.setPadding(0, 0, 0, 16);
		layout.addView(title);

		final EditText findInput = new EditText(this);
		findInput.setHint("Find");
		findInput.setTextColor(dlgText);
		findInput.setTextSize(POPUP_CONTENT_FONT_SP);
		findInput.setHintTextColor(dlgHint);
		findInput.setBackgroundColor(dlgField);
		findInput.setPadding(16, 14, 16, 14);
		findInput.setSingleLine(true);
		if (lastFindQuery != null && lastFindQuery.length() > 0)
			findInput.setText(lastFindQuery);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.bottomMargin = 12;
		findInput.setLayoutParams(lp);
		layout.addView(findInput);

		final EditText replaceInput = new EditText(this);
		replaceInput.setHint("Replace with");
		replaceInput.setTextColor(dlgText);
		replaceInput.setTextSize(POPUP_CONTENT_FONT_SP);
		replaceInput.setHintTextColor(dlgHint);
		replaceInput.setBackgroundColor(dlgField);
		replaceInput.setPadding(16, 14, 16, 14);
		replaceInput.setSingleLine(true);
		if (lastReplaceText != null && lastReplaceText.length() > 0)
			replaceInput.setText(lastReplaceText);
		LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		lp2.bottomMargin = 20;
		replaceInput.setLayoutParams(lp2);
		layout.addView(replaceInput);

		LinearLayout checks = new LinearLayout(this);
		checks.setOrientation(LinearLayout.HORIZONTAL);
		checks.setPadding(0, 0, 0, 24);

		final android.widget.CheckBox regexBox = new android.widget.CheckBox(this);
		regexBox.setText("Regex     ");
		stylePopupCheckBox(regexBox);
		regexBox.setChecked(lastFindRegex);
		checks.addView(regexBox);

		final android.widget.CheckBox caseBox = new android.widget.CheckBox(this);
		caseBox.setText("Case sensitive");
		stylePopupCheckBox(caseBox);
		caseBox.setPadding(0, 0, 0, 0);
		caseBox.setChecked(lastFindCase);
		checks.addView(caseBox);

		final android.widget.CheckBox selBox = new android.widget.CheckBox(this);
		selBox.setText("In selection");
		stylePopupCheckBox(selBox);
		selBox.setChecked(lastFindInSel);
		checks.addView(selBox);
		layout.addView(checks);

		// Horizontal row for the three action buttons
		LinearLayout buttonRow = new LinearLayout(this);
		buttonRow.setOrientation(LinearLayout.HORIZONTAL);
		LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		rowLp.bottomMargin = 4;
		buttonRow.setLayoutParams(rowLp);

		LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		int gap = (int) (8 * getResources().getDisplayMetrics().density);
		btnLp.setMargins(0, 0, gap, 0);
		btnLp = popupButtonLayoutParams(btnLp);

		Button btnFind = new Button(this);
		btnFind.setText("Find Next");
		btnFind.setSingleLine(true);
		styleGreyRoundButton(btnFind);
		btnFind.setLayoutParams(btnLp);
		btnFind.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				rememberFindState(findInput, replaceInput, regexBox, caseBox, selBox);
				doFind(tab, findInput.getText().toString(), caseBox.isChecked(), regexBox.isChecked(), false,
						selBox.isChecked());
			}
		});
		buttonRow.addView(btnFind);

		Button btnReplace = new Button(this);
		btnReplace.setText("Replace");
		btnReplace.setSingleLine(true);
		styleGreyRoundButton(btnReplace);
		btnReplace.setLayoutParams(btnLp);
		btnReplace.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				rememberFindState(findInput, replaceInput, regexBox, caseBox, selBox);
				doReplace(tab, findInput.getText().toString(), replaceInput.getText().toString(), caseBox.isChecked(),
						regexBox.isChecked(), false, selBox.isChecked());
			}
		});
		buttonRow.addView(btnReplace);

		// Last button: no right margin
		LinearLayout.LayoutParams btnLpLast = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		btnLpLast = popupButtonLayoutParams(btnLpLast);
		Button btnReplaceAll = new Button(this);
		btnReplaceAll.setText("Replace All");
		btnReplaceAll.setSingleLine(true);
		styleGreyRoundButton(btnReplaceAll);
		btnReplaceAll.setLayoutParams(btnLpLast);
		btnReplaceAll.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				rememberFindState(findInput, replaceInput, regexBox, caseBox, selBox);
				doReplace(tab, findInput.getText().toString(), replaceInput.getText().toString(), caseBox.isChecked(),
						regexBox.isChecked(), true, selBox.isChecked());
			}
		});
		buttonRow.addView(btnReplaceAll);

		layout.addView(buttonRow);

		// Reset row
		Button btnReset = new Button(this);
		btnReset.setText("Reset");
		btnReset.setSingleLine(true);
		styleGreyRoundButton(btnReset);
		LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		resetLp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
		btnReset.setLayoutParams(popupButtonLayoutParams(resetLp));
		btnReset.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				findInput.setText("");
				replaceInput.setText("");
				regexBox.setChecked(false);
				caseBox.setChecked(false);
				selBox.setChecked(false);
				lastFindQuery = "";
				lastReplaceText = "";
				lastFindRegex = false;
				lastFindCase = false;
				lastFindInSel = false;
				findInput.requestFocus();
			}
		});
		layout.addView(btnReset);

		final Dialog dialog = new Dialog(this);
		dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
		dialog.setContentView(layout);
		dialog.setCancelable(true);

		if (dialog.getWindow() != null) {
			// Same rounded color on window - no dark frame under corners
			android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
			winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
			winBg.setCornerRadius(radius);
			winBg.setColor(dlgBg);
			dialog.getWindow().setBackgroundDrawable(winBg);
			android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
			wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
			dialog.getWindow().setAttributes(wlp);
		}
		dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
			public void onDismiss(DialogInterface d) {
				rememberFindState(findInput, replaceInput, regexBox, caseBox, selBox);
			}
		});
		dialog.show();
		makeDialogDraggableDialog(dialog, title);
		findInput.requestFocus();
		if (findInput.getText().length() > 0)
			findInput.setSelection(findInput.getText().length());
	}

	private void rememberFindState(EditText findInput, EditText replaceInput, android.widget.CheckBox regexBox,
			android.widget.CheckBox caseBox, android.widget.CheckBox selBox) {
		if (findInput != null)
			lastFindQuery = findInput.getText().toString();
		if (replaceInput != null)
			lastReplaceText = replaceInput.getText().toString();
		if (regexBox != null)
			lastFindRegex = regexBox.isChecked();
		if (caseBox != null)
			lastFindCase = caseBox.isChecked();
		if (selBox != null)
			lastFindInSel = selBox.isChecked();
	}

	private int findIndex(String content, String query, int from, boolean caseSensitive, boolean regex) {
		if (query == null || query.length() == 0)
			return -1;
		if (content == null)
			return -1;
		if (regex) {
			try {
				int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
				java.util.regex.Pattern p = java.util.regex.Pattern.compile(query, flags);
				java.util.regex.Matcher m = p.matcher(content);
				if (from > 0 && from <= content.length()) {
					if (m.find(from)) {
						return m.start();
					}
					m = p.matcher(content);
				}
				if (m.find()) {
					return m.start();
				}
			} catch (Exception e) {
				Toast.makeText(this, "Invalid regex", Toast.LENGTH_SHORT).show();
			}
			return -1;
		}
		if (!caseSensitive) {
			String c = content.toLowerCase();
			String q = query.toLowerCase();
			int pos = c.indexOf(q, from);
			if (pos < 0)
				pos = c.indexOf(q, 0);
			return pos;
		}
		int pos = content.indexOf(query, from);
		if (pos < 0)
			pos = content.indexOf(query, 0);
		return pos;
	}

	private int matchLength(String content, String query, int pos, boolean caseSensitive, boolean regex) {
		if (regex) {
			try {
				int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
				java.util.regex.Pattern p = java.util.regex.Pattern.compile(query, flags);
				java.util.regex.Matcher m = p.matcher(content);
				if (m.find(pos) && m.start() == pos) {
					return m.end() - m.start();
				}
			} catch (Exception e) {
			}
			return query.length();
		}
		return query.length();
	}

	private void doFind(EditorTab tab, String query, boolean caseSensitive, boolean regex, boolean unused,
			boolean inSelection) {
		if (query == null || query.length() == 0)
			return;
		if (tab == null || tab.editor == null)
			return;
		String content = tab.getContent();
		int selStart = tab.editor.getSelectionStart();
		int selEnd = tab.editor.getSelectionEnd();
		if (selStart < 0)
			selStart = 0;
		if (selEnd < selStart)
			selEnd = selStart;
		int searchFrom = selEnd;
		int searchLimit = content.length();
		if (inSelection && selEnd > selStart) {
			searchFrom = selStart;
			searchLimit = selEnd;
			content = content.substring(0, searchLimit);
		}
		int pos = findIndex(content, query, searchFrom, caseSensitive, regex);
		if (inSelection && pos >= searchLimit)
			pos = -1;
		if (inSelection && pos >= 0 && pos < selStart)
			pos = -1;
		if (pos >= 0) {
			int len = matchLength(content, query, pos, caseSensitive, regex);
			tab.editor.setSelection(pos, pos + len);
			tab.editor.requestFocus();
			updateCursorStatus(tab);
			tab.updateLineNumbers();
		} else {
			Toast.makeText(this, "Not found", Toast.LENGTH_SHORT).show();
		}
	}

	private void doReplace(EditorTab tab, String query, String repl, boolean caseSensitive, boolean regex,
			boolean replaceAll, boolean inSelection) {
		if (query == null || query.length() == 0)
			return;
		if (tab == null || tab.editor == null)
			return;
		if (repl == null)
			repl = "";
		String content = tab.getContent();
		int rangeStart = 0;
		int rangeEnd = content.length();
		if (inSelection) {
			int a = tab.editor.getSelectionStart();
			int b = tab.editor.getSelectionEnd();
			if (a >= 0 && b > a) {
				rangeStart = a;
				rangeEnd = b;
			}
		}
		if (replaceAll) {
			int count = 0;
			if (regex) {
				try {
					int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
					java.util.regex.Pattern p = java.util.regex.Pattern.compile(query, flags);
					java.util.regex.Matcher m = p.matcher(content);
					StringBuffer sb = new StringBuffer();
					while (m.find()) {
						m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(repl));
						count++;
					}
					m.appendTail(sb);
					content = sb.toString();
				} catch (Exception e) {
					Toast.makeText(this, "Invalid regex", Toast.LENGTH_SHORT).show();
					return;
				}
			} else {
				if (!caseSensitive) {
					String lower = content.toLowerCase();
					String q = query.toLowerCase();
					StringBuilder sb = new StringBuilder();
					int i = 0;
					while (i < content.length()) {
						int pos = lower.indexOf(q, i);
						if (pos < 0) {
							sb.append(content.substring(i));
							break;
						}
						sb.append(content.substring(i, pos));
						sb.append(repl);
						i = pos + query.length();
						count++;
					}
					content = sb.toString();
				} else {
					int i = 0;
					StringBuilder sb = new StringBuilder();
					while (i < content.length()) {
						int pos = content.indexOf(query, i);
						if (pos < 0) {
							sb.append(content.substring(i));
							break;
						}
						sb.append(content.substring(i, pos));
						sb.append(repl);
						i = pos + query.length();
						count++;
					}
					content = sb.toString();
				}
			}
			tab.setContent(content);
			tab.modified = true;
			updateTabTitle(tab);
			tab.updateLineNumbers();
			Toast.makeText(this, "Replaced " + count + " occurrence(s)", Toast.LENGTH_SHORT).show();
			return;
		}

		// Single replace
		int start = tab.editor.getSelectionStart();
		int end = tab.editor.getSelectionEnd();
		String selected = "";
		if (start >= 0 && end > start && end <= content.length()) {
			selected = content.substring(start, end);
		}
		boolean selMatches = false;
		if (selected.length() > 0) {
			if (regex) {
				try {
					int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
					selMatches = java.util.regex.Pattern.compile(query, flags).matcher(selected).matches();
				} catch (Exception e) {
				}
			} else if (caseSensitive) {
				selMatches = selected.equals(query);
			} else {
				selMatches = selected.equalsIgnoreCase(query);
			}
		}
		if (selMatches) {
			tab.editor.getText().replace(start, end, repl);
			tab.editor.setSelection(start, start + repl.length());
		} else {
			int pos = findIndex(content, query, end, caseSensitive, regex);
			if (pos >= 0) {
				int len = matchLength(content, query, pos, caseSensitive, regex);
				tab.editor.getText().replace(pos, pos + len, repl);
				tab.editor.setSelection(pos, pos + repl.length());
			} else {
				Toast.makeText(this, "Not found", Toast.LENGTH_SHORT).show();
				return;
			}
		}
		tab.modified = true;
		updateTabTitle(tab);
		tab.updateLineNumbers();
		updateCursorStatus(tab);
	}

	private void showGotoDialog() {
		if (currentTabIndex < 0)
			return;
		final EditorTab tab = (EditorTab) tabs.get(currentTabIndex);

		final int dlgBg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final int dlgHint = Color.parseColor("#888888");
		final int dlgField = darkTheme ? Color.parseColor("#1E1E1E") : Color.parseColor("#F0F0F0");
		final float radius = 16f * getResources().getDisplayMetrics().density;

		android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
		panel.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		panel.setCornerRadius(radius);
		panel.setColor(dlgBg);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(32, 24, 32, 20);
		layout.setBackgroundDrawable(panel);

		TextView title = new TextView(this);
		title.setText("Go to line");
		title.setTextColor(dlgText);
		title.setTextSize(POPUP_HEADING_FONT_SP);
		title.setPadding(0, 0, 0, 16);
		layout.addView(title);

		final EditText input = new EditText(this);
		input.setHint("Line number...");
		input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
		input.setTextColor(dlgText);
		input.setTextSize(POPUP_CONTENT_FONT_SP);
		input.setHintTextColor(dlgHint);
		input.setBackgroundColor(dlgField);
		input.setPadding(16, 14, 16, 14);
		input.setSingleLine(true);
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		ilp.bottomMargin = 20;
		input.setLayoutParams(ilp);
		layout.addView(input);

		LinearLayout buttons = new LinearLayout(this);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.setGravity(android.view.Gravity.END);

		LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		btnLp.leftMargin = (int) (12 * getResources().getDisplayMetrics().density);
		btnLp = popupButtonLayoutParams(btnLp);

		final Dialog dialog = new Dialog(this);
		dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
		dialog.setCancelable(true);

		Button btnGo = new Button(this);
		btnGo.setText("Go");
		styleGreyRoundButton(btnGo);
		btnGo.setLayoutParams(btnLp);
		btnGo.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				try {
					int line = Integer.parseInt(input.getText().toString());
					if (line < 1)
						line = 1;
					String content = tab.getContent();
					int pos = 0;
					int currentLine = 1;
					while (currentLine < line && pos < content.length()) {
						if (content.charAt(pos) == '\n') {
							currentLine++;
						}
						pos++;
					}
					tab.editor.setSelection(pos);
					tab.editor.requestFocus();
					updateCursorStatus(tab);
					tab.updateLineNumbers();
					dialog.dismiss();
				} catch (Exception e) {
					Toast.makeText(MainActivity.this, "Invalid line", Toast.LENGTH_SHORT).show();
				}
			}
		});
		buttons.addView(btnGo);
		layout.addView(buttons);

		dialog.setContentView(layout);

		if (dialog.getWindow() != null) {
			android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
			winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
			winBg.setCornerRadius(radius);
			winBg.setColor(dlgBg);
			dialog.getWindow().setBackgroundDrawable(winBg);
			android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
			wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
			dialog.getWindow().setAttributes(wlp);
		}
		dialog.show();
		makeDialogDraggableDialog(dialog, title);
		input.requestFocus();
	}

	private void showSettings(final View anchor) {
		if (anchor == null)
			return;

		final String wrapText = "Wrap: " + (wordWrap ? "On" : "Off");
		final String lineText = "Line Numbers: " + (showLineNumbers ? "On" : "Off");
		final String themeText = "Theme: " + (darkTheme ? "Light" : "Dark");
		final String keepText = "Keep Screen On: " + (keepScreenOn ? "On" : "Off");
		final String softText = "Soft Tabs: " + (softTabs ? ("Spaces " + tabSize) : "Hard Tab");
		final String guideText = "Indent Guides: " + (showGuides ? "On" : "Off");
		final String[] items = new String[]{wrapText, lineText, themeText, keepText, softText, "Encoding   >",
				"Language   >", guideText};

		final int bg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int textCol = darkTheme ? Color.parseColor("#E0E0E0") : Color.parseColor("#1A1A1A");
		final int divider = darkTheme ? Color.parseColor("#3A3A3A") : Color.parseColor("#DDDDDD");

		final ListView list = new ListView(this);
		list.setBackgroundColor(bg);
		list.setDivider(new android.graphics.drawable.ColorDrawable(divider));
		list.setDividerHeight(1);
		list.setPadding(0, 4, 0, 4);

		ArrayAdapter adapter = new ArrayAdapter(this, android.R.layout.simple_list_item_1, items) {
			public View getView(int position, View convertView, ViewGroup parent) {
				View v = super.getView(position, convertView, parent);
				v.setBackgroundColor(bg);
				if (v instanceof TextView) {
					TextView tv = (TextView) v;
					tv.setTextColor(textCol);
					tv.setTextSize(POPUP_MENU_ITEM_FONT_SP);
					tv.setPadding(24, 16, 24, 16);
				}
				return v;
			}
		};
		list.setAdapter(adapter);

		android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
		int width = (int) (180 * dm.density);
		int maxW = (int) (dm.widthPixels * 0.7f);
		if (width > maxW)
			width = maxW;

		final PopupWindow optionsPopup = new PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
		optionsPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
		optionsPopup.setOutsideTouchable(true);
		optionsPopup.setFocusable(true);
		if (android.os.Build.VERSION.SDK_INT >= 21)
			optionsPopup.setElevation(12f);

		list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				if (position == 5) {
					showEncodingMenu(view, optionsPopup);
					return;
				}
				if (position == 6) {
					showLanguageMenu(view, optionsPopup);
					return;
				}

				optionsPopup.dismiss();
				if (position == 0) {
					wordWrap = !wordWrap;
					prefs.edit().putBoolean("word_wrap", wordWrap).commit();
					applySettingsToAllTabs();
				} else if (position == 1) {
					showLineNumbers = !showLineNumbers;
					prefs.edit().putBoolean("line_numbers", showLineNumbers).commit();
					applySettingsToAllTabs();
				} else if (position == 2) {
					darkTheme = !darkTheme;
					prefs.edit().putBoolean("dark_theme", darkTheme).commit();
					applyTheme();
					Toast.makeText(MainActivity.this, darkTheme ? "Theme: Dark" : "Theme: Light", Toast.LENGTH_SHORT)
							.show();
				} else if (position == 3) {
					keepScreenOn = !keepScreenOn;
					prefs.edit().putBoolean("keep_screen_on", keepScreenOn).commit();
					applyKeepScreenOn();
					Toast.makeText(MainActivity.this, keepScreenOn ? "Keep Screen On" : "Screen can sleep",
							Toast.LENGTH_SHORT).show();
				} else if (position == 4) {
					if (!softTabs) {
						softTabs = true;
						tabSize = 4;
					} else if (tabSize == 4) {
						tabSize = 2;
					} else {
						softTabs = false;
					}
					prefs.edit().putBoolean("soft_tabs", softTabs).putInt("tab_size", tabSize).commit();
					Toast.makeText(MainActivity.this, softTabs ? ("Soft Tabs: " + tabSize + " spaces") : "Hard Tabs",
							Toast.LENGTH_SHORT).show();
				} else if (position == 7) {
					showGuides = !showGuides;
					prefs.edit().putBoolean("indent_guides", showGuides).commit();
					applySettingsToAllTabs();
				}
			}
		});

		optionsPopup.showAsDropDown(anchor, 0, 0);
	}

	/** Encoding cascading submenu shown beside the Encoding row. */
	private void showEncodingMenu(final View rowAnchor, final PopupWindow optionsPopup) {
		if (rowAnchor == null)
			return;
		final String[] items = new String[]{"UTF-8", "ISO-8859-1", "Windows-1252", "UTF-16LE", "UTF-16BE"};
		final int bg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int textCol = darkTheme ? Color.parseColor("#E0E0E0") : Color.parseColor("#1A1A1A");
		final int divider = darkTheme ? Color.parseColor("#3A3A3A") : Color.parseColor("#DDDDDD");

		ListView list = new ListView(this);
		list.setBackgroundColor(bg);
		list.setDivider(new android.graphics.drawable.ColorDrawable(divider));
		list.setDividerHeight(1);
		list.setPadding(0, 4, 0, 4);
		ArrayAdapter adapter = new ArrayAdapter(this, android.R.layout.simple_list_item_1, items) {
			public View getView(int position, View convertView, ViewGroup parent) {
				View v = super.getView(position, convertView, parent);
				v.setBackgroundColor(bg);
				if (v instanceof TextView) {
					TextView tv = (TextView) v;
					tv.setTextColor(textCol);
					tv.setTextSize(POPUP_MENU_ITEM_FONT_SP);
					tv.setPadding(24, 16, 24, 16);
				}
				return v;
			}
		};
		list.setAdapter(adapter);

		android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
		int width = (int) (180 * dm.density);
		int maxW = (int) (dm.widthPixels * 0.7f);
		if (width > maxW)
			width = maxW;

		final PopupWindow encodingPopup = new PopupWindow(list, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
		encodingPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
		encodingPopup.setOutsideTouchable(true);
		encodingPopup.setFocusable(true);
		if (android.os.Build.VERSION.SDK_INT >= 21)
			encodingPopup.setElevation(14f);

		list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				setEncoding(items[position]);
				encodingPopup.dismiss();
				if (optionsPopup != null)
					optionsPopup.dismiss();
			}
		});

		// rowAnchor is inside the Options popup. Offset one full row width to
		// the right and one row height upward so both menu tops align.
		encodingPopup.showAsDropDown(rowAnchor, rowAnchor.getWidth(), -rowAnchor.getHeight());
	}

	/** Language cascading submenu shown beside the Language row. */
	private void showLanguageMenu(final View rowAnchor, final PopupWindow optionsPopup) {
		if (rowAnchor == null)
			return;
		final String[] ids = SyntaxHighlighter.allLanguageIds();
		final String[] items = SyntaxHighlighter.allLanguageDisplayNames();
		final int bg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int textCol = darkTheme ? Color.parseColor("#E0E0E0") : Color.parseColor("#1A1A1A");
		final int divider = darkTheme ? Color.parseColor("#3A3A3A") : Color.parseColor("#DDDDDD");

		ListView list = new ListView(this);
		list.setBackgroundColor(bg);
		list.setDivider(new android.graphics.drawable.ColorDrawable(divider));
		list.setDividerHeight(1);
		list.setPadding(0, 4, 0, 4);
		ArrayAdapter adapter = new ArrayAdapter(this, android.R.layout.simple_list_item_1, items) {
			public View getView(int position, View convertView, ViewGroup parent) {
				View v = super.getView(position, convertView, parent);
				v.setBackgroundColor(bg);
				if (v instanceof TextView) {
					TextView tv = (TextView) v;
					tv.setTextColor(textCol);
					tv.setTextSize(POPUP_MENU_ITEM_FONT_SP);
					tv.setPadding(24, 16, 24, 16);
				}
				return v;
			}
		};
		list.setAdapter(adapter);

		android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
		int width = (int) (200 * dm.density);
		int maxW = (int) (dm.widthPixels * 0.7f);
		if (width > maxW)
			width = maxW;

		final PopupWindow languagePopup = new PopupWindow(list, width, (int) (dm.heightPixels * 0.6f), true);
		languagePopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
		languagePopup.setOutsideTouchable(true);
		languagePopup.setFocusable(true);
		if (android.os.Build.VERSION.SDK_INT >= 21)
			languagePopup.setElevation(14f);

		list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
				setLanguage(ids[position]);
				languagePopup.dismiss();
				if (optionsPopup != null)
					optionsPopup.dismiss();
			}
		});

		// rowAnchor is inside the Options popup. Offset one full row width to
		// the right and one row height upward so both menu tops align.
		languagePopup.showAsDropDown(rowAnchor, rowAnchor.getWidth(), -rowAnchor.getHeight());
	}

	private void setLanguage(String langId) {
		if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
			EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
			tab.language = langId;
			tab.languageManual = true;
			applyHighlighting(tab);
			updateLanguageStatus(tab);
			Toast.makeText(this, "Language: " + SyntaxHighlighter.displayName(langId), Toast.LENGTH_SHORT).show();
		}
	}

	private void setEncoding(String enc) {
		if (currentTabIndex >= 0 && currentTabIndex < tabs.size()) {
			EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
			tab.encoding = enc;
			statusEncoding.setText(enc);
			Toast.makeText(this, "Encoding: " + enc, Toast.LENGTH_SHORT).show();
		}
	}

	private void printCurrent() {
		if (currentTabIndex < 0 || currentTabIndex >= tabs.size()) {
			return;
		}
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		final String content = tab.getContent();
		final String jobName = tab.title != null ? tab.title : "myPad++";
		try {
			android.print.PrintManager printManager = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
			if (printManager == null) {
				Toast.makeText(this, "Print service not available", Toast.LENGTH_SHORT).show();
				return;
			}
			printManager.print(jobName, new android.print.PrintDocumentAdapter() {
				private android.print.PrintAttributes printAttrs;

				public void onLayout(android.print.PrintAttributes oldAttributes,
						android.print.PrintAttributes newAttributes, android.os.CancellationSignal cancellationSignal,
						LayoutResultCallback callback, Bundle extras) {
					printAttrs = newAttributes;
					if (cancellationSignal.isCanceled()) {
						callback.onLayoutCancelled();
						return;
					}
					android.print.PrintDocumentInfo info = new android.print.PrintDocumentInfo.Builder(jobName)
							.setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(1)
							.build();
					callback.onLayoutFinished(info, true);
				}

				public void onWrite(android.print.PageRange[] pages, android.os.ParcelFileDescriptor destination,
						android.os.CancellationSignal cancellationSignal, WriteResultCallback callback) {
					android.print.pdf.PrintedPdfDocument pdf = null;
					try {
						pdf = new android.print.pdf.PrintedPdfDocument(MainActivity.this, printAttrs);
						android.graphics.pdf.PdfDocument.Page page = pdf.startPage(0);
						android.graphics.Canvas canvas = page.getCanvas();
						android.text.TextPaint paint = new android.text.TextPaint();
						paint.setColor(Color.BLACK);
						paint.setTextSize(10);
						float x = 40;
						float y = 40;
						float lineHeight = paint.getFontSpacing();
						String[] lines = content.split("\n", -1);
						for (int i = 0; i < lines.length; i++) {
							if (y > canvas.getHeight() - 40) {
								break;
							}
							String draw = lines[i];
							if (draw.length() > 90) {
								draw = draw.substring(0, 90);
							}
							canvas.drawText(draw, x, y, paint);
							y += lineHeight;
						}
						pdf.finishPage(page);
						java.io.FileOutputStream out = new java.io.FileOutputStream(destination.getFileDescriptor());
						pdf.writeTo(out);
						out.close();
						callback.onWriteFinished(new android.print.PageRange[]{android.print.PageRange.ALL_PAGES});
					} catch (Exception e) {
						callback.onWriteFailed(e.getMessage());
					} finally {
						if (pdf != null) {
							pdf.close();
						}
					}
				}
			}, null);
		} catch (Exception e) {
			Toast.makeText(this, "Print failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
		}
	}

	// -------------------- New feature helpers --------------------

	private void requestNewTab() {
		if (tabs.size() == 1) {
			EditorTab only = (EditorTab) tabs.get(0);
			if (only.modified && only.uri == null) {
				Toast.makeText(this, "Unsaved changes in the only tab - save or clear first", Toast.LENGTH_SHORT)
						.show();
				// Still allow new tab alongside dirty content
			}
		}
		createNewTab();
	}

	private void applyKeepScreenOn() {
		if (getWindow() == null)
			return;
		if (keepScreenOn) {
			getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
			getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
	}

	private void hapticTick() {
		try {
			View v = tabContainer != null ? tabContainer : findViewById(android.R.id.content);
			if (v != null) {
				v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
			}
		} catch (Exception ignored) {
		}
	}

	private void loadRecentFiles() {
		if (recentUris == null)
			recentUris = new ArrayList();
		recentUris.clear();
		String packed = prefs.getString("recent_uris", "");
		if (packed == null || packed.length() == 0)
			return;
		String[] parts = packed.split("\\n");
		for (int i = 0; i < parts.length; i++) {
			if (parts[i] != null && parts[i].length() > 0) {
				recentUris.add(parts[i]);
			}
		}
	}

	private void saveRecentFiles() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < recentUris.size(); i++) {
			if (i > 0)
				sb.append('\n');
			sb.append((String) recentUris.get(i));
		}
		prefs.edit().putString("recent_uris", sb.toString()).commit();
	}

	private void addRecentUri(Uri uri) {
		if (uri == null)
			return;
		String s = uri.toString();
		for (int i = recentUris.size() - 1; i >= 0; i--) {
			if (s.equals(recentUris.get(i))) {
				recentUris.remove(i);
			}
		}
		recentUris.add(0, s);
		while (recentUris.size() > RECENT_MAX) {
			recentUris.remove(recentUris.size() - 1);
		}
		saveRecentFiles();
	}

	/** Pretty file name from a stored recent URI string (strips primary: and paths). */
	private String recentDisplayName(String uriStr) {
		if (uriStr == null || uriStr.length() == 0)
			return "Untitled";
		String name = uriStr;
		try {
			name = java.net.URLDecoder.decode(uriStr, "UTF-8");
		} catch (Exception ignored) {
		}
		// Prefer last path segment of the URI
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (slash >= 0 && slash < name.length() - 1)
			name = name.substring(slash + 1);
		// DocumentsContract often uses "primary:Folder/file.txt"
		if (name.regionMatches(true, 0, "primary:", 0, 8))
			name = name.substring(8);
		// Also strip other volume prefixes like "home:" / "raw:"
		int colon = name.indexOf(':');
		if (colon >= 0 && colon < name.length() - 1) {
			// Only strip if it looks like a storage volume id (no slash before colon)
			if (name.lastIndexOf('/', colon) < 0 && name.lastIndexOf('\\', colon) < 0)
				name = name.substring(colon + 1);
		}
		// Final path segment if folders remain
		slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (slash >= 0 && slash < name.length() - 1)
			name = name.substring(slash + 1);
		if (name.length() == 0)
			return "Untitled";
		return name;
	}

	private void showRecentFilesMenu(View anchor) {
		if (recentUris == null || recentUris.size() == 0) {
			Toast.makeText(this, "No recent files", Toast.LENGTH_SHORT).show();
			return;
		}

		final int dlgBg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final int divider = darkTheme ? Color.parseColor("#3A3A3A") : Color.parseColor("#DDDDDD");
		final int removeCol = darkTheme ? Color.parseColor("#FF8A80") : Color.parseColor("#C62828");
		final float radius = 16f * getResources().getDisplayMetrics().density;

		android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
		panel.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		panel.setCornerRadius(radius);
		panel.setColor(dlgBg);

		final LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(24, 20, 24, 16);
		layout.setBackgroundDrawable(panel);

		final TextView title = new TextView(this);
		title.setText("Recent files");
		title.setTextColor(dlgText);
		title.setTextSize(POPUP_HEADING_FONT_SP);
		title.setPadding(0, 0, 0, 16);
		layout.addView(title);

		final LinearLayout listHost = new LinearLayout(this);
		listHost.setOrientation(LinearLayout.VERTICAL);
		layout.addView(listHost);

		final Dialog[] dialogRef = new Dialog[1];
		final Runnable[] rebuildRef = new Runnable[1];

		rebuildRef[0] = new Runnable() {
			public void run() {
				listHost.removeAllViews();
				if (recentUris.size() == 0) {
					if (dialogRef[0] != null)
						dialogRef[0].dismiss();
					Toast.makeText(MainActivity.this, "No recent files", Toast.LENGTH_SHORT).show();
					return;
				}
				for (int i = 0; i < recentUris.size(); i++) {
					final int index = i;
					LinearLayout row = new LinearLayout(MainActivity.this);
					row.setOrientation(LinearLayout.HORIZONTAL);
					row.setGravity(android.view.Gravity.CENTER_VERTICAL);
					row.setPadding(8, 10, 4, 10);
					row.setMinimumHeight((int) (44 * getResources().getDisplayMetrics().density));

					TextView name = new TextView(MainActivity.this);
					name.setText(recentDisplayName((String) recentUris.get(index)));
					name.setTextColor(dlgText);
					name.setTextSize(POPUP_CONTENT_FONT_SP);
					name.setSingleLine(true);
					name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
					LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
							ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
					name.setLayoutParams(nameLp);
					row.addView(name);

					TextView remove = new TextView(MainActivity.this);
					remove.setText("X");
					remove.setTextColor(removeCol);
					remove.setTextSize(POPUP_CONTENT_FONT_SP);
					remove.setPadding(16, 8, 12, 8);
					remove.setOnClickListener(new View.OnClickListener() {
						public void onClick(View v) {
							if (index < 0 || index >= recentUris.size())
								return;
							String removed = recentDisplayName((String) recentUris.get(index));
							recentUris.remove(index);
							saveRecentFiles();
							Toast.makeText(MainActivity.this, "Removed: " + removed, Toast.LENGTH_SHORT).show();
							if (rebuildRef[0] != null)
								rebuildRef[0].run();
						}
					});
					row.addView(remove);

					row.setOnClickListener(new View.OnClickListener() {
						public void onClick(View v) {
							if (index < 0 || index >= recentUris.size())
								return;
							try {
								Uri uri = Uri.parse((String) recentUris.get(index));
								if (dialogRef[0] != null)
									dialogRef[0].dismiss();
								openUri(uri);
							} catch (Exception e) {
								Toast.makeText(MainActivity.this, "Cannot open recent file", Toast.LENGTH_SHORT).show();
							}
						}
					});
					listHost.addView(row);

					if (i < recentUris.size() - 1) {
						View line = new View(MainActivity.this);
						line.setBackgroundColor(divider);
						listHost.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
					}
				}

				View line = new View(MainActivity.this);
				line.setBackgroundColor(divider);
				listHost.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

				TextView clear = new TextView(MainActivity.this);
				clear.setText("Clear all");
				clear.setTextColor(removeCol);
				clear.setTextSize(POPUP_CONTENT_FONT_SP);
				clear.setPadding(8, 14, 8, 8);
				clear.setOnClickListener(new View.OnClickListener() {
					public void onClick(View v) {
						recentUris.clear();
						saveRecentFiles();
						if (dialogRef[0] != null)
							dialogRef[0].dismiss();
						Toast.makeText(MainActivity.this, "Recent files cleared", Toast.LENGTH_SHORT).show();
					}
				});
				listHost.addView(clear);
			}
		};
		rebuildRef[0].run();

		final Dialog dialog = new Dialog(this);
		dialogRef[0] = dialog;
		dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
		dialog.setContentView(layout);
		dialog.setCancelable(true);

		if (dialog.getWindow() != null) {
			android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
			winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
			winBg.setCornerRadius(radius);
			winBg.setColor(dlgBg);
			dialog.getWindow().setBackgroundDrawable(winBg);
			android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
			wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
			dialog.getWindow().setAttributes(wlp);
		}
		dialog.show();
		makeDialogDraggableDialog(dialog, title);
	}

	private void shareAsText() {
		if (currentTabIndex < 0)
			return;
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("text/plain");
		send.putExtra(Intent.EXTRA_TEXT, tab.getContent());
		send.putExtra(Intent.EXTRA_SUBJECT, tab.title);
		try {
			startActivity(Intent.createChooser(send, "Share text"));
		} catch (Exception e) {
			Toast.makeText(this, "No app to share", Toast.LENGTH_SHORT).show();
		}
	}

	private void shareAsFile() {
		if (currentTabIndex < 0 || currentTabIndex >= tabs.size())
			return;
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		try {
			// Share the editor's current content (including unsaved edits) as a real file.
			String name = tab.title.equals("Untitled") ? "untitled.txt" : tab.title;
			name = name.replace('/', '_').replace((char) 92, '_');
			java.io.File dir = new java.io.File(getCacheDir(), "share");
			if (!dir.isDirectory()) {
				dir.mkdirs();
			}
			java.io.File[] old = dir.listFiles();
			if (old != null) {
				for (int i = 0; i < old.length; i++) {
					old[i].delete();
				}
			}
			java.io.File out = new java.io.File(dir, name);
			String enc = tab.encoding != null ? tab.encoding : "UTF-8";
			BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
					new java.io.FileOutputStream(out), enc));
			writer.write(tab.getContent());
			writer.close();

			Uri shareUri = Uri.parse("content://" + ShareProvider.AUTHORITY + "/" + Uri.encode(name));
			String mime = mimeForSaveName(name);
			if (mime.equals("application/octet-stream")) {
				mime = "text/plain";
			}
			Intent send = new Intent(Intent.ACTION_SEND);
			send.setType(mime);
			send.putExtra(Intent.EXTRA_STREAM, shareUri);
			send.putExtra(Intent.EXTRA_SUBJECT, name);
			send.setClipData(android.content.ClipData.newRawUri(name, shareUri));
			send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			startActivity(Intent.createChooser(send, "Share file"));
		} catch (Exception e) {
			Toast.makeText(this, "Cannot share as file: " + e.getMessage(), Toast.LENGTH_LONG).show();
		}
	}

	private void showDocumentStats() {
		if (currentTabIndex < 0)
			return;
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		String content = tab.getContent();
		int chars = content.length();
		int lines = 1;
		int words = 0;
		boolean inWord = false;
		for (int i = 0; i < content.length(); i++) {
			char c = content.charAt(i);
			if (c == '\n')
				lines++;
			if (Character.isWhitespace(c)) {
				inWord = false;
			} else if (!inWord) {
				inWord = true;
				words++;
			}
		}
		int sel = 0;
		int a = tab.editor.getSelectionStart();
		int b = tab.editor.getSelectionEnd();
		if (b > a)
			sel = b - a;
		String msg = "Characters: " + chars + "\nWords: " + words + "\nLines: " + lines;
		if (sel > 0)
			msg = msg + "\nSelection: " + sel;
		Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
	}

	private void showFindAllDialog() {
		if (currentTabIndex < 0)
			return;
		final EditorTab tab = (EditorTab) tabs.get(currentTabIndex);

		final int dlgBg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final int dlgHint = Color.parseColor("#888888");
		final int dlgField = darkTheme ? Color.parseColor("#1E1E1E") : Color.parseColor("#F0F0F0");
		final float radius = 16f * getResources().getDisplayMetrics().density;

		android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
		panel.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		panel.setCornerRadius(radius);
		panel.setColor(dlgBg);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(32, 20, 32, 20);
		layout.setBackgroundDrawable(panel);

		TextView title = new TextView(this);
		title.setText("Find All");
		title.setTextColor(dlgText);
		title.setTextSize(POPUP_HEADING_FONT_SP);
		title.setPadding(0, 0, 0, 16);
		layout.addView(title);

		final EditText input = new EditText(this);
		input.setHint("Find all...");
		input.setTextColor(dlgText);
		input.setTextSize(POPUP_CONTENT_FONT_SP);
		input.setHintTextColor(dlgHint);
		input.setBackgroundColor(dlgField);
		input.setPadding(16, 14, 16, 14);
		input.setSingleLine(true);
		if (lastFindAllQuery != null && lastFindAllQuery.length() > 0)
			input.setText(lastFindAllQuery);
		else if (lastFindQuery != null && lastFindQuery.length() > 0)
			input.setText(lastFindQuery);
		LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		ilp.bottomMargin = 12;
		input.setLayoutParams(ilp);
		layout.addView(input);

		LinearLayout checks = new LinearLayout(this);
		checks.setOrientation(LinearLayout.HORIZONTAL);
		checks.setPadding(0, 0, 0, 20);

		final android.widget.CheckBox regexBox = new android.widget.CheckBox(this);
		regexBox.setText("Regex     ");
		stylePopupCheckBox(regexBox);
		regexBox.setChecked(lastFindAllRegex);
		checks.addView(regexBox);

		final android.widget.CheckBox caseBox = new android.widget.CheckBox(this);
		caseBox.setText("Case sensitive");
		stylePopupCheckBox(caseBox);
		caseBox.setChecked(lastFindAllCase);
		checks.addView(caseBox);
		layout.addView(checks);

		LinearLayout buttonRow = new LinearLayout(this);
		buttonRow.setOrientation(LinearLayout.HORIZONTAL);
		buttonRow.setGravity(android.view.Gravity.END);

		LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		btnLp.leftMargin = (int) (12 * getResources().getDisplayMetrics().density);
		btnLp = popupButtonLayoutParams(btnLp);

		final Dialog dialog = new Dialog(this);
		dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
		dialog.setCancelable(true);

		Button btnFind = new Button(this);
		btnFind.setText("Find");
		styleGreyRoundButton(btnFind);
		btnFind.setLayoutParams(btnLp);
		btnFind.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				String q = input.getText().toString();
				lastFindAllQuery = q;
				lastFindAllRegex = regexBox.isChecked();
				lastFindAllCase = caseBox.isChecked();
				if (q.length() > 0) {
					lastFindQuery = q;
					lastFindRegex = lastFindAllRegex;
					lastFindCase = lastFindAllCase;
				}
				dialog.dismiss();
				runFindAll(tab, q, caseBox.isChecked(), regexBox.isChecked());
			}
		});
		buttonRow.addView(btnFind);

		Button btnReset = new Button(this);
		btnReset.setText("Reset");
		styleGreyRoundButton(btnReset);
		btnReset.setLayoutParams(btnLp);
		btnReset.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				input.setText("");
				regexBox.setChecked(false);
				caseBox.setChecked(false);
				lastFindAllQuery = "";
				lastFindAllRegex = false;
				lastFindAllCase = false;
				input.requestFocus();
			}
		});
		buttonRow.addView(btnReset);

		layout.addView(buttonRow);

		dialog.setContentView(layout);
		dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
			public void onDismiss(DialogInterface d) {
				lastFindAllQuery = input.getText().toString();
				lastFindAllRegex = regexBox.isChecked();
				lastFindAllCase = caseBox.isChecked();
			}
		});

		if (dialog.getWindow() != null) {
			android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
			winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
			winBg.setCornerRadius(radius);
			winBg.setColor(dlgBg);
			dialog.getWindow().setBackgroundDrawable(winBg);
			android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
			wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
			dialog.getWindow().setAttributes(wlp);
		}
		dialog.show();
		makeDialogDraggableDialog(dialog, title);
		input.requestFocus();
		if (input.getText().length() > 0)
			input.setSelection(input.getText().length());
	}

	private void runFindAll(final EditorTab tab, final String query, boolean caseSensitive, boolean regex) {
		if (query == null || query.length() == 0)
			return;
		String content = tab.getContent();
		ArrayList lines = new ArrayList();
		ArrayList positions = new ArrayList();
		ArrayList matchLens = new ArrayList();

		if (regex) {
			try {
				int flags = caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE;
				java.util.regex.Pattern p = java.util.regex.Pattern.compile(query, flags);
				java.util.regex.Matcher m = p.matcher(content);
				while (m.find()) {
					int pos = m.start();
					int mlen = m.end() - m.start();
					if (mlen < 1)
						mlen = 1;
					int line = 1;
					for (int i = 0; i < pos; i++) {
						if (content.charAt(i) == '\n')
							line++;
					}
					int lineEnd = content.indexOf('\n', pos);
					if (lineEnd < 0)
						lineEnd = content.length();
					int lineStart = pos;
					while (lineStart > 0 && content.charAt(lineStart - 1) != '\n')
						lineStart--;
					String snippet = content.substring(lineStart, lineEnd).trim();
					if (snippet.length() > 60)
						snippet = snippet.substring(0, 60) + "...";
					lines.add("L" + line + ": " + snippet);
					positions.add(Integer.valueOf(pos));
					matchLens.add(Integer.valueOf(mlen));
					if (lines.size() >= 200)
						break;
				}
			} catch (Exception e) {
				Toast.makeText(this, "Invalid regex", Toast.LENGTH_SHORT).show();
				return;
			}
		} else {
			String searchIn = caseSensitive ? content : content.toLowerCase();
			String q = caseSensitive ? query : query.toLowerCase();
			int from = 0;
			while (from < content.length()) {
				int pos = searchIn.indexOf(q, from);
				if (pos < 0)
					break;
				int line = 1;
				for (int i = 0; i < pos; i++) {
					if (content.charAt(i) == '\n')
						line++;
				}
				int lineEnd = content.indexOf('\n', pos);
				if (lineEnd < 0)
					lineEnd = content.length();
				int lineStart = pos;
				while (lineStart > 0 && content.charAt(lineStart - 1) != '\n')
					lineStart--;
				String snippet = content.substring(lineStart, lineEnd).trim();
				if (snippet.length() > 60)
					snippet = snippet.substring(0, 60) + "...";
				lines.add("L" + line + ": " + snippet);
				positions.add(Integer.valueOf(pos));
				matchLens.add(Integer.valueOf(query.length()));
				from = pos + Math.max(1, query.length());
				if (lines.size() >= 200)
					break;
			}
		}
		if (lines.size() == 0) {
			Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show();
			return;
		}

		final int dlgBg = darkTheme ? Color.parseColor("#2A2A2A") : Color.parseColor("#FFFFFF");
		final int dlgText = darkTheme ? Color.WHITE : Color.parseColor("#1A1A1A");
		final int divider = darkTheme ? Color.parseColor("#3A3A3A") : Color.parseColor("#DDDDDD");
		final float radius = 16f * getResources().getDisplayMetrics().density;

		android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
		panel.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
		panel.setCornerRadius(radius);
		panel.setColor(dlgBg);

		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(24, 20, 24, 16);
		layout.setBackgroundDrawable(panel);

		TextView title = new TextView(this);
		title.setText("Matches: " + lines.size());
		title.setTextColor(dlgText);
		title.setTextSize(POPUP_HEADING_FONT_SP);
		title.setPadding(0, 0, 0, 12);
		layout.addView(title);

		android.widget.ScrollView scroll = new android.widget.ScrollView(this);
		LinearLayout listHost = new LinearLayout(this);
		listHost.setOrientation(LinearLayout.VERTICAL);

		final Dialog[] dialogRef = new Dialog[1];
		final ArrayList posCopy = positions;
		final ArrayList lenCopy = matchLens;

		for (int i = 0; i < lines.size(); i++) {
			final int index = i;
			TextView row = new TextView(this);
			row.setText((String) lines.get(i));
			row.setTextColor(dlgText);
			row.setTextSize(POPUP_CONTENT_FONT_SP);
			row.setPadding(8, 12, 8, 12);
			row.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					int pos = ((Integer) posCopy.get(index)).intValue();
					int matchLen = ((Integer) lenCopy.get(index)).intValue();
					tab.editor.setSelection(pos, pos + matchLen);
					tab.editor.requestFocus();
					updateCursorStatus(tab);
					tab.updateLineNumbers();
					if (dialogRef[0] != null)
						dialogRef[0].dismiss();
				}
			});
			listHost.addView(row);
			if (i < lines.size() - 1) {
				View line = new View(this);
				line.setBackgroundColor(divider);
				listHost.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
			}
		}
		scroll.addView(listHost);
		int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
		LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH);
		scroll.setLayoutParams(scrollLp);
		layout.addView(scroll);

		final Dialog dialog = new Dialog(this);
		dialogRef[0] = dialog;
		dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
		dialog.setContentView(layout);
		dialog.setCancelable(true);

		if (dialog.getWindow() != null) {
			android.graphics.drawable.GradientDrawable winBg = new android.graphics.drawable.GradientDrawable();
			winBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
			winBg.setCornerRadius(radius);
			winBg.setColor(dlgBg);
			dialog.getWindow().setBackgroundDrawable(winBg);
			android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
			wlp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
			dialog.getWindow().setAttributes(wlp);
		}
		dialog.show();
		makeDialogDraggableDialog(dialog, title);
	}

	private void duplicateLine() {
		if (currentTabIndex < 0)
			return;
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		Editable ed = tab.editor.getText();
		int pos = tab.editor.getSelectionStart();
		if (pos < 0)
			pos = 0;
		String s = ed.toString();
		int start = pos;
		while (start > 0 && s.charAt(start - 1) != '\n')
			start--;
		int end = pos;
		while (end < s.length() && s.charAt(end) != '\n')
			end++;
		String line = s.substring(start, end);
		String insert = line + "\n";
		ed.insert(end < s.length() ? end + 1 : end, end < s.length() ? line + "\n" : "\n" + line);
		tab.modified = true;
		updateTabTitle(tab);
	}

	private void moveLine(int direction) {
		if (currentTabIndex < 0)
			return;
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		Editable ed = tab.editor.getText();
		String s = ed.toString();
		int pos = tab.editor.getSelectionStart();
		if (pos < 0)
			pos = 0;
		int start = pos;
		while (start > 0 && s.charAt(start - 1) != '\n')
			start--;
		int end = pos;
		while (end < s.length() && s.charAt(end) != '\n')
			end++;
		String line = s.substring(start, end);
		if (direction < 0) {
			if (start == 0)
				return;
			int prevEnd = start - 1;
			int prevStart = prevEnd;
			while (prevStart > 0 && s.charAt(prevStart - 1) != '\n')
				prevStart--;
			String prev = s.substring(prevStart, prevEnd);
			StringBuilder nb = new StringBuilder();
			nb.append(s.substring(0, prevStart));
			nb.append(line);
			nb.append('\n');
			nb.append(prev);
			nb.append(s.substring(end));
			ed.replace(0, s.length(), nb.toString());
			tab.editor.setSelection(prevStart + line.length() > 0 ? prevStart : 0);
		} else {
			if (end >= s.length())
				return;
			int nextStart = end + 1;
			int nextEnd = nextStart;
			while (nextEnd < s.length() && s.charAt(nextEnd) != '\n')
				nextEnd++;
			String next = s.substring(nextStart, nextEnd);
			StringBuilder nb = new StringBuilder();
			nb.append(s.substring(0, start));
			nb.append(next);
			nb.append('\n');
			nb.append(line);
			nb.append(s.substring(nextEnd));
			ed.replace(0, s.length(), nb.toString());
			tab.editor.setSelection(start + next.length() + 1);
		}
		tab.modified = true;
		updateTabTitle(tab);
		updateCursorStatus(tab);
	}

	private void toggleComment() {
		if (currentTabIndex < 0)
			return;
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		String prefix = "//";
		String lang = tab.language != null ? tab.language : "text";
		if ("python".equals(lang) || "shell".equals(lang) || "yaml".equals(lang) || "toml".equals(lang)
				|| "dockerfile".equals(lang)) {
			prefix = "#";
		} else if ("sql".equals(lang)) {
			prefix = "--";
		} else if ("html".equals(lang) || "xml".equals(lang)) {
			prefix = null; // simple skip for block comments
		}
		if (prefix == null) {
			Toast.makeText(this, "Comment not supported for this language", Toast.LENGTH_SHORT).show();
			return;
		}
		Editable ed = tab.editor.getText();
		String s = ed.toString();
		int a = tab.editor.getSelectionStart();
		int b = tab.editor.getSelectionEnd();
		if (a < 0)
			a = 0;
		if (b < a)
			b = a;
		int start = a;
		while (start > 0 && s.charAt(start - 1) != '\n')
			start--;
		int end = b;
		while (end < s.length() && s.charAt(end) != '\n')
			end++;
		String block = s.substring(start, end);
		String[] parts = block.split("\n", -1);
		boolean allCommented = true;
		for (int i = 0; i < parts.length; i++) {
			String t = parts[i].trim();
			if (t.length() > 0 && !t.startsWith(prefix)) {
				allCommented = false;
				break;
			}
		}
		StringBuilder nb = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0)
				nb.append('\n');
			String line = parts[i];
			if (allCommented) {
				int idx = line.indexOf(prefix);
				if (idx >= 0) {
					nb.append(line.substring(0, idx));
					nb.append(line.substring(idx + prefix.length()));
					if (nb.length() > 0 && line.length() > idx + prefix.length()
							&& line.charAt(idx + prefix.length()) == ' ') {
						// already handled; strip one space if present after prefix was removed above - simplify:
					}
				} else {
					nb.append(line);
				}
			} else {
				if (line.trim().length() == 0)
					nb.append(line);
				else
					nb.append(prefix).append(' ').append(line);
			}
		}
		// Cleaner strip when uncommenting
		if (allCommented) {
			nb = new StringBuilder();
			for (int i = 0; i < parts.length; i++) {
				if (i > 0)
					nb.append('\n');
				String line = parts[i];
				String trimmed = line.replaceFirst("^[ \\t]*" + java.util.regex.Pattern.quote(prefix) + " ?", "");
				nb.append(trimmed);
			}
		}
		ed.replace(start, end, nb.toString());
		tab.modified = true;
		updateTabTitle(tab);
	}

	private void convertCase(boolean upper) {
		if (currentTabIndex < 0)
			return;
		EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
		int a = tab.editor.getSelectionStart();
		int b = tab.editor.getSelectionEnd();
		if (a < 0 || b <= a) {
			Toast.makeText(this, "Select text first", Toast.LENGTH_SHORT).show();
			return;
		}
		Editable ed = tab.editor.getText();
		String sel = ed.subSequence(a, b).toString();
		ed.replace(a, b, upper ? sel.toUpperCase() : sel.toLowerCase());
		tab.editor.setSelection(a, a + sel.length());
		tab.modified = true;
		updateTabTitle(tab);
	}

	/** Current-line background + matching bracket highlight. */
	private void highlightExtras(EditorTab tab) {
		if (tab == null || tab.editor == null)
			return;
		try {
			Editable ed = tab.editor.getText();
			// Remove previous extras
			Object[] spans = ed.getSpans(0, ed.length(), LineHighlightSpan.class);
			for (int i = 0; i < spans.length; i++)
				ed.removeSpan(spans[i]);
			spans = ed.getSpans(0, ed.length(), BracketHighlightSpan.class);
			for (int i = 0; i < spans.length; i++)
				ed.removeSpan(spans[i]);

			int pos = tab.editor.getSelectionStart();
			if (pos < 0)
				pos = 0;
			CharSequence s = ed; // no whole-document copy
			if (s.length() == 0)
				return;

			// Current line
			int lineStart = pos;
			while (lineStart > 0 && s.charAt(lineStart - 1) != '\n')
				lineStart--;
			int lineEnd = pos;
			while (lineEnd < s.length() && s.charAt(lineEnd) != '\n')
				lineEnd++;
			int lineBg = darkTheme ? Color.parseColor("#2A2A3A") : Color.parseColor("#FFF8E1");
			ed.setSpan(new LineHighlightSpan(lineBg), lineStart, lineEnd,
					android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

			// Matching brackets
			int checkPos = pos;
			if (checkPos > 0 && checkPos <= s.length()) {
				char cLeft = s.charAt(checkPos - 1);
				char open = 0, close = 0;
				boolean forward = true;
				if (cLeft == '(' || cLeft == '[' || cLeft == '{') {
					open = cLeft;
					close = cLeft == '(' ? ')' : (cLeft == '[' ? ']' : '}');
					forward = true;
					checkPos = checkPos - 1;
				} else if (cLeft == ')' || cLeft == ']' || cLeft == '}') {
					close = cLeft;
					open = cLeft == ')' ? '(' : (cLeft == ']' ? '[' : '{');
					forward = false;
					checkPos = checkPos - 1;
				} else if (pos < s.length()) {
					char cRight = s.charAt(pos);
					if (cRight == '(' || cRight == '[' || cRight == '{') {
						open = cRight;
						close = cRight == '(' ? ')' : (cRight == '[' ? ']' : '}');
						forward = true;
						checkPos = pos;
					} else if (cRight == ')' || cRight == ']' || cRight == '}') {
						close = cRight;
						open = cRight == ')' ? '(' : (cRight == ']' ? '[' : '{');
						forward = false;
						checkPos = pos;
					} else {
						return;
					}
				} else {
					return;
				}
				int match = findMatchingBracket(s, checkPos, open, close, forward);
				if (match >= 0) {
					int brBg = darkTheme ? Color.parseColor("#5C6BC0") : Color.parseColor("#C5CAE9");
					ed.setSpan(new BracketHighlightSpan(brBg), checkPos, checkPos + 1,
							android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
					ed.setSpan(new BracketHighlightSpan(brBg), match, match + 1,
							android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				}
			}
		} catch (Exception ignored) {
		}
	}

	private int findMatchingBracket(CharSequence s, int pos, char open, char close, boolean forward) {
		int depth = 0;
		final int LIMIT = 20000; // never scan the whole file for a bracket
		if (forward) {
			for (int i = pos; i < s.length() && i < pos + LIMIT; i++) {
				char c = s.charAt(i);
				if (c == open)
					depth++;
				else if (c == close) {
					depth--;
					if (depth == 0)
						return i;
				}
			}
		} else {
			for (int i = pos; i >= 0 && i > pos - LIMIT; i--) {
				char c = s.charAt(i);
				if (c == close)
					depth++;
				else if (c == open) {
					depth--;
					if (depth == 0)
						return i;
				}
			}
		}
		return -1;
	}

	@Override
	public boolean dispatchKeyEvent(android.view.KeyEvent event) {
		if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
			int meta = event.getMetaState();
			boolean ctrl = (meta & android.view.KeyEvent.META_CTRL_ON) != 0;
			if (ctrl) {
				int key = event.getKeyCode();
				if (key == android.view.KeyEvent.KEYCODE_S) {
					saveCurrent(false);
					return true;
				}
				if (key == android.view.KeyEvent.KEYCODE_F) {
					showFindReplaceDialog();
					return true;
				}
				if (key == android.view.KeyEvent.KEYCODE_G) {
					showGotoDialog();
					return true;
				}
				if (key == android.view.KeyEvent.KEYCODE_Z) {
					if (currentTabIndex >= 0) {
						EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
						if ((meta & android.view.KeyEvent.META_SHIFT_ON) != 0)
							tab.redo();
						else
							tab.undo();
						updateTabTitle(tab);
						updateCursorStatus(tab);
						applyHighlighting(tab);
					}
					return true;
				}
				if (key == android.view.KeyEvent.KEYCODE_Y) {
					if (currentTabIndex >= 0) {
						EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
						tab.redo();
						updateTabTitle(tab);
						updateCursorStatus(tab);
						applyHighlighting(tab);
					}
					return true;
				}
				if (key == android.view.KeyEvent.KEYCODE_D) {
					duplicateLine();
					return true;
				}
			}
			// Soft tab: convert TAB key to spaces
			if (softTabs && event.getKeyCode() == android.view.KeyEvent.KEYCODE_TAB && currentTabIndex >= 0) {
				EditorTab tab = (EditorTab) tabs.get(currentTabIndex);
				if (tab.editor != null && tab.editor.hasFocus()) {
					StringBuilder spaces = new StringBuilder();
					for (int i = 0; i < tabSize; i++)
						spaces.append(' ');
					int a = tab.editor.getSelectionStart();
					int b = tab.editor.getSelectionEnd();
					if (a < 0)
						a = 0;
					if (b < a)
						b = a;
					tab.editor.getText().replace(a, b, spaces.toString());
					return true;
				}
			}
		}
		return super.dispatchKeyEvent(event);
	}

	/** Marker span for current-line background. */
	private static class LineHighlightSpan extends android.text.style.BackgroundColorSpan {
		LineHighlightSpan(int color) {
			super(color);
		}
	}

	/** Marker span for matching brackets. */
	private static class BracketHighlightSpan extends android.text.style.BackgroundColorSpan {
		BracketHighlightSpan(int color) {
			super(color);
		}
	}

	@Override
	public void onBackPressed() {
		if (tabs.size() > 1) {
			closeTab(currentTabIndex);
		} else {
			super.onBackPressed();
		}
	}
}

