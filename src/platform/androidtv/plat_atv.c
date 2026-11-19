/* Android TV: the platform calls (plat.h, mem.h) over SDL's activity (app/stremioorigin/tv/MainActivity). Java copies
   the bundled assets to the app's files folder before the app starts (fonts and dictionaries are read as files) and
   answers the questions here (the device, its screen, what it decodes); the remote's keys Java takes first come in
   through nativeTvKey as SDL key events. The app itself is the TV app, unchanged: the same screens, the same rules. */
#include <SDL2/SDL.h>
#include <SDL2/SDL_system.h>
#include <jni.h>
#include <locale.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/statvfs.h>
#include <android/log.h>
#include "platform/plat.h"
#include "platform/mem.h"
#include "core/keys.h"
#include "core/applog.h"
#include "core/store.h"
#include "platform/androidtv/atv.h"
#include "core/app.h"
#include "ui/img.h"
#include "ui/atlas.h"
#include "ui/gfx.h"
#include "cJSON.h"

static char assetDir[1024], dataDir[1024], devName[200], lastExit[160];
static volatile int logReady; /* stderr reaches the kept log (applog started): Java's lines go there too */

/* Java's log lines (MainActivity.log): into the app's own log, so shared logs have them (the player's buffer, the
   screen mode, the decoders found, the home screen's rows, the update's steps); before the start, the system log only */
JNIEXPORT void JNICALL Java_app_stremioorigin_tv_MainActivity_nativeLog(JNIEnv *e, jclass c, jstring s) {
  (void)c; if (!s) return; const char *u = (*e)->GetStringUTFChars(e, s, NULL); if (!u) return;
  if (logReady) fprintf(stderr, "%s\n", u); else __android_log_write(ANDROID_LOG_INFO, "StremioOrigin", u);
  (*e)->ReleaseStringUTFChars(e, s, u);
}
/* shared logs' device line (core/applog.c): this build's processor, the RAM, the storage left, how the last session
   ended (Android 11 and up say it: the system ending it for memory, a crash, not answering) */
const char *plat_log_header(void) {
  static char h[400]; struct statvfs v; long freeMb = !statvfs(dataDir, &v) ? (long)((unsigned long long)v.f_bavail * v.f_frsize / (1024 * 1024)) : -1;
  snprintf(h, sizeof h, "build %s · RAM %d MB · storage free %ld MB%s%s",
#if defined(__aarch64__)
    "arm64-v8a",
#else
    "armeabi-v7a",
#endif
    plat_mem_total_mb(), freeMb, lastExit[0] ? " · last session ended: " : "", lastExit);
  return h;
}

/* stderr to the system log (adb logcat -s StremioOrigin), line by line; the app's own kept log gets every line too */
static int logPipe[2];
/* the start's own log (<files>/data/laststart.txt, begun by Java at each open): every line of the first 256 KB, written
   at once, so a start that dies or gives up in its first seconds leaves its lines for the next open to show
   (MainActivity: StartReport) */
int atvStartFd = -1;
static void start_write(const char *s, size_t n) { static size_t total; if (atvStartFd < 0 || total > 256 * 1024) return; total += n; if (write(atvStartFd, s, n) < 0) { } }
static void *log_thread(void *a) { (void)a; char buf[2048]; size_t n = 0; ssize_t r;
  while ((r = read(logPipe[0], buf + n, sizeof buf - 1 - n)) > 0) { n += (size_t)r; buf[n] = 0; char *s = buf, *e;
    while ((e = strchr(s, '\n'))) { *e = 0; __android_log_write(ANDROID_LOG_INFO, "StremioOrigin", s); start_write(s, strlen(s)); start_write("\n", 1); s = e + 1; }
    n = strlen(s); memmove(buf, s, n + 1); if (n >= sizeof buf - 1) { __android_log_write(ANDROID_LOG_INFO, "StremioOrigin", buf); start_write(buf, n); start_write("\n", 1); n = 0; } }
  return NULL; }
/* a crash: what is still in the pipe goes straight to the start's log (the log thread dies with the process) */
void atv_crash_drain(void) {
  if (atvStartFd < 0) return; int fl = fcntl(logPipe[0], F_GETFL); if (fl >= 0) fcntl(logPipe[0], F_SETFL, fl | O_NONBLOCK);
  char b[4096]; ssize_t r; while ((r = read(logPipe[0], b, sizeof b)) > 0) start_write(b, (size_t)r); }

