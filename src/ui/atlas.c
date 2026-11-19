#include "ui/atlas.h"
#include "ui/gfx.h"
#include <stdlib.h>
#include <string.h>

#define MAX_PAGES 24
static int maxPages = 10; /* (6 on a box with little memory: atlas_low_memory; 24 for pages drawn at 4K: atlas_hidpi) */
#define MAX_SHELVES 96
typedef struct { int y, h, x; } Shelf;
typedef struct { GLuint tex; int nshelf, top, live; unsigned gen, lastUse; int sx, sy; Shelf shelf[MAX_SHELVES]; } Page;
static Page pages[MAX_PAGES]; static int npages; static unsigned frameNo = 1;
/* pieces not yet sent to their page: they go up together, just before the first draw that reads that page (gfx's
   pre-draw hook). A tiled GPU (Mali, PowerVR: most TV boxes) still reading the page for earlier draws copies the whole
   1 MB page on every write in between; written once before the next draw, it copies it once, not once a line */
typedef struct { int page, x, y, w, h; unsigned char *px; } Piece;
static Piece *pend; static int npend, cappend, deferOn = -1;
static void flush_page(int page) {
  int k = 0;
  for (int i = 0; i < npend; i++) { Piece *q = &pend[i]; if (q->page == page) { gfx_tex_upload_raw(pages[page].tex, q->px, q->x, q->y, q->w, q->h, 1); free(q->px); } else pend[k++] = *q; }
  npend = k; }
static void drop_page(int page) { int k = 0; for (int i = 0; i < npend; i++) { if (pend[i].page == page) free(pend[i].px); else pend[k++] = pend[i]; } npend = k; } /* (the page starts over: what was waiting for it would land on new pieces) */
static void predraw(GLuint tex) { if (!npend) return; int pg = atlas_page_of(tex); if (pg >= 0) flush_page(pg); }
static void put_px(int page, unsigned char *px, int x, int y, int w, int h) { /* px is owned from here */
  if (deferOn < 0) {
#ifdef SB_ANDROIDTV
    deferOn = getenv("SB_ATLASNOW") == NULL; /* (SB_ATLASNOW: each piece sent at once, as before) */
#else
    deferOn = getenv("SB_ATLASLATER") != NULL; /* (other platforms: as before unless asked) */
#endif
    if (deferOn) gfx_set_predraw(predraw); }
  if (deferOn && (npend < cappend || (pend = realloc(pend, sizeof *pend * (size_t)(cappend = cappend ? cappend * 2 : 64))))) {
    pend[npend++] = (Piece){ page, x, y, w, h, px }; return; }
  gfx_tex_update(pages[page].tex, px, x, y, w, h, 1); free(px); }

void atlas_frame(void) { frameNo++; }
void atlas_low_memory(void) { maxPages = 6; }
void atlas_hidpi(void) { maxPages = MAX_PAGES; } /* (text at twice the size takes four times the room: Android TV's 4K pages, a 3 GB+ box) */
/* pages not drawn for idleFrames let go (all but the first): their lines are made again when next shown (their slots
   no longer valid). For Android's memory warnings and the background */
int atlas_trim(unsigned idleFrames) {
  int n = 0;
  for (int i = 1; i < npages; i++) { Page *p = &pages[i]; if (!p->tex || frameNo - p->lastUse < idleFrames) continue;
    drop_page(i); gfx_tex_free(p->tex); p->tex = 0; p->gen++; p->nshelf = 0; p->top = 0; p->live = 0; n++; }
  return n; }
int atlas_pages(void) { return npages; }
GLuint atlas_tex(int page) { return page >= 0 && page < npages ? pages[page].tex : 0; }
int atlas_page_of(GLuint tex) { if (!tex) return -1; for (int i = 0; i < npages; i++) if (pages[i].tex == tex) return i; return -1; }

