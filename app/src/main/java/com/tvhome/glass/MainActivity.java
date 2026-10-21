package com.tvhome.glass;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    // ===================== CONFIG: edit these =====================
    static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";
    // Dock apps, left to right (package names). Change order here.
    static final String[] DOCK_PACKAGES = {
            LIVE_TV, "com.google.android.youtube.tv", "com.stremio.one", "com.internet.tvbrowser"};
    // Apps that never appear in the grid.
    static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.tvhome.glass", "me.efesser.flauncher"));
    // ==============================================================

    static final int COLS = 4;
    static final float PILL_H = 38, PILL_Y = 36;

    static class AppItem {
        String pkg, label;
        Intent launch;
        Drawable banner, icon;
        int glow = 0xFF4A6FA5;
        View tile;
        TextView name;
        float x, y, w, h;
    }

    static class DD {
        String label;
        Runnable run;
        DD(String l, Runnable r) { label = l; run = r; }
    }

    int W, H;
    float u, tileW, tileH, gapX, tx0, bandTop, rad;
    FrameLayout root, content, ddPanel;
    GlassView glass;
    GlowView glow;
    TextView greeting, clock, dateView, dockName;
    final TextView[] pills = new TextView[3];
    final float[] pillX = new float[3], pillW = new float[3];
    final List<AppItem> dock = new ArrayList<>(), grid = new ArrayList<>();
    final List<DD> dd = new ArrayList<>();
    int zone = 1, topIdx = 0, dockIdx = 0, gridRow = 0, gridCol = 0, ddIdx = 0;
    boolean dropdownOpen = false, pausedSinceResume = true, variableFont = false;
    float ddRowH, ddPw;
    Typeface font;
    int lastGlow = 0;
    final Handler handler = new Handler(Looper.getMainLooper());
    ConnectivityManager.NetworkCallback netCb;
    BroadcastReceiver timeRx, pkgRx;
    final Runnable recreateRun = () -> recreate();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = Math.max(dm.widthPixels, dm.heightPixels);
        H = Math.min(dm.widthPixels, dm.heightPixels);
        u = H / 540f;
        try {
            font = Typeface.createFromAsset(getAssets(), "Outfit.ttf");
            variableFont = true;
        } catch (Throwable e) {
            font = Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
        loadApps();
        buildUi();
        restoreFocus();
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        pkgRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                handler.removeCallbacks(recreateRun);
                handler.postDelayed(recreateRun, 1500);
            }
        };
        registerReceiver(pkgRx, pf);
    }

    @Override
    protected void onStart() {
        super.onStart();
        timeRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { updateClock(); }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeRx, f);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { runOnUiThread(() -> updateNetwork()); }
                @Override public void onLost(Network n) { runOnUiThread(() -> updateNetwork()); }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Throwable e) { netCb = null; }
        updateClock();
        updateNetwork();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pausedSinceResume = false;
        updateClock();
        updateNetwork();
        refresh(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedSinceResume = true;
        if (dropdownOpen) closeDropdown();
        saveFocus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(timeRx); } catch (Throwable e) { }
        try {
            if (netCb != null) {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb);
            }
        } catch (Throwable e) { }
        netCb = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(recreateRun);
        try { unregisterReceiver(pkgRx); } catch (Throwable e) { }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        // Home pressed while already on the launcher: go back to the dock.
        if (!pausedSinceResume && root != null) {
            if (dropdownOpen) closeDropdown();
            zone = 1;
            refresh(true);
        }
    }

    @Override
    public void onBackPressed() { /* launcher: ignore */ }

    // ---------------------------------------------------------------- data

    void loadApps() {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, AppItem> found = new LinkedHashMap<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String p = ri.activityInfo.packageName;
            if (found.containsKey(p)) continue;
            AppItem a = new AppItem();
            a.pkg = p;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(p, ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try { a.banner = ri.activityInfo.loadBanner(pm); } catch (Throwable e) { }
            try { a.icon = ri.loadIcon(pm); } catch (Throwable e) { }
            found.put(p, a);
        }
        for (String p : DOCK_PACKAGES) {
            AppItem a = found.get(p);
            if (a == null) {
                Intent li = pm.getLaunchIntentForPackage(p);
                if (li == null) continue;
                a = new AppItem();
                a.pkg = p;
                try { a.label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))); }
                catch (Throwable e) { a.label = p; }
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.launch = li;
                try { a.icon = pm.getApplicationIcon(p); } catch (Throwable e) { }
            }
            Drawable d = a.banner != null ? a.banner : a.icon;
            if (d != null) a.glow = sampleColor(d);
            dock.add(a);
        }
        for (AppItem a : found.values()) {
            if (HIDDEN.contains(a.pkg) || Arrays.asList(DOCK_PACKAGES).contains(a.pkg)) continue;
            grid.add(a);
        }
        Collections.sort(grid, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    int sampleColor(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 16, 9);
            d.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
                }
            }
            b.recycle();
            if (n == 0) return 0xFF4A6FA5;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (bl / n), hsv);
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.85f);
            return Color.HSVToColor(hsv);
        } catch (Throwable e) {
            return 0xFF4A6FA5;
        }
    }

    // ---------------------------------------------------------------- UI build

    TextView text(String s, float px, int weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setTypeface(font);
        if (variableFont) {
            try { t.setFontVariationSettings("'wght' " + weight); } catch (Throwable e) { }
        }
        return t;
    }

    void put(View v, float x, float y) {
        v.setTranslationX(x);
        v.setTranslationY(y);
        content.addView(v, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    }

    void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        int rows = (grid.size() + COLS - 1) / COLS;
        int contentH = (int) (H + 24 * u + rows * 150 * u + 140 * u);
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(W, contentH));

        tileW = 160 * u; tileH = 90 * u; gapX = 20 * u;
        tx0 = (W - (COLS * tileW + (COLS - 1) * gapX)) / 2f;
        bandTop = H - 158 * u;

        // ambient glow (behind everything)
        glow = new GlowView(this);
        int gw = (int) (820 * u), gh = (int) (520 * u);
        glow.setTranslationX((W - gw) / 2f);
        glow.setTranslationY(bandTop - 20 * u - gh / 2f);
        content.addView(glow, new FrameLayout.LayoutParams(gw, gh));

        // dock band
        View band = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x2AFFFFFF);
        bg.setCornerRadius(30 * u);
        band.setBackground(bg);
        band.setTranslationX(tx0 - 20 * u);
        band.setTranslationY(bandTop);
        content.addView(band, new FrameLayout.LayoutParams(
                (int) (COLS * tileW + (COLS - 1) * gapX + 40 * u), (int) (tileH + 40 * u)));

        for (int i = 0; i < dock.size(); i++) {
            AppItem a = dock.get(i);
            a.x = tx0 + i * (tileW + gapX);
            a.y = bandTop + 20 * u;
            a.w = tileW; a.h = tileH;
            addTile(a, false);
        }
        for (int i = 0; i < grid.size(); i++) {
            AppItem a = grid.get(i);
            a.x = tx0 + (i % COLS) * (tileW + gapX);
            a.y = H + 24 * u + (i / COLS) * 150 * u;
            a.w = tileW; a.h = tileH;
            addTile(a, true);
        }

        greeting = text("", 20 * u, 300, 0xCCFFFFFF);
        put(greeting, 48 * u, 30 * u);
        clock = text("", 84 * u, 200, 0xFFFFFFFF);
        put(clock, 46 * u, 52 * u);
        dateView = text("", 18 * u, 300, 0x99FFFFFF);
        put(dateView, 50 * u, 152 * u);

        dockName = text("", 38 * u, 300, 0xFFFFFFFF);
        put(dockName, tx0, bandTop - 76 * u);

        String[] labels = {"Inputs", "Wi-Fi", "Settings"};
        for (int i = 0; i < 3; i++) {
            TextView p = text(labels[i], 17 * u, 400, 0xFFFFFFFF);
            p.setGravity(Gravity.CENTER);
            p.setPadding((int) (18 * u), 0, (int) (18 * u), 0);
            p.setMaxWidth((int) (280 * u));
            p.setEllipsize(TextUtils.TruncateAt.END);
            GradientDrawable pg = new GradientDrawable();
            pg.setColor(0x33FFFFFF);
            pg.setCornerRadius(PILL_H * u / 2f);
            p.setBackground(pg);
            pills[i] = p;
            put(p, 0, PILL_Y * u);
        }
        layoutPills();

        glass = new GlassView(this);
        content.addView(glass, new FrameLayout.LayoutParams(W, contentH));
        setContentView(root);
    }

    void addTile(AppItem a, boolean withLabel) {
        AppTile t = new AppTile(this, a);
        a.tile = t;
        t.setTranslationX(a.x);
        t.setTranslationY(a.y);
        content.addView(t, new FrameLayout.LayoutParams((int) a.w, (int) a.h));
        if (withLabel) {
            TextView n = text(a.label, 15 * u, 400, 0xFFFFFFFF);
            n.setGravity(Gravity.CENTER_HORIZONTAL);
            n.setEllipsize(TextUtils.TruncateAt.END);
            n.setAlpha(0f);
            n.setTranslationX(a.x - 10 * u);
            n.setTranslationY(a.y + a.h + 14 * u);
            content.addView(n, new FrameLayout.LayoutParams((int) (a.w + 20 * u), FrameLayout.LayoutParams.WRAP_CONTENT));
            a.name = n;
        }
    }

    void layoutPills() {
        float right = W - 48 * u, gap = 12 * u, h = PILL_H * u;
        for (int i = 2; i >= 0; i--) {
            pills[i].measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            float w = pills[i].getMeasuredWidth();
            pillW[i] = w;
            pillX[i] = right - w;
            right = pillX[i] - gap;
            pills[i].setLayoutParams(new FrameLayout.LayoutParams((int) w, (int) h));
            pills[i].setTranslationX(pillX[i]);
            pills[i].setTranslationY(PILL_Y * u);
        }
    }

    // ---------------------------------------------------------------- status

    void updateClock() {
        if (clock == null) return;
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        dateView.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening");
    }

    void updateNetwork() {
        if (pills[1] == null) return;
        String t = "Offline";
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    t = "Ethernet";
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    t = "Wi-Fi";
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    WifiInfo wi = wm.getConnectionInfo();
                    if (wi != null) {
                        String s = wi.getSSID();
                        if (s != null && !s.contains("unknown ssid") && s.length() > 0) t = s.replace("\"", "");
                    }
                } else {
                    t = "Connected";
                }
            }
        } catch (Throwable e) { }
        pills[1].setText(t);
        layoutPills();
        applyGlass(false);
    }

    // ---------------------------------------------------------------- focus

    AppItem gridAt(int row, int col) {
        int i = row * COLS + col;
        return i >= 0 && i < grid.size() ? grid.get(i) : null;
    }

    int rowLen(int row) { return Math.max(0, Math.min(COLS, grid.size() - row * COLS)); }

    int rowCount() { return (grid.size() + COLS - 1) / COLS; }

    void clampFocus() {
        if (dock.isEmpty() && zone == 1) zone = grid.isEmpty() ? 0 : 2;
        if (grid.isEmpty() && zone == 2) zone = dock.isEmpty() ? 0 : 1;
        dockIdx = Math.max(0, Math.min(dockIdx, Math.max(0, dock.size() - 1)));
        gridRow = Math.max(0, Math.min(gridRow, Math.max(0, rowCount() - 1)));
        gridCol = Math.max(0, Math.min(gridCol, Math.max(0, rowLen(gridRow) - 1)));
        topIdx = Math.max(0, Math.min(topIdx, 2));
    }

    void scaleTile(AppItem a, float s) {
        a.tile.animate().scaleX(s).scaleY(s).setDuration(180).start();
    }

    void refresh(boolean anim) {
        if (root == null) return;
        clampFocus();
        AppItem fd = (zone == 1 && !dock.isEmpty()) ? dock.get(dockIdx) : null;
        AppItem fg = zone == 2 ? gridAt(gridRow, gridCol) : null;
        for (AppItem a : dock) scaleTile(a, a == fd ? 1.12f : 1f);
        for (AppItem a : grid) {
            boolean f = a == fg;
            scaleTile(a, f ? 1.08f : 1f);
            a.name.animate().alpha(f ? 1f : 0f).setDuration(180).start();
        }
        if (!dock.isEmpty()) {
            dockName.setText(dock.get(dockIdx).label);
            int g = dock.get(dockIdx).glow;
            if (g != lastGlow) { lastGlow = g; glow.setGlowColor(g); }
        }
        dockName.animate().alpha(zone == 1 ? 1f : 0f).setDuration(200).start();
        glow.animate().alpha(zone == 2 ? 0f : 1f).setDuration(300).start();
        float s = 0;
        if (zone == 2) s = H - 100 * u + Math.max(0, gridRow - 2) * 150 * u;
        content.animate().translationY(-s).setDuration(340).setInterpolator(new DecelerateInterpolator()).start();
        applyGlass(anim);
    }

    RectF focusRect() {
        RectF r = new RectF();
        if (dropdownOpen && ddPanel != null) {
            float x = ddPanel.getTranslationX(), y = ddPanel.getTranslationY();
            r.set(x + 6 * u, y + 6 * u + ddIdx * ddRowH, x + ddPw - 6 * u, y + 6 * u + (ddIdx + 1) * ddRowH);
            rad = 12 * u;
        } else if (zone == 0) {
            float x = pillX[topIdx], w = pillW[topIdx], y = PILL_Y * u, h = PILL_H * u;
            r.set(x - 3 * u, y - 3 * u, x + w + 3 * u, y + h + 3 * u);
            rad = h / 2f + 3 * u;
        } else {
            AppItem a = zone == 1 ? (dock.isEmpty() ? null : dock.get(dockIdx)) : gridAt(gridRow, gridCol);
            if (a == null) return null;
            float s = zone == 1 ? 1.12f : 1.08f, pad = zone == 1 ? 6 * u : 5 * u;
            float cx = a.x + a.w / 2f, cy = a.y + a.h / 2f;
            float hw = a.w * s / 2f + pad, hh = a.h * s / 2f + pad;
            r.set(cx - hw, cy - hh, cx + hw, cy + hh);
            rad = 22 * u;
        }
        return r;
    }

    void applyGlass(boolean anim) {
        if (glass == null) return;
        RectF r = focusRect();
        if (r != null) glass.moveTo(r.left, r.top, r.right, r.bottom, rad, anim);
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean onKeyDown(int kc, KeyEvent e) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
                handleKey(kc, e.getRepeatCount());
                return true;
            default:
                return super.onKeyDown(kc, e);
        }
    }

    boolean isOk(int kc) {
        return kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER
                || kc == KeyEvent.KEYCODE_NUMPAD_ENTER || kc == KeyEvent.KEYCODE_BUTTON_A;
    }

    void handleKey(int kc, int rep) {
        if (dropdownOpen) {
            if (kc == KeyEvent.KEYCODE_DPAD_UP) ddIdx = Math.max(0, ddIdx - 1);
            else if (kc == KeyEvent.KEYCODE_DPAD_DOWN) ddIdx = Math.min(dd.size() - 1, ddIdx + 1);
            else if (isOk(kc)) {
                if (rep == 0) { DD it = dd.get(ddIdx); closeDropdown(); it.run.run(); }
                return;
            } else if (kc == KeyEvent.KEYCODE_BACK) { closeDropdown(); return; }
            applyGlass(true);
            return;
        }
        if (isOk(kc)) { if (rep == 0) activate(); return; }
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (zone == 0) topIdx = Math.max(0, topIdx - 1);
                else if (zone == 1) dockIdx = Math.max(0, dockIdx - 1);
                else gridCol = Math.max(0, gridCol - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (zone == 0) topIdx = Math.min(2, topIdx + 1);
                else if (zone == 1) dockIdx = Math.min(dock.size() - 1, dockIdx + 1);
                else gridCol = Math.min(rowLen(gridRow) - 1, gridCol + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (zone == 2) {
                    if (gridRow > 0) { gridRow--; }
                    else if (!dock.isEmpty()) { zone = 1; dockIdx = Math.min(gridCol, dock.size() - 1); }
                    else zone = 0;
                } else if (zone == 1) zone = 0;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (zone == 0) { if (!dock.isEmpty()) zone = 1; else if (!grid.isEmpty()) { zone = 2; gridRow = 0; gridCol = 0; } }
                else if (zone == 1) {
                    if (!grid.isEmpty()) { zone = 2; gridRow = 0; gridCol = Math.min(dockIdx, rowLen(0) - 1); }
                } else if (gridRow < rowCount() - 1) {
                    gridRow++;
                    gridCol = Math.min(gridCol, rowLen(gridRow) - 1);
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (zone == 2 || zone == 0) { if (!dock.isEmpty()) zone = 1; }
                break;
        }
        refresh(true);
    }

    void activate() {
        if (zone == 0) {
            if (topIdx == 0) openDropdown();
            else if (topIdx == 1) startQuiet(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else startQuiet(new Intent(Settings.ACTION_SETTINGS));
        } else if (zone == 1 && !dock.isEmpty()) {
            launch(dock.get(dockIdx));
        } else if (zone == 2) {
            AppItem a = gridAt(gridRow, gridCol);
            if (a != null) launch(a);
        }
    }

    void launch(AppItem a) {
        try { startActivity(a.launch); }
        catch (Throwable e) { Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show(); }
    }

    void startQuiet(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (Throwable e) { Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show(); }
    }

    // ---------------------------------------------------------------- inputs dropdown

    void openDropdown() {
        dd.clear();
        dd.add(new DD("Live TV", () -> {
            Intent li = getPackageManager().getLaunchIntentForPackage(LIVE_TV);
            if (li != null) startQuiet(li);
        }));
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            for (TvInputInfo info : tm.getTvInputList()) {
                int type = info.getType();
                if (type == TvInputInfo.TYPE_TUNER || type == TvInputInfo.TYPE_OTHER) continue;
                if (!info.isPassthroughInput() || info.getParentId() != null) continue;
                CharSequence l = info.loadCustomLabel(this);
                if (l == null || l.length() == 0) l = info.loadLabel(this);
                final String id = info.getId();
                dd.add(new DD(String.valueOf(l), () -> {
                    startQuiet(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)));
                }));
            }
        } catch (Throwable e) { }
        ddRowH = 44 * u;
        ddPw = 250 * u;
        ddPanel = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xEE1E1E20);
        g.setCornerRadius(18 * u);
        g.setStroke((int) Math.max(1, u), 0x40FFFFFF);
        ddPanel.setBackground(g);
        for (int i = 0; i < dd.size(); i++) {
            TextView t = text(dd.get(i).label, 17 * u, 400, 0xFFFFFFFF);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding((int) (18 * u), 0, 0, 0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) ddPw, (int) ddRowH);
            lp.topMargin = (int) (6 * u + i * ddRowH);
            ddPanel.addView(t, lp);
        }
        float px = Math.min(pillX[0], W - 48 * u - ddPw);
        ddPanel.setTranslationX(px);
        ddPanel.setTranslationY((PILL_Y + PILL_H + 10) * u);
        content.addView(ddPanel, new FrameLayout.LayoutParams((int) ddPw, (int) (dd.size() * ddRowH + 12 * u)));
        dropdownOpen = true;
        ddIdx = 0;
        glass.bringToFront();
        applyGlass(true);
    }

    void closeDropdown() {
        if (ddPanel != null) content.removeView(ddPanel);
        ddPanel = null;
        dropdownOpen = false;
        applyGlass(true);
    }

    // ---------------------------------------------------------------- remember focus

    void saveFocus() {
        getSharedPreferences("focus", MODE_PRIVATE).edit()
                .putInt("zone", zone).putInt("dock", dockIdx)
                .putInt("row", gridRow).putInt("col", gridCol).putInt("top", topIdx).apply();
    }

    void restoreFocus() {
        SharedPreferences p = getSharedPreferences("focus", MODE_PRIVATE);
        zone = p.getInt("zone", 1);
        dockIdx = p.getInt("dock", 0);
        gridRow = p.getInt("row", 0);
        gridCol = p.getInt("col", 0);
        topIdx = p.getInt("top", 0);
        clampFocus();
        refresh(false);
    }

    // ---------------------------------------------------------------- custom views

    class AppTile extends View {
        final AppItem a;
        final Path clip = new Path();
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);

        AppTile(Context c, AppItem a) {
            super(c);
            this.a = a;
            bg.setColor(0xFF1C1C1E);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float r = 14 * u;
            clip.reset();
            clip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawRect(0, 0, w, h, bg);
            if (a.banner != null) {
                a.banner.setBounds(0, 0, w, h);
                a.banner.draw(c);
            } else if (a.icon != null) {
                int s = (int) (56 * u), l = (w - s) / 2, t = (h - s) / 2;
                a.icon.setBounds(l, t, l + s, t + s);
                a.icon.draw(c);
            }
            c.restore();
        }
    }

    class GlassView extends View {
        final RectF cur = new RectF(), from = new RectF(), to = new RectF();
        float radius = 18 * u;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        ValueAnimator anim;
        boolean shown = false;

        GlassView(Context c) {
            super(c);
            fill.setColor(0x38FFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * u);
            stroke.setColor(0x80FFFFFF);
        }

        void moveTo(float l, float tp, float r, float b, float rd, boolean animate) {
            to.set(l, tp, r, b);
            if (anim != null) anim.cancel();
            if (!animate || !shown) {
                cur.set(to);
                radius = rd;
                shown = true;
                invalidate();
                return;
            }
            from.set(cur);
            final float r0 = radius;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(320);
            anim.setInterpolator(new OvershootInterpolator(1.1f));
            anim.addUpdateListener(va -> {
                float k = (float) va.getAnimatedValue();
                cur.set(from.left + (to.left - from.left) * k, from.top + (to.top - from.top) * k,
                        from.right + (to.right - from.right) * k, from.bottom + (to.bottom - from.bottom) * k);
                radius = Math.max(0, r0 + (rd - r0) * k);
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!shown) return;
            c.drawRoundRect(cur, radius, radius, fill);
            c.drawRoundRect(cur, radius, radius, stroke);
        }
    }

    class GlowView extends View {
        int color = 0;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ValueAnimator anim;

        GlowView(Context c) { super(c); }

        void setGlowColor(int target) {
            if (anim != null) anim.cancel();
            if (color == 0) { color = target; rebuild(); invalidate(); return; }
            anim = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
            anim.setDuration(350);
            anim.addUpdateListener(va -> { color = (int) va.getAnimatedValue(); rebuild(); invalidate(); });
            anim.start();
        }

        void rebuild() {
            if (getWidth() == 0) return;
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(getWidth(), getHeight()) / 2f;
            int c0 = Color.argb(120, Color.red(color), Color.green(color), Color.blue(color));
            int c1 = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color));
            p.setShader(new RadialGradient(cx, cy, r, c0, c1, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) { rebuild(); }

        @Override
        protected void onDraw(Canvas c) {
            if (color == 0) return;
            if (p.getShader() == null) rebuild();
            c.drawRect(0, 0, getWidth(), getHeight(), p);
        }
    }
}
