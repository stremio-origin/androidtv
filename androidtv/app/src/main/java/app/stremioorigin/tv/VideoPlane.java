package app.stremioorigin.tv;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.SubtitleView;

import java.util.ArrayList;
import java.util.List;

/* The video plane under the app (core/video.h on Android TV): Media3's ExoPlayer on a SurfaceView behind SDL's
   transparent drawing, as LG's video plane sits under the app there. The box's own decoders take the picture (Dolby
   Vision, HDR10/HDR10+, HLG where it has them; a Dolby Vision file with an HDR10 or SDR base plays on that base where
   the box has no Dolby Vision decoder); the sound is passed through to the TV or receiver (Dolby Digital (Plus), DTS,
   TrueHD) when the output takes it, and decoded in the app (FFmpeg) when it does not. Subtitles inside the file are
   drawn by Media3's subtitle view; the app draws every other subtitle itself. The native side reads poll() once a frame. */
@OptIn(markerClass = UnstableApi.class)
public final class VideoPlane {
    private static ExoPlayer player;
    private static DefaultTrackSelector selector;
    private static FrameLayout box;      /* clips the picture to its window (a crop shows part of a larger picture) */
    private static SurfaceView view;
    private static SubtitleView subs;
    private static final Handler ui = new Handler(Looper.getMainLooper());
    /* for the native side: 0 idle, 1 loading, 2 playing, 3 paused, 4 buffering, 5 ended, 6 error (VidState) */
    private static volatile long state, pos, dur, buf, vw, vh, firstFrame, hasMedia, seeking, fpsm, unsup, changed;
    private static volatile String error = "", hdr = "";
    private static volatile String audioLines = "", textLines = "";
    private static volatile int audioCur = -1;
    private static float[] place = { 0, 0, 1, 1, 0, 0, 1, 1 };
    private static boolean wantPlay = true, live;
    private static int volTarget = 100;
    private static volatile int dtsMode, builtDts = -1;   /* 0 auto, 1 convert, 2 off */
    public static void setDts(int m) { dtsMode = m; run(() -> { if (player != null && builtDts != dtsMode) { release(); } }); }

    private static void run(Runnable r) { if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else ui.post(r); }

    /* the viewer's colour and brightness (video_atv.c sends them): white and black until then */
    private static int subFg = android.graphics.Color.WHITE, subEdge = android.graphics.Color.BLACK;
    private static androidx.media3.ui.CaptionStyleCompat subStyleOf(int fg, int edge) {
        return new androidx.media3.ui.CaptionStyleCompat(fg, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, edge, null); }
    private static float subUp = 0.09f;   /* the lines' height over the bottom (the viewer's Position) */
    public static void subStyle(final int fg, final int edge, final int posPct) { run(() -> { subFg = fg; subEdge = edge; subUp = posPct / 100f;
        if (subs != null) { subs.setStyle(subStyleOf(fg, edge)); subs.setBottomPaddingFraction(subUp); } }); }

    /* the lines Media3 reads itself. The view is hidden while it has none: a shown full-screen view that draws makes the
       window count as drawn everywhere, and Android then blends the whole window over the app's picture every frame
       (one full screen of composition for nothing: on a box drawing it in software, a large part of each frame) */
    private static void cues(java.util.List<androidx.media3.common.text.Cue> c) {
        if (subs == null) return; subs.setCues(c);
        int v = c == null || c.isEmpty() ? android.view.View.INVISIBLE : android.view.View.VISIBLE; if (subs.getVisibility() != v) subs.setVisibility(v); }

    private static void ensure() {
        MainActivity a = MainActivity.self; if (a == null || player != null) return;
        ViewGroup root = MainActivity.layout(); if (root == null) return;
        box = new FrameLayout(a); box.setClipChildren(true);
        view = new SurfaceView(a);
        box.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        root.addView(box, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));   /* under SDL's surface */
        subs = new SubtitleView(a);   /* (only while the app is still reading a track itself) drawn like the app's own lines: white, outlined, no box */
        subs.setStyle(subStyleOf(subFg, subEdge));
        subs.setApplyEmbeddedStyles(false); subs.setApplyEmbeddedFontSizes(false); subs.setFractionalTextSize(0.0533f * 1.2f); subs.setBottomPaddingFraction(subUp);
        subs.setVisibility(android.view.View.INVISIBLE);   /* (shown only while it has lines: see cues()) */
        root.addView(subs, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyPlace());