/* ---- calls into Java (MainActivity's static methods) ---- */
JNIEnv *atv_env(void) { return (JNIEnv *)SDL_AndroidGetJNIEnv(); }
static jclass cls_of(JNIEnv *e, const char *name, jclass *slot) {
  if (*slot) return *slot;
  jobject act = (jobject)SDL_AndroidGetActivity(); if (!act) return NULL;
  /* the activity's own class loader: a thread SDL did not start (a worker) only sees the system classes */
  jclass ac = (*e)->GetObjectClass(e, act); jmethodID gl = (*e)->GetMethodID(e, ac, "getClassLoader", "()Ljava/lang/ClassLoader;");
  jobject ld = (*e)->CallObjectMethod(e, act, gl); jclass lc = (*e)->FindClass(e, "java/lang/ClassLoader");
  jmethodID lcm = (*e)->GetMethodID(e, lc, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
  char dotted[128]; snprintf(dotted, sizeof dotted, "%s", name); for (char *p = dotted; *p; p++) if (*p == '/') *p = '.';
  jstring jn = (*e)->NewStringUTF(e, dotted); jclass k = (jclass)(*e)->CallObjectMethod(e, ld, lcm, jn);
  if ((*e)->ExceptionCheck(e)) { (*e)->ExceptionClear(e); k = NULL; }
  if (k) { *slot = (jclass)(*e)->NewGlobalRef(e, k); (*e)->DeleteLocalRef(e, k); }
  (*e)->DeleteLocalRef(e, jn); (*e)->DeleteLocalRef(e, lc); (*e)->DeleteLocalRef(e, ld); (*e)->DeleteLocalRef(e, ac); (*e)->DeleteLocalRef(e, act);
  if (!*slot) fprintf(stderr, "[jni] no class %s\n", name);
  return *slot;
}
jclass atv_class(JNIEnv *e, int which) { static jclass c[2]; return cls_of(e, which ? ATV_PKG "/VideoPlane" : ATV_PKG "/MainActivity", &c[which ? 1 : 0]); }
jmethodID atv_method(JNIEnv *e, int which, const char *name, const char *sig) {
  jclass c = atv_class(e, which); if (!c) return NULL;
  jmethodID m = (*e)->GetStaticMethodID(e, c, name, sig); if (!m) { (*e)->ExceptionClear(e); fprintf(stderr, "[jni] no %s%s\n", name, sig); }
  return m; }
int atv_check(JNIEnv *e) { if ((*e)->ExceptionCheck(e)) { (*e)->ExceptionDescribe(e); (*e)->ExceptionClear(e); return 0; } return 1; }
void atv_jstr(JNIEnv *e, jstring s, char *out, size_t cap) { out[0] = 0; if (!s) return; const char *c = (*e)->GetStringUTFChars(e, s, NULL); if (c) { snprintf(out, cap, "%s", c); (*e)->ReleaseStringUTFChars(e, s, c); } (*e)->DeleteLocalRef(e, s); }
/* MainActivity.info(what): the device's answers as text */
static void info(int what, char *out, size_t cap) { JNIEnv *e = atv_env(); out[0] = 0; jmethodID m = atv_method(e, 0, "info", "(I)Ljava/lang/String;"); if (!m) return;
  jstring s = (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, 0), m, (jint)what); if (!atv_check(e)) return; atv_jstr(e, s, out, cap); }
/* the screen's rate as Android composes it (main.c paces the app by it instead of timing the screen: an emulator or a
   busy box can time 20 ms frames on a 60 Hz display); 0 unknown */
int plat_display_hz(void) { JNIEnv *e = atv_env(); static jmethodID m; if (!m) m = atv_method(e, 0, "displayHz", "()F"); if (!m) return 0;
  float hz = (*e)->CallStaticFloatMethod(e, atv_class(e, 0), m); if (!atv_check(e) || !(hz > 1)) return 0; return (int)(hz + 0.5f); }
void atv_call_int(const char *name, int v) { JNIEnv *e = atv_env(); jmethodID m = atv_method(e, 0, name, "(I)V"); if (m) { (*e)->CallStaticVoidMethod(e, atv_class(e, 0), m, (jint)v); atv_check(e); } }

/* a JPEG decoded by Android at the smallest power-of-two size still covering bw x bh (MainActivity.decodeJpeg): RGBA
   pixels (malloc) and their size, or NULL. Called from the picture workers (each attached to Java by SDL); every
   local reference let go at once: a worker lives as long as the app */
