/* Android TV's player behind core/video.h: Media3 (ExoPlayer) in VideoPlane.java plays on a SurfaceView under the app's
   transparent drawing, the same model as the LG video plane. It hands the picture to the box's own decoders (Dolby Vision,
   HDR10/HDR10+, HLG where the box has them), passes Dolby Digital (Plus), DTS and TrueHD through to the TV or receiver
   when the output takes them, and decodes them itself (FFmpeg) when it does not. Its state is read once a frame. */
#include <SDL2/SDL.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "core/video.h"
#include "core/store.h"
#include "platform/androidtv/atv.h"

#define VP 1   /* atv_class(): VideoPlane */
static struct { long long st, pos, dur, buf, w, h, live, media, settle, fpsm, unsup; } V;
static char vErr[160], vHdr[32]; static int surfW = 1920, surfH = 1080, liveSrc;
static struct { int on; char base[4096]; double off, dur; } timed;   /* video_load_timed: seeks are loads, times offset */
static char url[4096];

static void vcall(const char *name) { JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, name, "()V"); if (m) { (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m); atv_check(e); } }
static void vcall_i(const char *name, int v) { JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, name, "(I)V"); if (m) { (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m, (jint)v); atv_check(e); } }
static volatile int startSilent; /* (video_start_silent) */
/* a film held for its seek previews (Settings > Playback): its loads start at volume 0, set before the player takes the
   load (both run on Android's UI thread in that order), so not a sound comes before the player's own hold; the player's
   video_set_volume brings the sound up softly when it starts */
void video_start_silent(int on) { startSilent = on; }
static void load_now(const char *u, double startSec) {
  { JNIEnv *ev = atv_env(); jmethodID mv = atv_method(ev, VP, "volume", "(II)V"); if (mv) { (*ev)->CallStaticVoidMethod(ev, atv_class(ev, VP), mv, (jint)(startSilent ? 0 : 100), (jint)0); atv_check(ev); } }
  /* (every load at full volume, as webOS gives each new media: the one player here kept the last one's, and a film
     opened after the home screen's trailer faded out played at volume 0, silent) */
  { const char *d = store_get("dtsMode", "auto"); vcall_i("setDts", !strcmp(d, "convert") ? 1 : !strcmp(d, "off") ? 2 : 0); }   /* Settings › Audio › DTS sound */
  { extern int player_open(void); vcall_i("setFilm", player_open() ? 1 : 0); } /* (a film: the TV's mode may follow it and its 4K track is taken; the home screen's trailer: neither, the screen never blinks for it) */
  JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, "load", "(Ljava/lang/String;JZ)V"); if (!m) return;
  jstring s = (*e)->NewStringUTF(e, u); (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m, s, (jlong)(startSec * 1000), (jboolean)(liveSrc != 0)); atv_check(e); (*e)->DeleteLocalRef(e, s);
  memset(&V, 0, sizeof V); V.st = VID_LOADING; vErr[0] = vHdr[0] = 0;
  fprintf(stderr, "[video] load %.200s%s\n", u, liveSrc ? " (live)" : "");
}
int video_init(const char *appId) { (void)appId; return 1; }
void video_set_live(int on) { liveSrc = on; }
void video_load(const char *u) { timed.on = 0; liveSrc = 0; snprintf(url, sizeof url, "%s", u ? u : ""); load_now(url, 0); } /* (the live mark is set after the load: one left from the channel before must not mark a film) */
void video_load_timed(const char *base, double durSec) {
  timed.on = 1; timed.off = 0; timed.dur = durSec; snprintf(timed.base, sizeof timed.base, "%s", base);
  snprintf(url, sizeof url, "%s?t=0", base); load_now(url, 0); }
