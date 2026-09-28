package com.dsh.rearvibe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * RearVibe - a vibe-coded HTML container for the Xiaomi rear screen.
 *
 * Pages live as *.html files in getExternalFilesDir()/pages, so adb push
 * updates content with NO reinstall. Switching happens through on-screen
 * arrows, volume keys, or adb broadcasts handled by VibeReceiver:
 *   LOAD (extra "page"), NEXT, PREV, REFRESH
 */
public class MainActivity extends Activity {

    public static volatile MainActivity instance;

    static final String ACTION_LOAD = "dsh.vibe.LOAD";
    static final String ACTION_NEXT = "dsh.vibe.NEXT";
    static final String ACTION_PREV = "dsh.vibe.PREV";
    static final String ACTION_REFRESH = "dsh.vibe.REFRESH";

    private WebView web;
    private TextView label;
    private File pagesDir;
    private String[] pages = new String[0];
    private int index = 0;
    private SharedPreferences prefs;
    private FrameLayout root;
    private LinearLayout bar;
    private final Handler barHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // No title strip: hide the status bar too (the ActionBar is already
        // gone via Theme.Material.NoActionBar in the manifest).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        // --- full-screen web container ---
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0F172A"));
        configureWeb(web);

        // --- overlay switcher: hidden by default, flashes on page changes ---
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xB0000000);
        bar.setPadding(dp(10), dp(4), dp(10), dp(4));
        bar.setAlpha(0f);
        bar.setVisibility(View.INVISIBLE);

        TextView prev = arrow("\u25C0", v -> step(-1));
        label = new TextView(this);
        label.setTextColor(Color.parseColor("#7DD3FC"));
        label.setTextSize(11);
        label.setPadding(dp(10), 0, dp(10), 0);
        label.setGravity(Gravity.CENTER);
        TextView next = arrow("\u25B6", v -> step(1));
        TextView refresh = arrow("\u21BB", v -> command(ACTION_REFRESH, null));

        bar.addView(prev);
        bar.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(next);
        bar.addView(refresh);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barLp.bottomMargin = dp(6);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(bar, barLp);

        // Full-bleed: the WebView spans the ENTIRE display, no inset padding.
        // Note systemWindowInsets include the display cutout on Android, so
        // even "system-only" padding would re-create the 296px camera strip.
        // Pages keep content clear of the cameras themselves (--cam: 30.33vw).

        setContentView(root);

        // --- content ---
        prefs = getSharedPreferences("vibe", MODE_PRIVATE);
        pagesDir = new File(getExternalFilesDir(null), "pages");
        if (!pagesDir.exists()) pagesDir.mkdirs();
        seedDefaultIfEmpty();
        reloadList();

        Intent intent = getIntent();
        String a = intent == null ? null : intent.getAction();
        boolean isVibeCmd = ACTION_LOAD.equals(a) || ACTION_NEXT.equals(a)
                || ACTION_PREV.equals(a) || ACTION_REFRESH.equals(a)
                || (Intent.ACTION_MAIN.equals(a) && intent.hasExtra("page"));
        if (isVibeCmd) handle(intent);
        else show(currentPageName());   // plain launch (ACTION_MAIN) must load too
    }

    private TextView arrow(String glyph, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(Color.WHITE);
        t.setTextSize(16);
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        t.setOnClickListener(l);
        return t;
    }

    private void configureWeb(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);   // pages may load sibling css/js
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextSize(WebSettings.TextSize.NORMAL);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---------- content management ----------

    private void reloadList() {
        File[] files = pagesDir.listFiles((d, n) ->
                n.toLowerCase(Locale.ROOT).endsWith(".html"));
        if (files == null) files = new File[0];
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) names[i] = files[i].getName();
        Arrays.sort(names);
        pages = names;
        if (index >= pages.length) index = 0;
    }

    private String currentPageName() {
        return pages.length == 0 ? "" : pages[index];
    }

    private void show(String name) {
        if (pages.length == 0) { web.loadUrl("about:blank"); label.setText("no pages"); return; }
        int found = -1;
        for (int i = 0; i < pages.length; i++) if (pages[i].equals(name)) { found = i; break; }
        index = found >= 0 ? found : Math.max(0, Math.min(index, pages.length - 1));
        File f = new File(pagesDir, pages[index]);
        prefs.edit().putString("current", pages[index]).apply();
        label.setText((index + 1) + "/" + pages.length + "  " + pages[index]);
        web.loadUrl(Uri.fromFile(f).toString());
        flashBar();
    }

    /** Show the switcher briefly after a page change, then fade it out. */
    private void flashBar() {
        barHandler.removeCallbacksAndMessages(null);
        bar.setVisibility(View.VISIBLE);
        bar.animate().cancel();
        bar.animate().alpha(1f).setDuration(180).start();
        barHandler.postDelayed(() -> {
            bar.animate().alpha(0f).setDuration(350)
                    .withEndAction(() -> bar.setVisibility(View.INVISIBLE))
                    .start();
        }, 2200);
    }

    private void step(int delta) {
        if (pages.length == 0) return;
        index = (index + delta + pages.length) % pages.length;
        show(currentPageName());
    }

    private void seedDefaultIfEmpty() {
        File[] existing = pagesDir.listFiles();
        if (existing != null && existing.length > 0) return;
        String welcome = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;"
                + "font-family:system-ui;background:linear-gradient(135deg,#0f172a,#1e3a5f);color:#7dd3fc;"
                + "text-align:center}h1{font-size:28px;margin:0 0 8px}p{color:#94a3b8;font-size:13px}</style>"
                + "</head><body><div><h1>RearVibe &#128161;</h1>"
                + "<p>adb push html to:<br>/sdcard/Android/data/com.dsh.rearvibe/files/pages/</p>"
                + "<p>&#9660; switch pages with arrows or:<br>"
                + "am broadcast -n com.dsh.rearvibe/.VibeReceiver -a dsh.vibe.NEXT</p></div></body></html>";
        writePage("welcome.html", welcome);
    }

    private void writePage(String name, String html) {
        try (FileOutputStream out = new FileOutputStream(new File(pagesDir, name))) {
            out.write(html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // ---------- command plumbing ----------

    public void postCommand(Intent intent) {
        runOnUiThread(() -> handle(intent));
    }

    private void command(String action, String page) {
        Intent i = new Intent(action);
        if (page != null) i.putExtra("page", page);
        handle(i);
    }

    private void handle(Intent intent) {
        String action = intent.getAction();
        if (ACTION_NEXT.equals(action)) step(1);
        else if (ACTION_PREV.equals(action)) step(-1);
        else if (ACTION_LOAD.equals(action)) {
            String page = intent.getStringExtra("page");
            if (page != null) {
                reloadList();   // a freshly pushed file must be visible to show()
                show(page);
            }
        } else if (ACTION_REFRESH.equals(action)) {
            reloadList();
            String want = intent.getStringExtra("page");
            show(want != null ? want : currentPageName());
        } else if (Intent.ACTION_MAIN.equals(action) && intent.hasExtra("page")) {
            // cold start via receiver with LOAD semantics
            reloadList();
            show(intent.getStringExtra("page"));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getAction() != null) handle(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume keys page through vibe content while the app is focused.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { step(-1); return true; }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { step(1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