unsigned char *atv_jpeg_decode(const unsigned char *data, size_t len, int bw, int bh, int *w, int *h) {
  extern int atvSafeStart; if (atvSafeStart) return NULL; /* (a safe start: the app's own decoder only) */
  JNIEnv *e = atv_env(); static jmethodID m; static int none; if (none || !e) return NULL;
  if (!m) { m = atv_method(e, 0, "decodeJpeg", "([BII)[B"); if (!m) { none = 1; return NULL; } }
  jbyteArray in = (*e)->NewByteArray(e, (jsize)len); if (!in) { (*e)->ExceptionClear(e); return NULL; }
  (*e)->SetByteArrayRegion(e, in, 0, (jsize)len, (const jbyte *)data);
  jbyteArray out = (jbyteArray)(*e)->CallStaticObjectMethod(e, atv_class(e, 0), m, in, (jint)bw, (jint)bh);
  (*e)->DeleteLocalRef(e, in); if (!atv_check(e) || !out) { if (out) (*e)->DeleteLocalRef(e, out); return NULL; }
  unsigned char *px = NULL; jsize n = (*e)->GetArrayLength(e, out); unsigned char hd[8];
  if (n >= 8) { (*e)->GetByteArrayRegion(e, out, n - 8, 8, (jbyte *)hd); /* (the size after the pixels: MainActivity.decodeJpeg) */
    int ow = hd[0] | hd[1] << 8 | hd[2] << 16 | hd[3] << 24, oh = hd[4] | hd[5] << 8 | hd[6] << 16 | hd[7] << 24;
    if (ow > 0 && oh > 0 && (long)n == 8 + (long)ow * oh * 4 && (px = malloc((size_t)ow * oh * 4))) { (*e)->GetByteArrayRegion(e, out, 0, ow * oh * 4, (jbyte *)px); *w = ow; *h = oh;
      static int said; if (!said) { said = 1; fprintf(stderr, "[img] jpeg: Android's own decoder, scaled (first: %dx%d for a %dx%d box)\n", ow, oh, bw, bh); } } }
  (*e)->DeleteLocalRef(e, out); return px;
}

/* ---- the remote: keys Java takes before SDL (colour keys, channel keys, the media keys, Menu), as key events ---- */
JNIEXPORT void JNICALL Java_app_stremioorigin_tv_MainActivity_nativeTvKey(JNIEnv *env, jclass c, jint code, jboolean down, jint repeat) {
  (void)env; (void)c; SDL_Event ev; memset(&ev, 0, sizeof ev);
  ev.type = down ? SDL_KEYDOWN : SDL_KEYUP; ev.key.state = down ? SDL_PRESSED : SDL_RELEASED; ev.key.repeat = repeat > 0;
  ev.key.keysym.scancode = (SDL_Scancode)(ATV_KEY_BASE + code); ev.key.keysym.sym = 0; ev.key.timestamp = SDL_GetTicks();
  SDL_PushEvent(&ev);
}
int plat_map_key(const SDL_KeyboardEvent *e) {
  int sc = (int)e->keysym.scancode;
  if (sc >= ATV_KEY_BASE) switch (sc - ATV_KEY_BASE) {   /* Android's key codes (KeyEvent.KEYCODE_*) */
    case 19: return K_UP; case 20: return K_DOWN; case 21: return K_LEFT; case 22: return K_RIGHT;
    case 23: case 66: case 160: case 96: return K_OK;                 /* DPAD_CENTER, ENTER, NUMPAD_ENTER, BUTTON_A */
    case 4: case 97: case 111: return K_BACK;                         /* BACK, BUTTON_B, ESCAPE */
    case 85: return K_PLAYPAUSE; case 126: return K_PLAY; case 127: return K_PAUSE; case 86: return K_STOP;
    case 89: return K_REW; case 90: return K_FF; case 88: return K_REW; case 87: return K_FF;   /* PREVIOUS / NEXT as rewind / forward */
    case 183: return K_RED; case 184: return K_GREEN; case 185: return K_YELLOW; case 186: return K_BLUE;
    case 166: return K_CH_UP; case 167: return K_CH_DOWN; case 92: return K_CH_UP; case 93: return K_CH_DOWN;   /* CHANNEL, PAGE_UP/DOWN */
    case 84: if (e->state == SDL_PRESSED && !e->repeat) app_go(SCR_SEARCH, NULL, 0); return K_NONE;   /* SEARCH (remotes with a search key): the Search page */
    case 82: return K_RED;   /* MENU (remotes without colour keys, Fire TV's Menu): what Red opens on LG, the title's or card's menu */
    case 165: return K_INFO; /* INFO: Stats for nerds on and off, anywhere, as LG's Info key */
    default: return K_NONE; }
  switch (e->keysym.sym) {   /* a keyboard plugged into the box */
    case SDLK_LEFT: return K_LEFT; case SDLK_RIGHT: return K_RIGHT; case SDLK_UP: return K_UP; case SDLK_DOWN: return K_DOWN;
    case SDLK_RETURN: case SDLK_KP_ENTER: case SDLK_SELECT: return K_OK;
    case SDLK_AC_BACK: case SDLK_ESCAPE: return K_BACK;
    case SDLK_AUDIOPLAY: return K_PLAYPAUSE; case SDLK_AUDIOSTOP: return K_STOP;
    case SDLK_AUDIOREWIND: return K_REW; case SDLK_AUDIOFASTFORWARD: return K_FF;
    case SDLK_SPACE: return K_PLAYPAUSE;
    case SDLK_F1: return K_RED; case SDLK_F2: return K_GREEN; case SDLK_F3: return K_YELLOW; case SDLK_F4: return K_BLUE;
    case SDLK_PAGEUP: return K_CH_UP; case SDLK_PAGEDOWN: return K_CH_DOWN;
    default: return K_NONE; }
}