void video_unload(void) { timed.on = 0; url[0] = 0; vcall("unload"); memset(&V, 0, sizeof V); }
void video_play(void) { vcall("play"); }
void video_pause(void) { vcall("pause"); }
void video_seek(double sec) {
  if (sec < 0) sec = 0;
  if (timed.on) { timed.off = sec; snprintf(url, sizeof url, "%s?t=%.3f", timed.base, sec); fprintf(stderr, "[video] the stream again from %.0f s\n", sec); load_now(url, 0); return; }
  JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, "seek", "(J)V"); if (m) { (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m, (jlong)(sec * 1000)); atv_check(e); }
  V.pos = (long long)(sec * 1000); V.settle = 1;
}
double video_time(void) { return V.pos / 1000.0 + (timed.on ? timed.off : 0); }
double video_duration(void) { return timed.on ? timed.dur : V.dur > 0 ? V.dur / 1000.0 : 0; }
double video_buffered_end(void) { return V.buf / 1000.0 + (timed.on ? timed.off : 0); }
VidState video_state(void) { return (VidState)V.st; }
const char *video_error(void) { return vErr; }
int video_live(void) { return V.live != 0; }
int video_has_media(void) { return V.media != 0; }
void video_set_volume(int pct, int easeMs) {
  JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, "volume", "(II)V"); if (m) { (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m, (jint)pct, (jint)easeMs); atv_check(e); } }
int video_settling(void) { return V.settle != 0; }
int video_width(void) { return (int)V.w; }
int video_height(void) { return (int)V.h; }
double video_fps(void) { return V.fpsm / 1000.0; }
int video_hdr(char *out, int cap) { snprintf(out, (size_t)cap, "%s", vHdr); return out[0] != 0; }
int video_audio_unsupported(void) { return V.unsup != 0; }
int video_partial_plane(void) { return 1; }   /* the plane is a view: any rectangle, any crop */
/* the plane is a SurfaceView laid out again on the UI thread: a move lands a frame or two after the call, so a grow sends
   it ahead in steps (live previews no longer grow on Android TV: they go full screen at once). SB_GROW_* (development) */
VidPlaneCaps video_plane_caps(void) { VidPlaneCaps c = { 0, 50, 33, 0 }; const char *e;
  if ((e = getenv("SB_GROW_LEAD"))) c.leadMs = atoi(e); if ((e = getenv("SB_GROW_STEP"))) { c.stepMs = atoi(e); c.perFrame = c.stepMs <= 0; } if ((e = getenv("SB_GROW")) && !strcmp(e, "cut")) c.cut = 1; return c; }

/* tracks: VideoPlane.tracks(type) gives one line per track, "index \t lang \t name \t codec \t channels \t forced" */
static int tracks(int type, VidTrack *out, int max) {
  JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, "tracks", "(I)Ljava/lang/String;"); if (!m) return 0;
  char buf[8192]; jstring s = (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, VP), m, (jint)type); if (!atv_check(e)) return 0; atv_jstr(e, s, buf, sizeof buf);
  int n = 0; char *save = NULL;
  for (char *ln = strtok_r(buf, "\n", &save); ln && n < max; ln = strtok_r(NULL, "\n", &save)) {
    VidTrack *t = &out[n]; memset(t, 0, sizeof *t); char *f[6] = { 0 }; int k = 0; char *p = ln; f[k++] = p;
    while (*p && k < 6) { if (*p == '\t') { *p = 0; f[k++] = p + 1; } p++; }
    if (k < 6) continue;
    t->idx = atoi(f[0]); snprintf(t->lang, sizeof t->lang, "%s", f[1]); snprintf(t->name, sizeof t->name, "%s", f[2]);
    snprintf(t->codec, sizeof t->codec, "%s", f[3]); t->channels = atoi(f[4]); t->forced = atoi(f[5]); n++; }
  return n;
}
static int audPlays[32] = { 0 }, audPlaysN; /* (video_audio_plays: per audio track idx, from the last list) */
int video_audio_tracks(VidTrack *out, int max) { int n = tracks(1, out, max); audPlaysN = 0;
  for (int i = 0; i < n; i++) { if (out[i].idx >= 0 && out[i].idx < 32) { audPlays[out[i].idx] = out[i].forced ? 1 : 0; if (out[i].idx + 1 > audPlaysN) audPlaysN = out[i].idx + 1; } out[i].forced = 0; } /* (an audio line's last field is "plays here", not "forced") */
  return n; }