static int fit(Page *p, int w, int h, int *ox, int *oy) {
  for (int i = 0; i < p->nshelf; i++) { Shelf *sh = &p->shelf[i]; if (sh->h >= h && sh->h <= h + h / 4 + 2 && sh->x + w <= ATLAS_W) { *ox = sh->x; *oy = sh->y; sh->x += w; return 1; } }
  if (p->nshelf >= MAX_SHELVES || p->top + h > ATLAS_H) return 0;
  Shelf *sh = &p->shelf[p->nshelf++]; sh->y = p->top; sh->h = h; sh->x = w; p->top += h; *ox = 0; *oy = sh->y; return 1;
}
/* every page keeps a 6x6 block that is fully on: plain fills and the straight parts of shapes sample its middle */
static void reset(Page *p, int clear) {
  p->nshelf = 0; p->top = 0; p->live = 0; p->gen++;
  (void)clear; /* nothing to wipe: every piece brings its own empty border */
  int pg = (int)(p - pages); drop_page(pg); if (clear) gfx_tex_content_changed(); /* (a page used before: what the last frame drew from it is gone) */
  int x, y; fit(p, 6, 6, &x, &y); unsigned char *on = malloc(36); if (on) { memset(on, 255, 36); put_px(pg, on, x, y, 6, 6); } p->sx = x + 3; p->sy = y + 3;
}
static int new_page(void) {
  for (int i = 0; i < npages; i++) if (!pages[i].tex) { Page *q = &pages[i]; unsigned char *z = calloc((size_t)ATLAS_W * ATLAS_H, 1); q->tex = gfx_tex_create(z, ATLAS_W, ATLAS_H, 1, 1); free(z); if (!q->tex) return -1; reset(q, 0); return i; } /* (a page let go earlier: made again in its place, its number kept) */
  if (npages >= maxPages) return -1;
  Page *p = &pages[npages]; unsigned char *z = calloc((size_t)ATLAS_W * ATLAS_H, 1); p->tex = gfx_tex_create(z, ATLAS_W, ATLAS_H, 1, 1); free(z);
  if (!p->tex) return -1;
  reset(p, 0); return npages++;
}
int atlas_put(const unsigned char *alpha, int w, int h, int preferPage, int keep, AtlasSlot *out) {
  out->page = -1;
  int pw = w + 2, ph = h + 2, ox, oy, at = -1;   /* 1 px of empty space around each piece: no bleeding under linear filtering */
  if (pw > ATLAS_W || ph > ATLAS_H) return 0;
  if (!npages) new_page();
  /* a page the GPU has not read for two frames first: written now, no draw still waiting on it, so the driver copies
     nothing (a page drawn this frame or the last is copied whole by a tiled GPU's driver when written) */
  for (int pass = 0; pass < 2 && at < 0; pass++) {
    int cool0 = pass == 0; Page *pp = preferPage >= 0 && preferPage < npages ? &pages[preferPage] : NULL;
    if (pp && pp->tex && (!cool0 || frameNo - pp->lastUse >= 2) && fit(pp, pw, ph, &ox, &oy)) { at = preferPage; break; }
    for (int i = 0; at < 0 && i < npages; i++) if (i != preferPage && pages[i].tex && (!cool0 || frameNo - pages[i].lastUse >= 2) && fit(&pages[i], pw, ph, &ox, &oy)) at = i; }
  if (at < 0) { int n = new_page(); if (n >= 0 && fit(&pages[n], pw, ph, &ox, &oy)) at = n; }
  if (at < 0) { /* all full: the page drawn least recently starts over, unless it was drawn this frame */
    int old = -1; for (int i = 0; i < npages; i++) if (pages[i].tex && pages[i].lastUse != frameNo && (old < 0 || pages[i].lastUse < pages[old].lastUse)) old = i;
    if (old < 0) return 0;
    reset(&pages[old], 1);
    if (!fit(&pages[old], pw, ph, &ox, &oy)) return 0;
    at = old;
  }
  Page *p = &pages[at];
  { /* the piece with its 1 px empty border: a reused page needs no clearing (a 1 MB upload was a stall) */
    unsigned char *pad = calloc((size_t)pw * ph, 1); if (!pad) return 0; for (int y = 0; y < h; y++) memcpy(pad + (size_t)(y + 1) * pw + 1, alpha + (size_t)y * w, w);
    put_px(at, pad, ox, oy, pw, ph); } /* (into an unused part of the page: what earlier frames drew from it is unchanged) */
  if (keep) p->live++;
  p->lastUse = frameNo;
  out->page = at; out->gen = p->gen; out->x = ox + 1; out->y = oy + 1; out->w = w; out->h = h;
  return 1;
}
int atlas_valid(const AtlasSlot *s) { return s->page >= 0 && s->page < npages && pages[s->page].gen == s->gen; }
void atlas_touch(const AtlasSlot *s) { if (atlas_valid(s)) pages[s->page].lastUse = frameNo; }
void atlas_release(const AtlasSlot *s) {
  if (!atlas_valid(s)) return;
  Page *p = &pages[s->page];
  if (--p->live <= 0 && p->lastUse != frameNo) reset(p, 1); /* nothing kept on it: the page is free again */
}
void atlas_uv(const AtlasSlot *s, float *u0, float *v0, float *u1, float *v1) {
  *u0 = (float)s->x / ATLAS_W; *v0 = (float)s->y / ATLAS_H; *u1 = (float)(s->x + s->w) / ATLAS_W; *v1 = (float)(s->y + s->h) / ATLAS_H;
}
void atlas_solid_uv(int page, float *u, float *v) { Page *p = &pages[page < 0 || page >= npages ? 0 : page]; *u = (p->sx + 0.5f) / ATLAS_W; *v = (p->sy + 0.5f) / ATLAS_H; }
