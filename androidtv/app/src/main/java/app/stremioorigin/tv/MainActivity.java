// The formatting is rough and I'm not going to bother. Pull requests that fix it are welcome; complaints without one help neither me nor the end user.
package app.stremioorigin.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.AssetManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.view.Display;
import android.view.KeyEvent;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/* Android TV: SDL's activity running the TV app (libmain.so, the same screens as the LG app). Java's part is small:
   the bundled files copied out once per version, the remote's keys handed to the app before Android acts on them,
   the device's answers (its screen, what it decodes), and the video plane under the app's drawing (VideoPlane). */
public class MainActivity extends SDLActivity {
    static MainActivity self;
    private static WifiManager.MulticastLock mcLock;

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL2", "curl", "main"};   /* curl is opened by name from the app: loaded first, it is found on every version */
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        self = this;
        crashWatch(this);
        /* development: the app's test settings as the TV build takes them from its environment
           (adb shell am start -n app.stremioorigin.tv/.MainActivity --es env "SB_MEMMB=512;SB_SKIP_LOGIN=1") */
        /* only in a debug build or a test build made with SB_DEVHOOKS=1: a published APK takes no settings from whoever
           opens it (the update source and the log address among them) */
        boolean hooks = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 || getResources().getBoolean(R.bool.dev_hooks);
        String env = hooks && getIntent() != null ? getIntent().getStringExtra("env") : null;
        if (env != null) for (String kv : env.split(";")) { int i = kv.indexOf('='); if (i > 0) try { android.system.Os.setenv(kv.substring(0, i).trim(), kv.substring(i + 1), true); } catch (Throwable e) { } }
        copyAssets();
        writeVersion();
        linkFonts();
        /* a link this task was started with, given again as Android opens the app anew (closed in the back, then opened
           from its icon or the recent apps): not acted on twice */
        android.content.Intent start = getIntent();
        if (savedInstanceState == null && (start == null || (start.getFlags() & android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0)) takeLink(start);
        super.onCreate(savedInstanceState);
        installStatus(getIntent());
        usbWatch();
        /* the app's drawing over the video plane: clear where the picture shows */
        if (mSurface != null) { mSurface.setZOrderMediaOverlay(true); mSurface.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT); }
        /* the drawing surface's size, fixed before SDL makes its surface (set later, the app had already drawn at the old
           size): 1280x720 on a box found weak (the app's own measure of this GPU, kept in data/drawsize, or under 1.5 GB of
           memory), the TV's display hardware scaling it to the screen for nothing; else 1920x1080 (a 4K screen scales that) */
        { int lines = drawLines(); if (mSurface != null) { mSurface.getHolder().setFixedSize(lines * 16 / 9, lines);
            /* (SDL adds its view sized to its content: with a fixed buffer smaller than the screen the view itself shrank to it,
               in the top-left corner. Filling the screen, the compositor scales the buffer to the whole view) */
            mSurface.setLayoutParams(new android.widget.RelativeLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)); } log("[start] drawing surface " + (lines * 16 / 9) + "x" + lines + " (Java)"); }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        /* a video app, never a game: the TV keeps its picture mode (a window asking for minimal post-processing makes Android
           send the TV ALLM / Game Content Type over HDMI, and the TV switches to Game mode; asked off here, and the manifest
           says appCategory video for TVs that decide by the app's category) */
        if (Build.VERSION.SDK_INT >= 30) { try { getWindow().setPreferMinimalPostProcessing(false); } catch (Throwable t) { } }
        try {   /* the phone finds the TV app by a broadcast on the network: Wi-Fi drops them without this */
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { mcLock = wm.createMulticastLock("origin-cast"); mcLock.setReferenceCounted(false); mcLock.acquire(); }
        } catch (Throwable e) { }
    }

    @Override
    protected void onPause() { inFront = false; VideoPlane.onPause(); super.onPause(); }
    @Override protected void onStop() { VideoPlane.onStop(); super.onStop(); }
    @Override protected void onStart() { super.onStart(); VideoPlane.onStart(); }
    static volatile boolean inFront;
    /* back in front after the system's install screen with no answer from it (cancelled, or Android 5's "Install blocked"
       closed: no status comes then): the update is not stuck as installing, it can be tried again */
    @Override
    protected void onResume() { super.onResume(); inFront = true; installLeaveBack(); long at = installAskedAt; if (at > 0 && System.currentTimeMillis() - at > 1000) { installAskedAt = 0; updFail("The update was not installed"); } }
    private static volatile long installAskedAt;

    /* ---- what the app takes once a frame (src/platform/androidtv: plat_luna_pump): 0 the update's answers, 1 a link the
       app was opened with, 2 a memory warning, 3 the launch's parameters as LG gives them ({"cast":1}) ---- */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> updateQ = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile String link, params; private static volatile int trim;
    public static String take(int what) {
        if (what == 0) { String s = updateQ.poll(); return s == null ? "" : s; }
        if (what == 1) { String s = link; link = null; return s == null ? "" : s; }
        if (what == 3) { String s = params; params = null; return s == null ? "" : s; }
        if (what == 4) { String s = typedQ.poll(); return s == null ? "" : s; }
        int t = trim; trim = 0; return t == 0 ? "" : String.valueOf(t);
    }
    /* the TV's home-screen rows (src/platform/androidtv/homerows_atv.c sends the list when it changes): HomeRows */
    /* the rate the display composes at now (its mode: the video's own while one plays with its rate matched), 0 unknown */
    public static float displayHz() { MainActivity a = self; if (a == null) return 0; try { return a.getWindowManager().getDefaultDisplay().getRefreshRate(); } catch (Throwable t) { return 0; } }
    public static void homeRows(String json) { MainActivity a = self; if (a != null && json != null) HomeRows.update(a, json); }
    /* a card from the TV's home screen (CardActivity): played by the app (homerows_atv.c) */
    static void cardLink(String u) { if (u != null && u.startsWith("stremioorigin://play/")) link = "stremio:" + u.substring(14); }
    /* a stremio:///detail/... link (from another app or the system): the title's page, as an addon's link opens it */
    private static void takeLink(android.content.Intent i) {
        if (i == null || !android.content.Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        String u = i.getData().toString();
        if (u.startsWith("stremioorigin://open") || u.equals("stremioorigin:open")) {   /* the phone's "open the app": coming forward, and cast=1 when its play follows */
            if ("1".equals(i.getData().getQueryParameter("cast"))) params = "{\"cast\":1}";
            return; }
        if (u.startsWith("stremioorigin:")) u = "stremio:" + u.substring(14);
        link = u;
    }
    @Override
    protected void onNewIntent(android.content.Intent i) { super.onNewIntent(i); if (!installStatus(i)) takeLink(i); }
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) trim = level;   /* running low (10), critical (15), or the app in the back (20 and up) */
    }

    /* ---- the in-app update (core/update.c asks through the platform): the package downloaded, its SHA-256 checked, then
       the system's package installer (it asks the user to confirm); answers as {"statusText","progress"},
       {"returnValue":false,"errorText"}, {"finished":true} ---- */
    private static volatile boolean updCancel;
    private static String q(String s) { return org.json.JSONObject.quote(s); }
    private static void updSay(String status, int pct) { updateQ.add("{\"statusText\":" + q(status) + (pct >= 0 ? ",\"progress\":" + pct : "") + "}"); }
    private static void updFail(String why) { updateQ.add("{\"returnValue\":false,\"errorText\":" + q(why) + "}"); log("[update] " + why); }
    public static void updateCancel(int unused) { updCancel = true; }
    /* the update asked for while the system's leave to install apps was still to be given: started once it is (onResume) */
    private static volatile String askUrl, askSha;
    private static volatile boolean updRunning;
    public static void updateStart(final String url, final String sha) {
        final MainActivity a = self; if (a == null) { updFail("The app is closing"); return; }
        updCancel = false;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            askUrl = url; askSha = sha;
            updateQ.add("{\"returnValue\":false,\"permission\":true,\"errorText\":" + q("Allow Stremio Origin in the list that opened, then come back: the update goes on by itself") + "}");
            log("[update] the leave to install apps is not given: its list opened");
            a.runOnUiThread(() -> openInstallList(a));
            return;
        }
        download(a, url, sha);
    }
    /* Android TV's page for one app ("package:" link) shows "No apps can perform this action" (measured on Android TV 12):
       the list of all apps is opened instead, where this one is, "Not allowed" until switched */
    private static void openInstallList(MainActivity a) {
        String act = android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES;
        android.content.Intent[] tries = { new android.content.Intent(act), new android.content.Intent(act, Uri.parse("package:" + a.getPackageName())), new android.content.Intent(android.provider.Settings.ACTION_SETTINGS) };
        for (android.content.Intent i : tries) { try { a.startActivity(i); return; } catch (Throwable e) { } }
        log("[update] no settings page to allow installs opened");
    }
    /* back from the list: the leave given, the update that waited goes on */
    private void installLeaveBack() {
        String u = askUrl, h = askSha; if (u == null || updRunning) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) return;
        askUrl = null; askSha = null; log("[update] the leave to install apps given: the download starts"); download(this, u, h);
    }
    private static void download(final MainActivity a, final String url, final String sha) {
        if (updRunning) return; updRunning = true;
        new Thread(() -> {
            File apk = new File(a.getCacheDir(), "update.apk");
            try {
                updSay("Downloading…", 0);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setInstanceFollowRedirects(true); c.setConnectTimeout(20000); c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "StremioOrigin (Android TV)");
                if (c.getResponseCode() != 200) { updFail("The download failed (" + c.getResponseCode() + ")"); return; }
                long total = c.getContentLength(), got = 0; int lastPct = -1;
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) { if (updCancel) { updFail("Cancelled"); return; } out.write(b, 0, n); md.update(b, 0, n); got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1; if (pct != lastPct && pct % 5 == 0) { lastPct = pct; updSay("Downloading…", pct); } }
                }
                StringBuilder hex = new StringBuilder(); for (byte x : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", x));
                if (!hex.toString().equalsIgnoreCase(sha)) { apk.delete(); updFail("The download does not match its checksum"); return; }
                updSay("Installing…", -1);
                install(a, apk);
            } catch (Throwable e) { updFail("The update failed: " + e.getMessage()); }
            finally { updRunning = false; }
        }, "update").start();
    }
    /* Android 13 and up ask every receiver whether other apps may reach it */
    static void reg(Context c, android.content.BroadcastReceiver r, android.content.IntentFilter f, boolean fromSystem) {
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, fromSystem ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED); else c.registerReceiver(r, f);
    }
    /* The install, made so the app is open again afterwards (an update ends every process of the app):
       - Android 9 and before, or where the user allowed "Display over other apps": a package session; UpdatedReceiver
         opens the app from the "my package was replaced" broadcast (kept to updates this app started: update.reopen);
       - Android 10 and up otherwise (an app in the back may not open a screen; the installer's answer is refused too,
         measured on Android 12): the system's install screen, which ends on Open. */
    static final String INSTALL_STATUS = "app.stremioorigin.tv.INSTALL_STATUS";
    private static void install(final MainActivity a, File apk) throws IOException {
        if (Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(a)) {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_INSTALL_PACKAGE)
                .setData(Uri.parse("content://" + ApkProvider.AUTH + "/update.apk"))
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(android.content.Intent.EXTRA_INSTALLER_PACKAGE_NAME, a.getPackageName());
            a.runOnUiThread(() -> { try { a.startActivity(i); installAskedAt = System.currentTimeMillis(); updSay("Confirm the update on the screen", -1); } catch (Throwable e) { updFail("The installer did not open"); } });
            return;
        }
        android.content.pm.PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        android.content.pm.PackageInstaller.SessionParams sp = new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(a.getPackageName());
        int id = pi.createSession(sp);
        try (android.content.pm.PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); java.io.OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); s.fsync(out);
            }
            try (FileOutputStream m = new FileOutputStream(new File(a.getFilesDir(), "update.reopen"))) { m.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8")); }
            android.content.Intent back = new android.content.Intent(a, MainActivity.class).setAction(INSTALL_STATUS).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int fl = (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0) | android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            Bundle opts = null;
            if (Build.VERSION.SDK_INT >= 34) opts = android.app.ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
            android.app.PendingIntent done = android.app.PendingIntent.getActivity(a, id, back, fl, opts);
            s.commit(done.getIntentSender());
        }
    }
    /* the installer's answer (see install): the confirmation to show, the end, or why it failed */
    private boolean installStatus(android.content.Intent i) {
        if (i == null || !INSTALL_STATUS.equals(i.getAction())) return false;
        int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
        if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {   /* the system's confirmation */
            android.content.Intent confirm = (android.content.Intent) i.getParcelableExtra(android.content.Intent.EXTRA_INTENT);
            if (confirm != null) try { startActivity(confirm); installAskedAt = System.currentTimeMillis(); } catch (Throwable e) { updFail("The installer did not open"); }
            updSay("Confirm the update on the screen", -1);
        } else if (st == android.content.pm.PackageInstaller.STATUS_SUCCESS) { installAskedAt = 0;
            new File(getFilesDir(), "update.reopen").delete(); updateQ.add("{\"finished\":true}");
            MainActivity.log("[update] installed: opened again by the installer's answer");
        } else {
            new File(getFilesDir(), "update.reopen").delete(); installAskedAt = 0;
            String m = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE); updFail(m != null ? m : "The install was cancelled");
        }
        return true;
    }

    /* ---- a removable drive for the torrent cache: <files>/usb points at the app's own folder on it while it is mounted
       (Android gives an app its folder on every volume without a permission); nothing when there is none ---- */
    private void usbWatch() {
        usbRefresh();
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(android.content.Intent.ACTION_MEDIA_MOUNTED); f.addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(android.content.Intent.ACTION_MEDIA_REMOVED); f.addAction(android.content.Intent.ACTION_MEDIA_EJECT); f.addDataScheme("file");
        try { reg(this, new android.content.BroadcastReceiver() { @Override public void onReceive(Context c, android.content.Intent i) { usbRefresh(); } }, f, true); } catch (Throwable e) { }
    }
    private void usbRefresh() {
        File link = new File(getFilesDir(), "usb"), target = null;
        try {
            File[] dirs = getExternalFilesDirs(null);
            for (int i = 1; dirs != null && i < dirs.length; i++) {   /* [0] is the box's own storage */
                File d = dirs[i]; if (d == null) continue;
                if (!android.os.Environment.MEDIA_MOUNTED.equals(android.os.Environment.getExternalStorageState(d))) continue;
                if (!android.os.Environment.isExternalStorageRemovable(d)) continue;
                File t = new File(d, "torrents"); t.mkdirs(); if (t.isDirectory() && t.canWrite()) { target = t; break; }
            }
        } catch (Throwable e) { }
        try { link.delete(); if (target != null) android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath()); } catch (Throwable e) { }
        MainActivity.log("[usb] " + (target != null ? "a removable drive for torrents" : "no removable drive"));
    }

    /* the version for the app's About page and its update check (core/update.c reads <files>/appinfo.json) */
    private void writeVersion() {
        try { String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "appinfo.json")); o.write(("{\"version\": \"" + v + "\"}").getBytes("UTF-8")); o.close();
        } catch (Throwable e) { }
    }

    @Override
    protected void onDestroy() {
        VideoPlane.release();
        if (mcLock != null) try { mcLock.release(); } catch (Throwable e) { }
        if (self == this) self = null;
        super.onDestroy();
    }

    /* ---- the remote: the keys the app uses go to it first (Back never closes the app behind its back; the colour and
       channel keys, Menu and the media keys reach it whatever SDL knows of them). Volume, Home and the rest stay Android's. ---- */
    static native void nativeTvKey(int code, boolean down, int repeat);
    private static boolean appKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_PROG_RED: case KeyEvent.KEYCODE_PROG_GREEN: case KeyEvent.KEYCODE_PROG_YELLOW: case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_UP: case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_SEARCH:
                return true;
            default: return false;
        }
    }
    /* letters typed on a real keyboard (an air mouse's, a remote's back, a USB or Bluetooth one) while Android's own keyboard
       is not up: SDL drops them then, so they go to the app here (Search starts typing with the first one) */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String> typedQ = new java.util.concurrent.ConcurrentLinkedQueue<>();
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && e.getDevice() != null && e.getDevice().getKeyboardType() == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            int u = e.getUnicodeChar(); if (typedQ.size() < 64) {   /* (a keyboard's letters, digits, space and backspace: a remote's own digit keys are not an alphabetic keyboard's) */
                if (k == KeyEvent.KEYCODE_DEL) typedQ.add("\b"); else if (u > 0 && (Character.isLetterOrDigit(u) || u == ' ')) typedQ.add(new String(Character.toChars(u))); }
        }
        if (appKey(k) && (e.getAction() == KeyEvent.ACTION_DOWN || e.getAction() == KeyEvent.ACTION_UP)) {
            try { nativeTvKey(k, e.getAction() == KeyEvent.ACTION_DOWN, e.getRepeatCount()); } catch (UnsatisfiedLinkError ex) { }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /* ---- the app's log: Java's lines go where the app's own go (src/platform/androidtv/plat_atv.c: nativeLog), so shared
       logs have them; before the app's library is loaded, the system log only ---- */
    static native void nativeLog(String s);
    static void log(String s) { try { nativeLog(s); } catch (Throwable e) { android.util.Log.i("StremioOrigin", s); } }

    /* a Java crash: its kind and where, straight into the kept log (data/applog.log, as the app writes it: the next
       Share logs shows it under "the last crash"), then Android's own handling. No message: it may hold an address */
    private static boolean crashWatched;
    static void crashWatch(Context c) {
        if (crashWatched) return; crashWatched = true;
        final File f = new File(c.getFilesDir(), "data/applog.log");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                java.text.SimpleDateFormat d = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT); d.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                String ts = d.format(new java.util.Date()); StringBuilder b = new StringBuilder();
                b.append('\n').append(ts).append(" [crash] java ").append(e.getClass().getName()).append(" on the thread ").append(t.getName().replaceAll("[^A-Za-z0-9 _-]", "")).append('\n');
                int n = 0; for (Throwable x = e; x != null && n < 14; x = x.getCause()) {
                    if (x != e) b.append(ts).append(" [crash] caused by ").append(x.getClass().getName()).append('\n');
                    for (StackTraceElement el : x.getStackTrace()) { if (n++ >= 14) break; b.append(ts).append(" [crash] at ").append(el.getClassName()).append('.').append(el.getMethodName()).append(':').append(el.getLineNumber()).append('\n'); } }
                b.append(ts).append(" [crash] end\n"); o.write(b.toString().getBytes("UTF-8"));
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e); else System.exit(2);
        });
    }

    /* how the last session ended, as Android 11 and up keep it: the system ending it for memory, a crash, not answering */
    static String lastExit(Context c) {
        if (Build.VERSION.SDK_INT < 30) return "";
        java.util.List<android.app.ApplicationExitInfo> l = ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getHistoricalProcessExitReasons(c.getPackageName(), 0, 1);
        if (l == null || l.isEmpty()) return "";
        android.app.ApplicationExitInfo x = l.get(0);
        String[] R = { "unknown", "it closed itself", "a signal", "the system, for memory", "a crash", "a crash in native code", "not answering (ANR)", "it failed to start", "a permission change", "using too much", "the user", "the user stopped it", "a part it needs ended", "another reason", "frozen", "a package change", "an update" };
        int r = x.getReason(); String why = r >= 0 && r < R.length ? R[r] : "reason " + r;
        long mins = (System.currentTimeMillis() - x.getTimestamp()) / 60000;
        return why + (x.getImportance() <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ? ", in front" : ", in the back") + ", " + (x.getRss() / 1024) + " MB, " + mins + " min ago";
    }

    /* ---- the device's answers (src/platform/androidtv/atv.h: ATV_MODEL, ATV_RELEASE, ...) ---- */
    public static String info(int what) {
        MainActivity a = self; if (a == null) return "";
        try {
            switch (what) {
                case 0: return Build.MANUFACTURER + " " + Build.MODEL;
                case 1: return Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
                case 2: return Locale.getDefault().toLanguageTag();
                case 3: return a.getFilesDir().getAbsolutePath();
                case 4: return a.getCacheDir().getAbsolutePath();
                case 5: return a.getApplicationInfo().nativeLibraryDir;
                case 6: return caps(a);
                case 7: return String.valueOf(panelLines(a));
                case 8: return "";   /* no MAC: the phone never wakes the box, it only opens the app when both are on */
                case 9: return castExtra(a);
                case 10: return lastExit(a);
            }
        } catch (Throwable e) { MainActivity.log("info " + what + ": " + e); }
        return "";
    }

    /* what the cast reply adds on Android TV (Cast/CONTRACT.md): the package, the system's version, how a phone can open
       the app when the box is on (Google's TV remote service: its link; Fire TV: DIAL) */
    static String castExtra(Context c) {
        boolean remote = installed(c, "com.google.android.tv.remote.service"), fire = c.getPackageManager().hasSystemFeature("amazon.hardware.fire_tv");
        return "\"pkg\":\"" + c.getPackageName() + "\",\"api\":" + Build.VERSION.SDK_INT + ",\"launch\":{\"remote\":" + remote + ",\"dial\":" + (fire ? "\"StremioOrigin\"" : "null")
            + ",\"link\":\"stremioorigin://open\"}";
    }
    static boolean installed(Context c, String pkg) { try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Throwable e) { return false; } }

    /* the screen's lines: the tallest mode the TV offers (a 4K TV runs its menus at 1080p) */
    static int panelLines(Context c) {
        Display d = ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay(); int best = 0;
        if (Build.VERSION.SDK_INT >= 23) { for (Display.Mode m : d.getSupportedModes()) best = Math.max(best, Math.min(m.getPhysicalWidth(), m.getPhysicalHeight())); }
        if (best == 0) { android.graphics.Point p = new android.graphics.Point(); d.getRealSize(p); best = Math.min(p.x, p.y); }
        return best;
    }
    /* what the box plays, for the best-stream rules (the same fields the LG app reads from its TV): the screen's size,
       HDR10 and Dolby Vision on the screen with a decoder for them, AV1 in hardware; DTS and TrueHD always (passed
       through when the output takes them, decoded in the app otherwise) */
    static String caps(Context c) {
        int lines = panelLines(c), hdr = 0, dv = 0, av1 = 0;
        if (Build.VERSION.SDK_INT >= 24) {
            Display d = ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            Display.HdrCapabilities h = d.getHdrCapabilities();
            if (h != null) for (int t : h.getSupportedHdrTypes()) { if (t == Display.HdrCapabilities.HDR_TYPE_HDR10 || t == Display.HdrCapabilities.HDR_TYPE_HLG) hdr = 1; if (t == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) dv = 1; }
        }
        if (dv == 1 && !hasDecoder("video/dolby-vision", false)) dv = 0;
        if (hasDecoder("video/av01", true)) av1 = 1;
        String s = "maxRes=" + (lines >= 2160 ? 2160 : 1080) + ",hdr=" + hdr + ",dv=" + dv + ",av1=" + av1 + ",dts=1,truehd=1,hevc10=" + (hevcMain10() ? 1 : 0);
        MainActivity.log("[caps] " + s + " (" + lines + " lines)");
        return s;
    }
    /* 10-bit HEVC (Main 10): most boxes decode it; old and cheap ones (and the emulator) do not, and the best-stream
       rules then leave such streams aside */
    static boolean hevcMain10() {
        try {
            for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (i.isEncoder()) continue;
                for (String t : i.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase("video/hevc")) continue;
                    for (MediaCodecInfo.CodecProfileLevel pl : i.getCapabilitiesForType(t).profileLevels)
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 || pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) return true;
                }
            }
        } catch (Throwable e) { }
        return false;
    }
    static boolean hasDecoder(String mime, boolean hardwareOnly) {
        try {
            MediaCodecInfo[] all = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo i : all) {
                if (i.isEncoder()) continue;
                boolean hw = Build.VERSION.SDK_INT >= 29 ? i.isHardwareAccelerated() : !(i.getName().startsWith("OMX.google.") || i.getName().startsWith("c2.android."));
                if (hardwareOnly && !hw) continue;
                for (String t : i.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
            }
        } catch (Throwable e) { }
        return false;
    }

    /* a JPEG by Android's own decoder (the box's libjpeg-turbo, NEON), at a half, quarter or eighth of its size when that
       still covers w x h: much less work than the whole picture decoded and then shrunk (src: img.c). Returned as
       {width, height (4 bytes each, little-endian), then the RGBA pixels}, or null (the app's own decoder then) */
    public static byte[] decodeJpeg(byte[] data, int w, int h) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int w0 = o.outWidth, h0 = o.outHeight; if (w0 <= 0 || h0 <= 0) return null;
            int n = 1; while (n < 8 && (w0 + 2 * n - 1) / (2 * n) >= w && (h0 + 2 * n - 1) / (2 * n) >= h) n *= 2;   /* (cover: both sides at least the box) */
            o = new android.graphics.BitmapFactory.Options(); o.inSampleSize = n; o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888; o.inScaled = false;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o); if (b == null) return null;
            int bw = b.getWidth(), bh = b.getHeight();
            if (b.getConfig() != android.graphics.Bitmap.Config.ARGB_8888 || b.getRowBytes() != bw * 4) { b.recycle(); return null; }
            byte[] out = new byte[8 + bw * bh * 4];
            b.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(out, 8, bw * bh * 4)); b.recycle();   /* (ARGB_8888 is R, G, B, A in memory) */
            out[0] = (byte) bw; out[1] = (byte) (bw >> 8); out[2] = (byte) (bw >> 16); out[3] = (byte) (bw >> 24);
            out[4] = (byte) bh; out[5] = (byte) (bh >> 8); out[6] = (byte) (bh >> 16); out[7] = (byte) (bh >> 24);
            return out;
        } catch (Throwable t) { return null; }
    }
    /* the app started again (the GPU state was lost while it was away): opened anew a moment after this process ends */
    public static void restartApp(int unused) {
        final MainActivity a = self; if (a == null) return;
        a.runOnUiThread(() -> {
            /* started while the app is still in front (Android 10+ refuses activity starts from the background, so an alarm
               after the process ended did not reopen it), then this process ends; the new task opens the app afresh */
            try { a.startActivity(android.content.Intent.makeRestartActivityTask(a.getComponentName())); } catch (Throwable t) { }
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
    /* SB_RES (development) first, then what the app measured on this box last time, then the box's memory */
    private int drawLines() {
        try { String r = android.system.Os.getenv("SB_RES"); if (r != null) return r.equals("720") ? 720 : 1080; } catch (Throwable t) { }
        try { File f = new File(getFilesDir(), "data/drawsize"); if (f.exists()) { byte[] b = new byte[64]; int n; try (FileInputStream in = new FileInputStream(f)) { n = in.read(b); }
                String v = n > 0 ? new String(b, 0, n).trim() : ""; if (v.startsWith("720")) return 720; if (v.startsWith("1080")) return 1080; } } catch (Throwable t) { }
        try { ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            if (mi.totalMem > 0 && mi.totalMem < 1500L * 1024 * 1024) return 720; } catch (Throwable t) { }
        return 1080;
    }
    /* the drawing surface's size, fixed (the TV scales it to the screen): 1080 or 720 lines (src: plat_window_size) */
    public static void surfaceSize(int lines) {
        final MainActivity a = self; if (a == null) return;
        final int h = lines, w = lines * 16 / 9;
        a.runOnUiThread(() -> { if (mSurface != null) mSurface.getHolder().setFixedSize(w, h); });
    }
    /* the screen stays on while a film is loaded (the TV's screen saver would take the decoder) */
    static void keepScreen(final boolean on) {
        final MainActivity a = self; if (a == null) return;
        a.runOnUiThread(() -> { if (on) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); });
    }
    static android.view.ViewGroup layout() { return mLayout; }

    /* ---- files ---- */
    /* the app reads its fonts, dictionaries and icons as files: copied out of the APK once per version */
    private void copyAssets() {
        File dst = new File(getFilesDir(), "assets"), stamp = new File(dst, ".version");
        String ver = "";
        try { ver = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime); } catch (Exception e) { }
        try {
            if (stamp.exists() && ver.equals(new String(readAll(new FileInputStream(stamp)), "UTF-8"))) return;
            copyDir(getAssets(), "", dst);
            FileOutputStream o = new FileOutputStream(stamp); o.write(ver.getBytes("UTF-8")); o.close();
        } catch (IOException e) { MainActivity.log("assets: " + e); }
    }
    private static void copyDir(AssetManager am, String path, File dst) throws IOException {
        String[] names = am.list(path);
        if (names == null || names.length == 0) {
            if (path.isEmpty()) return;
            dst.getParentFile().mkdirs();
            InputStream in = am.open(path); FileOutputStream out = new FileOutputStream(dst);
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close(); out.close(); return;
        }
        dst.mkdirs();
        for (String n : names) {
            if (path.isEmpty() && (n.equals("images") || n.equals("webkit") || n.equals("geoid_map"))) continue;   /* the system's own */
            copyDir(am, path.isEmpty() ? n : path + "/" + n, new File(dst, n));
        }
    }
    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n); in.close(); return o.toByteArray();
    }
    /* the system's faces for the scripts the bundled fonts do not cover (CJK, Thai, Hebrew, the Indic scripts, emoji):
       a folder of links into /system/fonts, the regular weights only, that the app reads as the TV's font folder */
    private static final String[] FONT_KEYS = { "CJK", "Thai", "Hebrew", "Devanagari", "Bengali", "Tamil", "Telugu", "Kannada", "Malayalam",
        "Gujarati", "Gurmukhi", "Sinhala", "Khmer", "Lao", "Myanmar", "Ethiopic", "Armenian", "Georgian", "Emoji", "Symbols", "DroidSansFallback" };
    private void linkFonts() {
        File dir = new File(getFilesDir(), "fonts"), sys = new File("/system/fonts");
        String[] names = sys.list(); if (names == null) return;
        dir.mkdirs(); String[] old = dir.list(); if (old != null) for (String o : old) new File(dir, o).delete();
        java.util.Arrays.sort(names); int n = 0;
        for (String name : names) {
            String low = name.toLowerCase(Locale.ROOT);
            if (!(low.endsWith(".ttf") || low.endsWith(".otf") || low.endsWith(".ttc"))) continue;
            if (low.contains("bold") || low.contains("italic") || low.contains("light") || low.contains("medium") || low.contains("black") || low.contains("thin") || low.contains("serif") || low.contains("mono") || low.contains("ui-")) continue;
            boolean want = false; for (String k : FONT_KEYS) if (name.contains(k)) { want = true; break; }
            if (!want) continue;
            try { android.system.Os.symlink(new File(sys, name).getAbsolutePath(), new File(dir, name).getAbsolutePath()); n++; } catch (Throwable e) { }
            if (n >= 90) break;
        }
        MainActivity.log("[text] " + n + " system faces linked");
    }
}