/* ---- start ---- */
void plat_pre_init(const char *appId) { (void)appId;
  /* text as UTF-8 (Android 5 and 6 print in the "C" locale byte by byte and stop a line at its first non-ASCII
     character: "[mem] app 79 MB (swap 0) · ..." ended at the dot, the rest of the line and its newline lost) */
  setlocale(LC_CTYPE, "C.UTF-8");
  if (!pipe(logPipe)) { dup2(logPipe[1], 2); setvbuf(stderr, NULL, _IOLBF, 0); pthread_t t; if (!pthread_create(&t, NULL, log_thread, NULL)) pthread_detach(t); }
  char files[900] = "", cache[900] = "", libs[900] = "", tmp[1000];
  info(ATV_FILES, files, sizeof files); info(ATV_CACHE, cache, sizeof cache); info(ATV_LIBDIR, libs, sizeof libs);
  snprintf(assetDir, sizeof assetDir, "%s/assets", files); snprintf(dataDir, sizeof dataDir, "%s/data", files);
  { char cmd[1100]; snprintf(cmd, sizeof cmd, "%s", dataDir); mkdir(cmd, 0700); }
  { char p[1100]; snprintf(p, sizeof p, "%s/laststart.txt", dataDir); atvStartFd = open(p, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0600); }
  applog_start(dataDir); logReady = 1; /* as early as the data folder is known: Java's answers below (the decoders, the screen) are kept too */
  /* the libraries the app loads by name (the torrent engine) sit in the app's native folder: the working folder, so
     the core finds them as it does beside the TV's binary (SDL has no base path on Android) */
  if (libs[0] && chdir(libs)) fprintf(stderr, "[start] cannot enter %s\n", libs);
  snprintf(tmp, sizeof tmp, "%s/tmp", cache); mkdir(tmp, 0700); setenv("SB_P2P_TMP", tmp, 0); setenv("TMPDIR", tmp, 0);   /* no /tmp on Android */
  { char fd[1100]; snprintf(fd, sizeof fd, "%s/fonts", files); if (!access(fd, R_OK)) setenv("SB_FONTDIR", fd, 0); }   /* the system's faces, picked by Java (links into /system/fonts) */
  /* what this box plays, for the best-stream rules (Java asks the decoders, the screen and the sound output) */
  { char caps[200] = ""; info(ATV_CAPS, caps, sizeof caps); if (caps[0]) setenv("SB_TVCAPS", caps, 0); }
  /* for the phone (cast discovery): the box's MAC where Android still gives it, and how the app can be opened and the box woken */
  { char m[32] = "", ex[400] = ""; info(ATV_MAC, m, sizeof m); if (m[0]) setenv("SB_MAC", m, 0); info(ATV_CASTX, ex, sizeof ex); if (ex[0]) setenv("SB_CAST_EXTRA", ex, 0);
    fprintf(stderr, "[cast] box: MAC %s; %s\n", m[0] ? "known" : "not given by this Android", ex); }
  /* a removable drive for the torrent cache: Java keeps <files>/usb pointing at the app's folder on it while it is
     mounted (no permission needed, scoped storage), the core reads it as its drive */
  { char u[1100]; snprintf(u, sizeof u, "%s/usb", files); setenv("SB_USB_DIR", u, 0); }
  /* pictures go to the GPU at the steady rate only: the five-fold burst at rest made a weak box's next move stutter
     (measured on the 1 GB, 2-core emulator: p50 18-26 ms against 16.7) */
  setenv("SB_UPBURST", "0", 0);
  info(ATV_EXIT, lastExit, sizeof lastExit); if (lastExit[0]) fprintf(stderr, "[start] the last session ended: %s\n", lastExit);
  char model[160] = "", rel[40] = ""; info(ATV_MODEL, model, sizeof model); info(ATV_RELEASE, rel, sizeof rel);
  snprintf(devName, sizeof devName, "Android TV %s %s", rel, model);
  fprintf(stderr, "[start] %s, assets %s, data %s\n", devName, assetDir, dataDir);
  SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");
  SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
}
void plat_post_window(SDL_Window *w) { (void)w; }
const char *plat_asset_dir(void) { return assetDir; }
const char *plat_data_dir(void) { return dataDir; }
/* the drawing surface's size is fixed by Java before SDL makes the surface (MainActivity.drawLines: 1280x720 on a box
   found weak, else 1920x1080); the window takes that size (main.c reads it back) */