/* whether this box plays an audio track (ExoPlayer: decoded, by its own decoders or FFmpeg, or passed through to the TV or
   receiver): 1 yes, 0 no, -1 not known (no list yet) */
int video_audio_plays(int idx) { return idx >= 0 && idx < audPlaysN ? audPlays[idx] : -1; }
int video_text_tracks(VidTrack *out, int max) { return tracks(3, out, max); }
int video_audio_current(void) { JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, "current", "(I)I"); if (!m) return 0; int r = (*e)->CallStaticIntMethod(e, atv_class(e, VP), m, (jint)1); return atv_check(e) ? r : 0; }
void video_select_audio(int idx) { JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, "select", "(II)V"); if (m) { (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m, (jint)1, (jint)idx); atv_check(e); } }
void video_select_text(int idx) { JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, "select", "(II)V"); if (m) { (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m, (jint)3, (jint)idx); atv_check(e); } }

/* the picture's place: layout units (1920x1080) to the screen's pixels on the Java side (it knows the view's size) */
static void place(float sx, float sy, float sw, float sh, float x, float y, float w, float h) {
  JNIEnv *e = atv_env(); jmethodID m = atv_method(e, VP, "place", "(FFFFFFFF)V"); if (!m) return;
  (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m, sx, sy, sw, sh, x / 1920.0f, y / 1080.0f, w / 1920.0f, h / 1080.0f); atv_check(e); }
static float last[4] = { -1, -1, -1, -1 };   /* the last whole-picture window (a crop in between forgets it: Original after Zoom is placed again) */
void video_set_window(float x, float y, float w, float h) {
  if (last[0] == x && last[1] == y && last[2] == w && last[3] == h) return;
  last[0] = x; last[1] = y; last[2] = w; last[3] = h; place(0, 0, 1, 1, x, y, w, h); }
int video_set_crop(int sx, int sy, int sw, int sh, float dx, float dy, float dw, float dh) {
  if (V.w <= 0 || V.h <= 0 || sw <= 0 || sh <= 0) return 0;
  last[0] = -1; place((float)sx / V.w, (float)sy / V.h, (float)sw / V.w, (float)sh / V.h, dx, dy, dw, dh); return 1; }
void video_set_surface(int w, int h) { surfW = w; surfH = h; }

/* the lines Android's own subtitle view draws (an embedded track while the app is still reading it): the viewer's
   colour, outline and brightness, as the app's own lines (Settings › Subtitles, the player's style card) */
static unsigned hexc(const char *h, unsigned dflt) { if (!h || h[0] != '#' || strlen(h) < 7) return dflt; return (unsigned)strtoul(h + 1, NULL, 16) & 0xffffffu; }
static unsigned dimc(unsigned c, float k) { return ((unsigned)(((c >> 16) & 255) * k) << 16) | ((unsigned)(((c >> 8) & 255) * k) << 8) | (unsigned)((c & 255) * k); }
static void sub_style_tick(JNIEnv *e) { static int n, wasP = -1; static unsigned was = 0xffffffffu, wasO;
  if (n++ % 30) return; /* (twice a second is enough: the style card changes it while a film plays) */
  float k = atoi(store_get("subsBright", "100")) / 100.0f; if (k <= 0 || k > 1) k = 1;
  unsigned fg = dimc(hexc(store_get("subsColor", "#ffffff"), 0xffffff), k), ol = dimc(hexc(store_get("subsOutlineColor", "#000000"), 0), k);
  /* the height over the bottom, as the player's lines (subsPosPct 2..30 %, else the older six steps) */
  static const int STEP[6] = { 3, 8, 13, 18, 23, 28 }; int pos = atoi(store_get("subsPosPct", "0"));
  if (pos < 2 || pos > 30) { int i = atoi(store_get("subsPosition", "1")); pos = STEP[i < 0 || i > 5 ? 1 : i]; }
  if (fg == was && ol == wasO && pos == wasP) return; was = fg; wasO = ol; wasP = pos;
  fprintf(stderr, "[video] subtitle view: #%06x on #%06x (brightness %.0f%%), %d%% up\n", fg, ol, k * 100, pos);
  jmethodID m = atv_method(e, VP, "subStyle", "(III)V"); if (m) { (*e)->CallStaticVoidMethod(e, atv_class(e, VP), m, (jint)(0xff000000u | fg), (jint)(0xff000000u | ol), (jint)pos); atv_check(e); } }
