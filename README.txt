myPad++ (DroidPad++ style editor) - Simple Android Text & Code Editor (AIDE ready)
================================================================

This is a lightweight multi-tab text editor for Android, written in pure Java.
Compatible with AIDE (no AndroidX, no lambdas).

FEATURES
--------
- Multiple tabs (long-press tab to close)
- New / Open / Save / Save As
- Find text
- Go to Line
- Word Wrap, Line Numbers, Dark Theme, Font Size settings
- Share content
- Status bar
- Fully offline

HOW TO OPEN IN AIDE (IMPORTANT)
-------------------------------
1. Extract the zip so you have the folder "DroidPadPlusClone".
2. In AIDE go to: Menu → Open existing project
3. Navigate to and select the "DroidPadPlusClone" folder 
   (the one that contains AndroidManifest.xml).
4. Wait a few seconds. AIDE should detect it as Android project.
5. Tap the play / build button.
6. If you still see "Unknown entity R":
   - Menu → More → Refresh
   - or Menu → Project → Clean
   - then Build again.
7. The first successful build will regenerate the real R.java 
   (the stub in gen/ will be overwritten).

If AIDE still complains, create a NEW empty Android App project 
inside AIDE, then copy the contents of src/ and res/ from this 
project into the new one (overwrite).

REQUIREMENTS
------------
- Android 4.1+ (API 16)
- Target API 28
- No external libraries

NOTES
-----
- Syntax highlighting is intentionally simple / not included 
  (to keep the project small and AIDE-friendly).
- Uses classic Holo themes.