void plat_window_size(int *w, int *h, Uint32 *flags) { *w = 1920; *h = 1080; *flags = SDL_WINDOW_OPENGL | SDL_WINDOW_FULLSCREEN; }
int plat_text_input(void) { return 0; }   /* a remote, no keyboard: the app draws its own */
const char *plat_device_name(void) { return devName; }
static int meminfo(const char *key) { FILE *f = fopen("/proc/meminfo", "r"); if (!f) return -1; char l[160]; int v = -1; size_t kl = strlen(key);
  while (fgets(l, sizeof l, f)) if (!strncmp(l, key, kl)) { long kb = atol(l + kl); v = (int)(kb / 1024); break; } fclose(f); return v; }
int plat_mem_total_mb(void) { if (getenv("SB_MEMMB")) return atoi(getenv("SB_MEMMB")); int v = meminfo("MemTotal:"); return v > 0 ? v : 0; }
int plat_mem_free_mb(void) { if (getenv("SB_MEMFREEMB")) return atoi(getenv("SB_MEMFREEMB")); return meminfo("MemAvailable:"); }
void plat_thread_background(int nice) { setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), nice); }
/* Home, another app, the screen saver: the activity pauses (SDL stops the loop until it is back) */
int plat_lifecycle(const SDL_Event *e) {
  static int away;
  if (e->type == SDL_APP_WILLENTERBACKGROUND || e->type == SDL_APP_DIDENTERBACKGROUND) { if (!away) { fprintf(stderr, "[life] to the back\n");
      /* a box with little memory: the pictures and idle text pages go with the app to the back (Android kills the biggest
         first; they come back from their small copies when it is in front again) */
      int mb = plat_mem_total_mb(); if (mb > 0 && mb < 1500) { img_release_all(); gfx_pool_clear(); int pg = atlas_trim(1); fprintf(stderr, "[mem] in the back: pictures, kept textures and %d text page(s) let go\n", pg); } }
    away = 1; return -1; }
  if ((e->type == SDL_APP_DIDENTERFOREGROUND) && away) { away = 0; fprintf(stderr, "[life] back in front\n"); return 1; }
  return 0;
}
const char *plat_ui_locale(void) { static char loc[40]; static int asked; if (!asked) { asked = 1; info(ATV_LOCALE, loc, sizeof loc); fprintf(stderr, "[i18n] TV language: %s\n", loc[0] ? loc : "(none)"); } return loc[0] ? loc : NULL; }
char *plat_luna_call(const char *uri, const char *payload, int timeoutMs) { (void)uri; (void)payload; (void)timeoutMs; return NULL; }
/* The one system job the core starts through this call on Android TV: the in-app update. Its request (the package's
   address and SHA-256) goes to Java (Updater: download, check, the system's package installer), and Java's answers come
   back here as the TV's answered, polled once a frame: {"statusText","progress"}, {"returnValue":false,"errorText"},
   {"finished":true}. */