void video_pump(void) {
  JNIEnv *e = atv_env(); sub_style_tick(e); static jmethodID m; if (!m) m = atv_method(e, VP, "poll", "()[J"); if (!m) return;
  jlongArray r = (jlongArray)(*e)->CallStaticObjectMethod(e, atv_class(e, VP), m); if (!atv_check(e) || !r) return;
  jlong a[12] = { 0 }; jsize n = (*e)->GetArrayLength(e, r); if (n > 12) n = 12; (*e)->GetLongArrayRegion(e, r, 0, n, a); (*e)->DeleteLocalRef(e, r);
  /* poll: state, position ms, duration ms, buffered ms, width, height, first frame shown, has media, seeking, fps x1000, sound unsupported, changed (hdr/error to read) */
  V.st = a[0]; V.pos = a[1]; V.dur = a[2]; V.buf = a[3]; V.w = a[4]; V.h = a[5]; V.live = a[6]; V.media = a[7]; V.settle = a[8]; V.fpsm = a[9]; V.unsup = a[10];
  if (a[11]) { jmethodID s = atv_method(e, VP, "text", "(I)Ljava/lang/String;"); if (s) {
      atv_jstr(e, (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, VP), s, (jint)0), vErr, sizeof vErr); atv_check(e);
      atv_jstr(e, (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, VP), s, (jint)1), vHdr, sizeof vHdr); atv_check(e);
      /* the player's refusals in the words the player screen reads (webOS: "Codec", "Fail To Demultiplex"): a format the
         box can't decode, or a container the player can't read, says the format isn't supported, also on a torrent */
      size_t el = strlen(vErr);
      if (strstr(vErr, "DECODING_FORMAT_UNSUPPORTED") || strstr(vErr, "DECODING_FORMAT_EXCEEDS_CAPABILITIES")) snprintf(vErr + el, sizeof vErr - el, "; Codec");
      else if (strstr(vErr, "PARSING_CONTAINER_UNSUPPORTED")) snprintf(vErr + el, sizeof vErr - el, "; Demultiplex"); }
    if (vErr[0] && V.st == VID_ERROR) fprintf(stderr, "[video] error: %s\n", vErr);
    if (vHdr[0]) fprintf(stderr, "[video] %lldx%lld %s %.3f fps\n", V.w, V.h, vHdr, V.fpsm / 1000.0); }
}
/* a live broadcast after a pause: loaded again at its edge, with the same address */
void video_live_rejoin(void) { if (!url[0]) return; int was = liveSrc; liveSrc = 1; fprintf(stderr, "[video] live: joined again where it is now\n"); load_now(url, 0); liveSrc = was; }
/* the picture's edge colours: the plane is a hardware surface the app cannot read (as LG's), so the light round the
   preview box follows the channel's logo, as on the TV */
int video_edge_colors(uint32_t *out4) { (void)out4; return 0; }
/* the preview box shows a picture whenever one plays (the name is the Mac preview's, which can simulate playback) */
int video_mac_real(void) { return 1; }