        selector = new DefaultTrackSelector(a);
        selector.setParameters(selector.buildUponParameters().setPreferredAudioLanguage(null).setTunnelingEnabled(false)
            .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true));   /* a receiver switched on mid-film: passthrough taken again */
        /* Settings › Audio › DTS sound: Auto = the box's decoders (or passthrough) first, FFmpeg after; Convert = FFmpeg first
           (always decoded in the app); Off = the box alone */
        DefaultRenderersFactory rf = new DefaultRenderersFactory(a)
            .setExtensionRendererMode(dtsMode == 1 ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER : dtsMode == 2 ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true);
        builtDts = dtsMode;
        /* the buffer from the box's memory: a 1 GB box keeps less ahead than a 4 GB one (the torrent engine has its own) */
        long memMb = 0, freeMb = -1; try { android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) a.getSystemService(android.content.Context.ACTIVITY_SERVICE)).getMemoryInfo(mi); memMb = mi.totalMem / (1024 * 1024);
            freeMb = (mi.availMem - mi.threshold) / (1024 * 1024); } catch (Throwable e) { }
        /* as much as the box can spare: a remux at 40-60 Mbit/s needs tens of MB to ride out a slow second (12 MB was two
           seconds of it). The size for the box's memory, then no more than what is free now, less the system's own low
           mark and room for the video decoder */
        int tierMb = memMb > 0 && memMb < 1300 ? 64 : memMb < 2600 ? 128 : memMb < 4600 ? 256 : 384;
        int reserveMb = 240;   /* (the player is made once, before the film is known: room for a 4K decoder always) */
        int bufMb = freeMb < 0 ? (memMb > 0 && memMb < 1300 ? 12 : memMb < 2600 ? 32 : 64) : (int) Math.max(12, Math.min(tierMb, freeMb - reserveMb));
        int bufBytes = bufMb * 1024 * 1024;
        MainActivity.log("[video] player buffer " + bufMb + " MB (" + memMb + " MB of RAM, " + freeMb + " MB free)");
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufMb <= 12 ? 8000 : 15000, bufMb <= 12 ? 20000 : 50000, 1500, 3000).setTargetBufferBytes(bufBytes).setPrioritizeTimeOverSizeThresholds(false).build();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent("StremioOrigin/1.1 (Android TV)").setConnectTimeoutMs(20000).setReadTimeoutMs(30000);
        DefaultExtractorsFactory ex = new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(a, http), new MkvSeekHead.Factory(ex)); /* (Matroska: an index listed in a second SeekHead found too) */
        player = new ExoPlayer.Builder(a, rf).setTrackSelector(selector).setLoadControl(lc).setMediaSourceFactory(msf)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS).build();
        /* what the logs need to tell a stutter's cause: the decoder (a box's own, or software), frames dropped (the decoder or
           the box too slow), and each wait for data once the picture runs (the source too slow) */
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime t, String name, long initializedMs, long initMs) { MainActivity.log("[video] decoder " + name + " (ready in " + initMs + " ms)"); }
            @Override public void onDroppedVideoFrames(EventTime t, int dropped, long elapsedMs) { droppedFilm += dropped; MainActivity.log("[video] " + dropped + " frames dropped in " + elapsedMs + " ms (" + droppedFilm + " this film)"); }
            @Override public void onPlaybackStateChanged(EventTime t, int st) { if (st == androidx.media3.common.Player.STATE_BUFFERING && firstFrame == 1) MainActivity.log("[video] waiting for data at " + (t.currentPlaybackPositionMs / 100) / 10.0 + " s (" + (player != null ? player.getTotalBufferedDuration() : 0) + " ms buffered)"); }
        });
        player.setVideoSurfaceView(view);
        /* a film takes the sound (another app's sound pauses it, a call or the assistant ducks it); headphones or a
           receiver going away pauses it */
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) { readState(); }
            @Override public void onIsPlayingChanged(boolean p) { readState(); }
            @Override public void onPlayerError(PlaybackException e) {
                error = e.getErrorCodeName() + (e.getCause() != null ? ": " + e.getCause().getMessage() : ""); state = 6; changed = 1;
                MainActivity.log("[video] " + error); MainActivity.keepScreen(false); }
            @Override public void onRenderedFirstFrame() { firstFrame = 1; }
            @Override public void onVideoSizeChanged(VideoSize s) { vw = s.width; vh = s.height; applyPlace(); }
            @Override public void onTracksChanged(Tracks t) { readTracks(t); }
            @Override public void onCues(CueGroup g) { cues(g.cues); }
            @Override public void onPositionDiscontinuity(Player.PositionInfo o, Player.PositionInfo n, int reason) { if (reason == Player.DISCONTINUITY_REASON_SEEK) seeking = 1; }
        });
        ui.post(VideoPlane::tick);
    }
    /* the position and the buffer, read on the main thread (the player's thread) ten times a second */
    private static void tick() {
        ExoPlayer p = player; if (p == null) return;
        pos = p.getCurrentPosition(); long d = p.getDuration(); dur = d == C.TIME_UNSET ? 0 : d; buf = p.getBufferedPosition();
        if (seeking == 1 && p.getPlaybackState() == Player.STATE_READY) seeking = 0;
        readState(); ui.postDelayed(VideoPlane::tick, 100);
    }
    private static void readState() {
        ExoPlayer p = player; if (p == null) return;
        if (state == 6) return;
        int s = p.getPlaybackState();
        long st = s == Player.STATE_IDLE ? (hasMedia == 1 ? 1 : 0) : s == Player.STATE_BUFFERING ? (firstFrame == 1 ? 4 : 1) : s == Player.STATE_ENDED ? 5 : p.getPlayWhenReady() ? 2 : 3;
        if (st == 5) MainActivity.keepScreen(false);
        state = st;
    }

    /* ---- tracks: one line per track, "index \t lang \t name \t codec \t channels \t forced" ---- */
    private static final List<Tracks.Group> audioGroups = new ArrayList<>(), textGroups = new ArrayList<>();
    private static final List<Integer> audioIdx = new ArrayList<>(), textIdx = new ArrayList<>();
    private static void readTracks(Tracks t) {
        audioGroups.clear(); textGroups.clear(); audioIdx.clear(); textIdx.clear();
        StringBuilder al = new StringBuilder(), tl = new StringBuilder(); int na = 0, nt = 0, cur = -1; boolean selUnsup = false; String vh0 = hdr;
        for (Tracks.Group g : t.getGroups()) {
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                if (g.getType() == C.TRACK_TYPE_AUDIO) {
                    audioGroups.add(g); audioIdx.add(i);
                    al.append(na).append('\t').append(nz(f.language)).append('\t').append(audioName(f)).append('\t').append(codec(f)).append('\t').append(f.channelCount > 0 ? f.channelCount : 0).append('\t').append(g.isTrackSupported(i) ? 1 : 0).append('\n');   /* (last: this box plays it, decoded or passed through) */
                    if (g.isTrackSelected(i)) { cur = na; if (!g.isTrackSupported(i)) selUnsup = true; }
                    na++;
                } else if (g.getType() == C.TRACK_TYPE_TEXT) {
                    String m = nz(f.sampleMimeType);   /* broadcast captions carried in the picture (CEA-608/708, live channels' machine captions): never offered, as on the LG TV */
                    if (m.contains("cea-608") || m.contains("cea-708")) continue;
                    textGroups.add(g); textIdx.add(i);
                    tl.append(nt).append('\t').append(nz(f.language)).append('\t').append(nz(f.label)).append('\t').append(nz(f.sampleMimeType)).append('\t').append(0).append('\t').append((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0 ? 1 : 0).append('\n');
                    nt++;
                } else if (g.getType() == C.TRACK_TYPE_VIDEO && g.isTrackSelected(i)) {
                    hdr = hdrOf(f); fpsm = f.frameRate > 0 ? (long) (f.frameRate * 1000) : 0;
                    matchMode(f.frameRate, f.height);
                }
            }
        }
        audioLines = al.toString(); textLines = tl.toString(); audioCur = cur; unsup = selUnsup ? 1 : 0;
        if (!hdr.equals(vh0) || true) changed = 1;
    }
    private static String nz(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }
    /* the sound's codec as the app's rules name it ("dts", "truehd", "eac3", "ac3", "aac"...) */
    /* an audio track's name as the LG TV gives it, the line under its language in the tray: "EAC3 5.1 Atmos" */
    private static String audioName(Format f) {
        String c = codec(f).toUpperCase(java.util.Locale.ROOT); int ch = f.channelCount;
        boolean atmos = MimeTypes.AUDIO_E_AC3_JOC.equals(f.sampleMimeType) || (MimeTypes.AUDIO_TRUEHD.equals(f.sampleMimeType) && f.label != null && f.label.toLowerCase(java.util.Locale.ROOT).contains("atmos"));
        return nz(c + (ch == 6 ? " 5.1" : ch == 8 ? " 7.1" : ch == 2 ? " 2.0" : ch == 1 ? " 1.0" : "") + (atmos ? " Atmos" : ""));
    }
    private static String codec(Format f) {
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
        switch (m) {
            case MimeTypes.AUDIO_DTS: case MimeTypes.AUDIO_DTS_EXPRESS: return "dts";
            case MimeTypes.AUDIO_DTS_HD: return "dts-hd";
            case MimeTypes.AUDIO_DTS_X: return "dts-x";
            case MimeTypes.AUDIO_TRUEHD: return "truehd";
            case MimeTypes.AUDIO_E_AC3: case MimeTypes.AUDIO_E_AC3_JOC: return "eac3";
            case MimeTypes.AUDIO_AC3: return "ac3";
            case MimeTypes.AUDIO_AC4: return "ac4";
            case MimeTypes.AUDIO_AAC: return "aac";
            case MimeTypes.AUDIO_OPUS: return "opus";
            case MimeTypes.AUDIO_FLAC: return "flac";
            case MimeTypes.AUDIO_MPEG: return "mp3";
            default: return m.startsWith("audio/") ? m.substring(6) : m;
        }
    }
    /* the picture's HDR, as the LG app's player names it */
    private static String hdrOf(Format f) {
        String m = f.sampleMimeType == null ? "" : f.sampleMimeType, c = f.codecs == null ? "" : f.codecs;
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(m) || c.startsWith("dvh") || c.startsWith("dva") || c.startsWith("dav1")) return "DolbyVision";
        ColorInfo ci = f.colorInfo;
        if (ci != null) { if (ci.colorTransfer == C.COLOR_TRANSFER_ST2084) return "HDR10"; if (ci.colorTransfer == C.COLOR_TRANSFER_HLG) return "HLG"; }
        return "none";
    }

    /* ---- calls from the native side (any thread) ---- */
    public static void load(final String url, final long startMs, final boolean isLive) {
        droppedFilm = 0; state = 1; firstFrame = 0; hasMedia = 1; error = ""; hdr = ""; vw = vh = 0; pos = startMs; dur = 0; buf = 0; unsup = 0; seeking = 0; changed = 1;
        audioLines = ""; textLines = ""; audioCur = -1; wantPlay = true; live = isLive;
        MainActivity.keepScreen(true);
        run(() -> {
            ensure(); if (player == null) { state = 6; error = "no player"; return; }
            /* no subtitle of the file's own until the app picks one (a track flagged "default" was shown by itself) */
            selector.setParameters(selector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));
            MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
            if (isLive) b.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().build());
            player.setVolume(volTarget / 100f);
            if (startMs > 0) player.setMediaItem(b.build(), startMs); else player.setMediaItem(b.build());
            player.setPlayWhenReady(true); player.prepare();
            cues(java.util.Collections.emptyList());
        });
    }
    public static void unload() {
        state = 0; hasMedia = 0; firstFrame = 0; changed = 1; MainActivity.keepScreen(false); restoreMode();
        run(() -> { if (player != null) { player.stop(); player.clearMediaItems(); } cues(java.util.Collections.emptyList()); });
    }
    public static void play() { wantPlay = true; run(() -> { if (player != null) { if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0); player.play(); } }); }
    public static void pause() { wantPlay = false; run(() -> { if (player != null) player.pause(); }); }
    public static void seek(final long ms) { seeking = 1; pos = ms; run(() -> { if (player != null) player.seekTo(ms); }); }
    /* the volume, reached over easeMs (the trailer's sound fading in and out) */
    public static void volume(final int pct, final int easeMs) {
        volTarget = Math.max(0, Math.min(100, pct));
        run(() -> { if (player == null) return; final float from = player.getVolume(), to = volTarget / 100f; final long t0 = android.os.SystemClock.uptimeMillis();
            if (easeMs <= 0) { player.setVolume(to); return; }
            Runnable step = new Runnable() { public void run() { if (player == null) return; float k = Math.min(1f, (android.os.SystemClock.uptimeMillis() - t0) / (float) easeMs);
                player.setVolume(from + (to - from) * k); if (k < 1f) ui.postDelayed(this, 16); } };
            ui.post(step); });
    }
    public static String tracks(int type) { return type == 1 ? audioLines : textLines; }
    public static int current(int type) { return type == 1 ? audioCur : -1; }
    public static void select(final int type, final int idx) {
        run(() -> {
            if (player == null || selector == null) return;
            int tt = type == 1 ? C.TRACK_TYPE_AUDIO : C.TRACK_TYPE_TEXT;
            List<Tracks.Group> gs = type == 1 ? audioGroups : textGroups; List<Integer> is = type == 1 ? audioIdx : textIdx;
            DefaultTrackSelector.Parameters.Builder p = selector.buildUponParameters().clearOverridesOfType(tt);
            if (idx < 0 || idx >= gs.size()) { if (tt == C.TRACK_TYPE_TEXT) p.setTrackTypeDisabled(tt, true); }
            else { p.setTrackTypeDisabled(tt, false); p.setOverrideForType(new TrackSelectionOverride(gs.get(idx).getMediaTrackGroup(), is.get(idx))); }
            selector.setParameters(p.build());
        });
    }
    /* the picture's place: part (sx, sy, sw, sh) of the decoded frame shown in the rectangle (x, y, w, h), all as
       fractions (of the frame, of the screen); the whole frame in a rectangle is (0, 0, 1, 1, ...) */
    public static void place(float sx, float sy, float sw, float sh, float x, float y, float w, float h) {
        final float[] p = { sx, sy, sw, sh, x, y, w, h }; run(() -> { place = p; applyPlace(); });
    }
    private static void applyPlace() {
        ViewGroup root = MainActivity.layout(); if (box == null || view == null || root == null) return;
        int W = root.getWidth(), H = root.getHeight(); if (W <= 0 || H <= 0) return;
        float[] p = place;
        int bx = Math.round(p[4] * W), by = Math.round(p[5] * H), bw = Math.max(1, Math.round(p[6] * W)), bh = Math.max(1, Math.round(p[7] * H));
        /* the whole frame fitted inside the box (its shape kept); a crop: the frame scaled so its part fills the box */
        int fw, fh, fx, fy;
        if (p[2] >= 0.999f && p[3] >= 0.999f && p[0] <= 0.001f && p[1] <= 0.001f) {
            float ar = vw > 0 && vh > 0 ? (float) vw / vh : 16f / 9f;
            if ((float) bw / bh > ar) { fh = bh; fw = Math.round(bh * ar); } else { fw = bw; fh = Math.round(bw / ar); }
            fx = (bw - fw) / 2; fy = (bh - fh) / 2;
        } else {
            fw = Math.round(bw / p[2]); fh = Math.round(bh / p[3]); fx = -Math.round(p[0] * fw); fy = -Math.round(p[1] * fh);
        }
        ViewGroup.LayoutParams bl = box.getLayoutParams();
        MainActivity.log("[video] place box " + bx + "," + by + " " + bw + "x" + bh + " frame " + fx + "," + fy + " " + fw + "x" + fh + " (root " + W + "x" + H + ", video " + vw + "x" + vh + ")");
        box.setX(bx); box.setY(by); if (bl.width != bw || bl.height != bh) { bl.width = bw; bl.height = bh; box.setLayoutParams(bl); }
        FrameLayout.LayoutParams vl = (FrameLayout.LayoutParams) view.getLayoutParams();
        if (vl.width != fw || vl.height != fh || vl.leftMargin != fx || vl.topMargin != fy) { vl.width = fw; vl.height = fh; vl.leftMargin = fx; vl.topMargin = fy; view.setLayoutParams(vl); }
    }

    /* poll: state, position ms, duration ms, buffered ms, width, height, first frame shown, has media, seeking, fps x1000, sound unsupported, changed */
    public static long[] poll() {
        long c = changed; changed = 0;
        return new long[] { state, pos, dur, buf, vw, vh, firstFrame, hasMedia, seeking, fpsm, unsup, c };
    }
    public static String text(int what) { return what == 0 ? error : hdr; }

    /* ---- the TV's mode follows the film (frame-rate matching): a mode at the same size or the film's own (a 4K film on a box
       in 1080p) whose refresh rate is the film's or a whole multiple of it (23.976 at 23.976 / 47.95, 25 at 50, ...);
       nothing when the screen already fits it or has no such mode. The mode the app found is restored when the film
       ends. Android 6 and up. ---- */
    private static long droppedFilm; /* (frames dropped since this film was loaded: the log) */
    private static int savedMode = -1;
    private static void matchMode(final float fps, final int height) {
        if (android.os.Build.VERSION.SDK_INT < 23 || fps <= 1) return;
        ui.post(() -> {
            MainActivity a = MainActivity.self; if (a == null) return;
            android.view.Display d = a.getWindowManager().getDefaultDisplay(); android.view.Display.Mode cur = d.getMode(), best = null;
            int want = Math.max(Math.min(cur.getPhysicalWidth(), cur.getPhysicalHeight()), height > 1080 ? 2160 : 0);
            if (fits(cur.getRefreshRate(), fps) && Math.min(cur.getPhysicalWidth(), cur.getPhysicalHeight()) >= want) return;
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                int h = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                if (!fits(m.getRefreshRate(), fps) || (h != want && h != Math.min(cur.getPhysicalWidth(), cur.getPhysicalHeight()))) continue;
                if (best == null || (h == want && Math.min(best.getPhysicalWidth(), best.getPhysicalHeight()) != want) || m.getRefreshRate() < best.getRefreshRate()) best = m;
            }
            if (best == null || best.getModeId() == cur.getModeId()) return;
            if (savedMode < 0) savedMode = cur.getModeId();
            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes(); lp.preferredDisplayModeId = best.getModeId(); a.getWindow().setAttributes(lp);
            MainActivity.log("[video] screen mode " + best.getPhysicalWidth() + "x" + best.getPhysicalHeight() + " @ " + best.getRefreshRate() + " Hz for a " + fps + " fps film");
        });
    }
    private static boolean fits(float hz, float fps) { float k = hz / fps; return Math.abs(k - Math.round(k)) < 0.01f && Math.round(k) >= 1; }
    private static void restoreMode() {
        if (savedMode < 0) return; final int id = savedMode; savedMode = -1;
        ui.post(() -> { MainActivity a = MainActivity.self; if (a == null) return; android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes(); lp.preferredDisplayModeId = id; a.getWindow().setAttributes(lp); });
    }

    /* the activity leaves (Home, another app): the film pauses where it is */
    static void onPause() { run(() -> { if (player != null && player.isPlaying()) { player.pause(); } }); }
    static void release() { run(() -> { if (player != null) { player.release(); player = null; }
        ViewGroup root = MainActivity.layout(); if (root != null) { if (box != null) root.removeView(box); if (subs != null) root.removeView(subs); } box = null; view = null; subs = null; }); }
}