static struct { unsigned long id; PlatLunaCb cb; void *ud; } job;
unsigned long plat_luna_subscribe(const char *uri, const char *payload, PlatLunaCb cb, void *ud) {
  if (!uri || !strstr(uri, "/install")) return 0;
  cJSON *j = cJSON_Parse(payload); const cJSON *u = cJSON_GetObjectItemCaseSensitive(j, "ipkUrl"), *h = cJSON_GetObjectItemCaseSensitive(j, "ipkHash");
  unsigned long id = 0;
  if (cJSON_IsString(u) && cJSON_IsString(h)) {
    JNIEnv *e = atv_env(); jmethodID m = atv_method(e, 0, "updateStart", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (m) { jstring a = (*e)->NewStringUTF(e, u->valuestring), b = (*e)->NewStringUTF(e, h->valuestring);
      (*e)->CallStaticVoidMethod(e, atv_class(e, 0), m, a, b); if (atv_check(e)) { static unsigned long next = 1; id = next++; job.id = id; job.cb = cb; job.ud = ud; }
      (*e)->DeleteLocalRef(e, a); (*e)->DeleteLocalRef(e, b); } }
  cJSON_Delete(j); return id;
}
void plat_luna_cancel(unsigned long handle) { if (handle && handle == job.id) { job.id = 0; atv_call_int("updateCancel", 0); } }
/* once a frame (core/update.c's tick): the update's answers, a link the system opened the app with, memory warnings */
void plat_luna_pump(void) {
  JNIEnv *e = atv_env(); static jmethodID take; if (!take) take = atv_method(e, 0, "take", "(I)Ljava/lang/String;"); if (!take) return;
  char b[2048];
  if (job.id) for (int i = 0; i < 4; i++) { atv_jstr(e, (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, 0), take, (jint)0), b, sizeof b); if (!atv_check(e) || !b[0]) break; if (job.cb) job.cb(b, job.ud); }
  { extern void search_typed(const char *u); for (int i = 0; i < 8; i++) { atv_jstr(e, (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, 0), take, (jint)4), b, sizeof b); if (!atv_check(e) || !b[0]) break; if (SCR == SCR_SEARCH) search_typed(b); } } /* letters from a real keyboard (MainActivity.typedQ) */
  atv_jstr(e, (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, 0), take, (jint)1), b, sizeof b);
  if (atv_check(e) && b[0]) { fprintf(stderr, "[life] opened with a link\n"); if (!atv_home_link(b)) app_open_external(b, NULL); }
  /* Android's memory warnings (onTrimMemory): pictures not on screen are let go at once, and the budget shrinks while
     the box is short (as the core's own guard does from the free memory it reads) */
  atv_jstr(e, (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, 0), take, (jint)2), b, sizeof b);
  if (atv_check(e) && b[0]) { int lvl = atoi(b); img_release_unused(1); gfx_pool_clear(); int pages = atlas_trim(600); /* (the textures kept for reuse, and text pages idle 10 s. The background's own trim is plat_lifecycle's: a level read here arrives after the app is back in front) */
    fprintf(stderr, "[mem] Android asks for memory back (level %d): pictures not on screen, kept textures and %d text page(s) let go\n", lvl, pages); }
  /* the launch's parameters, as LG's launch params reach its core: {"cast":1} when the phone opened the app to play */
  atv_jstr(e, (jstring)(*e)->CallStaticObjectMethod(e, atv_class(e, 0), take, (jint)3), b, sizeof b);
  if (atv_check(e) && b[0]) { fprintf(stderr, "[life] launch params %s\n", b); app_launch_params(b); }
  atv_home_rows_tick();
}
int plat_luna_send(const char *uri, const char *payload) { (void)uri; (void)payload; return 0; }
void plat_pointer_visible(int on) { (void)on; }   /* an air mouse's arrow is the system's: it hides by itself */
int plat_panel_lines(void) { char s[16] = ""; info(ATV_PANEL, s, sizeof s); return atoi(s); }
void plat_mem_self(int *rssMb, int *swapMb) {
  *rssMb = *swapMb = -1; FILE *f = fopen("/proc/self/status", "r"); if (!f) return;
  char line[128]; long kb; while (fgets(line, sizeof line, f)) { if (sscanf(line, "VmRSS: %ld kB", &kb) == 1) *rssMb = (int)(kb / 1024); else if (sscanf(line, "VmSwap: %ld kB", &kb) == 1) *swapMb = (int)(kb / 1024); }
  fclose(f);
}
