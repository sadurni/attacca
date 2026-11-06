/* Development: runs the plugin's logic on a PC against a .bpcr2, to compare with Attacca Desktop.
     gcc -O2 -I. -o host_test host_test.c && ./host_test LIB.bpcr2 SEED COUNT [same_mood] [TO]
   SEED and TO are device paths ("/device/path/of/song") or record numbers ("#123"); without TO
   the bridge goes to a song taken at random.
   Prints the fitted filters and writes Playlists/seed_*.m3u8 (from the seed) and
   Playlists/fitted_*.m3u8 (from the seed after fitting); then, with the filters reset, the other
   lists (energy curves, sampler, opposite, for a set time, albums like, bridge) and what each one
   is like. */
#include "plugin.h"

static char seed_path[MAX_PATH];
static struct mp3entry current;
static long tick;
static struct user_settings settings = { "english" };
static char *buffer;

static size_t my_strlcpy(char *d, const char *s, size_t n) { size_t l = strlen(s); if (n) { size_t c = l < n - 1 ? l : n - 1; memcpy(d, s, c); d[c] = 0; } return l; }
static size_t my_strlcat(char *d, const char *s, size_t n) { size_t l = strlen(d); return l + my_strlcpy(d + l, s, n > l ? n - l : 0); }
static void splash(int t, const char *s) { (void)t; printf("[splash] %s\n", s); }
static void splashf(int t, const char *f, ...) { va_list a; (void)t; va_start(a, f); printf("[splash] "); vprintf(f, a); printf("\n"); va_end(a); }
static void splash_progress(int c, int t, const char *f, ...) { (void)c; (void)t; (void)f; }
static int abort_no(int t) { (void)t; return 0; }
static void *get_buffer(size_t *s) { *s = 4 << 20; return buffer; }
static void *get_audio(size_t *s) { static char *a; if (!a) a = malloc(64 << 20); *s = 64 << 20; return a; }
static void release_audio(void) {}
static struct mp3entry *cur(void) { return &current; }
static bool dir_exists(const char *p) { struct stat st; return stat(p + (p[0] == '/'), &st) == 0; }
static int my_mkdir(const char *p) { return mkdir(p + (p[0] == '/'), 0777); }
static int my_open(const char *p, int f, ...) { return open(p[0] == '/' && !strncmp(p, "/Playlists", 10) ? p + 1 : p, f, 0666); }
static int fdprintf(int fd, const char *f, ...) { va_list a; va_start(a, f); int r = vdprintf(fd, f, a); va_end(a); return r; }
static bool no(void) { return false; }
static int pl_create(const char *d, const char *n) { (void)d; (void)n; return -1; }
static void pl_start(int a, unsigned long b, unsigned long c) { (void)a; (void)b; (void)c; }
static int pl_amount(void) { return 0; }
static int pl_info(void *p, int i, struct playlist_track_info *t) { (void)p; (void)i; (void)t; return -1; }
static void sl_init(struct simplelist_info *i, char *t, int n, void *d) { (void)d; i->title = t; i->count = n; i->selection = 0; }
static bool sl_show(struct simplelist_info *i) { i->selection = -1; return false; }
static bool set_int(const char *a, const char *b, int c, int *d, void *e, int f, int g, int h, const char *(*fmt)(char *, size_t, int, const char *)) { (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; (void)g; (void)h; (void)fmt; return false; }
static int my_rand(void) { return rand(); }
static void my_srand(unsigned s) { srand(s); }

static const struct plugin_api api = {
    lseek, read, (int (*)(const char *, int, ...))my_open, close, memcpy, memcmp, memset, my_strlcpy, my_strlcat,
    strcmp, strncmp, strlen, (char *(*)(const char *, int))strrchr, snprintf, splash, splashf, splash_progress,
    abort_no, qsort, my_rand, my_srand, get_buffer, get_audio, release_audio, cur, dir_exists, my_mkdir, fdprintf,
    no, pl_create, pl_start, pl_amount, pl_info, sl_init, sl_show, set_int, &tick, &settings,
};
const struct plugin_api *rb = &api;

#define HOST_TEST
#define plugin_start plugin_start_unused
#include "../attaccaplg.c"
#undef plugin_start

/* What a finished list is like: size, artists, links (read again from the library), artist-rule
   breaks, minutes, energy along it, albums (folders), first and last song. */
static void host_list(const struct song *s, int n, const char *name, uint32_t avg)
{
    static uint8_t seen[65536];
    static uint32_t folders[65536];
    static struct song full[65536];
    struct record r;
    char title[200], path[MAX_PATH];
    int artists = 0, nf = 0;
    long seconds = 0;
    memset(seen, 0, sizeof(seen));
    printf("== list %s: %d songs, link given %.3f\n", name, n, avg / 65536.0);
    if (n <= 0)
        return;
    for (int i = 0; i < n && i < 65536; i++) {
        if (!read_record(s[i].rec, &r))
            continue;
        full[i].rec = s[i].rec;
        full[i].artist = r.artist;
        full[i].flags = r.flags;
        full[i].edges = NULL;
        if (r.edge != NONE32) {
            full[i].edges = malloc(2 * sizeof(struct edge));
            read_at(H.edgeoff + r.edge * 2 * sizeof(struct edge), full[i].edges, 2 * sizeof(struct edge));
        }
        if (r.artist != NONE16 && !seen[r.artist]++)
            artists++;
        seconds += r.duration;
        uint32_t f = folder_hash(get_str(r.path, path, sizeof(path)));
        int k = 0;
        while (k < nf && folders[k] != f)
            k++;
        if (k == nf && nf < 65536)
            folders[nf++] = f;
    }
    uint64_t sum = 0;
    for (int i = 1; i < n; i++)
        sum += transition(&full[i - 1], &full[i]);
    printf("   distinct artists %d, mean link %.3f, artist rule (spacing %d) broken %d, %.1f minutes, %d folders\n",
           artists, n > 1 ? sum / (double)(n - 1) / 65536 : 0.0, spacing(), broken_rule(full, 0, n, spacing()),
           seconds / 60.0, nf);
    printf("   energy along (%% at each tenth):");
    for (int d = 0; d <= 10; d++) {
        int i = (n - 1) * d / 10;
        if (read_record(s[i].rec, &r))
            printf(" %d", (r.energy + 50) / 100);
    }
    printf("\n");
    read_record(s[0].rec, &r);
    printf("   first: %s", get_str(r.title, title, sizeof(title)));
    printf(" [%s]\n", get_str(r.path, path, sizeof(path)));
    read_record(s[n - 1].rec, &r);
    printf("   last:  %s", get_str(r.title, title, sizeof(title)));
    printf(" [%s]\n", get_str(r.path, path, sizeof(path)));
    for (int i = 0; i < n && i < 65536; i++)
        free(full[i].edges);
}

static void reset_filters(void)
{
    cfg.year_from = cfg.year_to = cfg.bpm_min = cfg.bpm_max = cfg.voice = 0;
    cfg.e_min = cfg.p_min = 0;
    cfg.e_max = cfg.p_max = 100;
    cfg.min_minutes = 1;
    cfg.max_minutes = 20;
}

/** A record from "#N" or a device path, as the plugin finds it. */
static int record_of(const char *arg, char *device_path)
{
    struct record r;
    char rel[MAX_PATH];
    if (arg[0] == '#') {
        int rec = atoi(arg + 1);
        if (!read_record(rec, &r))
            return -1;
        snprintf(device_path, MAX_PATH, "%s/%s", base, get_str(r.path, rel, sizeof(rel)));
        return rec;
    }
    strncpy(device_path, arg, MAX_PATH - 1);
    load_hashes();
    return find_path(arg);
}

/* The automatic crossfade's joins: from the seed through the next songs of the library (its own
   album first, which plays on without a blend), and from the seed into songs of other albums. */
static void host_join(int a, int b)
{
    struct record ra, rb_;
    struct ends ea, eb;
    char ta[80], tb[80];
    if (!get_record(a, &ra) || !get_record(b, &rb_)) return;
    get_str(ra.title, ta, sizeof(ta));
    get_str(rb_.title, tb, sizeof(tb));
    if (!read_ends(a, &ea) || !read_ends(b, &eb)) { printf("  %s -> %s: no ends\n", ta, tb); return; }
    bool gapless = ra.album == rb_.album && ra.album != NONE32 && b == a + 1;
    struct xf_values v;
    plan_join(&ea, &eb, gapless, 10, &v);
    printf("  %-28.28s -> %-28.28s end %d fade %.1fs tail %.1fs | next start %.1fs soft %d | out %d+%d in %d+%d mix %d%s\n",
           ta, tb, ea.ending, ea.fade / 100.0, ea.tail / 100.0, eb.start / 100.0, eb.soft,
           v.out_delay, v.out_duration, v.in_delay, v.in_duration, v.mix, gapless ? " (gapless)" : "");
    if (!gapless && wants_jump(&ea, 10)) {
        long before = plan_jump(&ea, &eb, 10, &v);
        printf("      jump %.1fs before the end of the sound: out %d+%d in %d+%d\n", before / 1000.0,
               v.out_delay, v.out_duration, v.in_delay, v.in_duration);
    }
}

int main(int argc, char **argv)
{
    if (argc < 4) return 2;
    buffer = malloc(4 << 20);
    pbuf.base = (uint8_t *)buffer;
    pbuf.size = 4 << 20;
    rwin.buf = palloc(WINDOW);
    ewin.buf = palloc(WINDOW);
    texts = texts_en;
    const char *err = open_db(argv[1]);
    if (err) { printf("%s\n", err); return 1; }
    srand(1);
    int seed = record_of(argv[2], seed_path);
    strcpy(current.path, seed_path);
    cfg.count = atoi(argv[3]);
    cfg.same_mood = argc > 4 && !strcmp(argv[4], "same_mood");
    cfg.same_family = 0;
    cfg.play = 0;
    printf("library: %u songs, base %s, seed #%d %s\n", H.n, base, seed, seed_path);
    from_current();
    fit_to_current();
    printf("fitted: years %d-%d, BPM %d-%d, energy %d-%d, positivity %d-%d, voice %d\n",
           cfg.year_from, cfg.year_to, cfg.bpm_min, cfg.bpm_max, cfg.e_min, cfg.e_max, cfg.p_min, cfg.p_max, cfg.voice);
    from_current();
    if (seed < 0)
        return 0;
    printf("# automatic crossfade joins (blends of 10 s)\n");
    for (int i = 0; i < 8 && seed + i + 1 < (int)H.n; i++)
        host_join(seed + i, seed + i + 1);
    for (int i = 1; i <= 8; i++)
        host_join(seed, (seed + i * 97) % H.n);
    printf("# songs with long silence after their sound\n");
    int shown = 0;
    for (uint32_t i = 0; i + 1 < H.n && shown < 6; i++) {
        struct ends e;
        if (read_ends(i, &e) && e.tail >= 300) { host_join(i, (i + 500) % H.n); shown++; }
    }

    reset_filters();
    printf("\n######## other lists (filters reset, spacing %d)\n", spacing());
    printf("\n# energy up and down (random start)\n");
    energy_curve(CURVE_RISE_FALL, -1);
    printf("\n# warming up (random start)\n");
    energy_curve(CURVE_WARM_UP, -1);
    printf("\n# winding down (from the seed)\n");
    energy_curve(CURVE_WIND_DOWN, seed);
    printf("\n# sampler of sound families (%u families in the library)\n", dict_n[D_FAMILY]);
    sampler();
    printf("\n# opposite of the seed\n");
    opposite(seed);
    static const int minutes[] = { 30, 60, 90 };
    for (int k = 0; k < 3; k++) {
        printf("\n# for %d minutes from the seed\n", minutes[k]);
        for_time(seed, minutes[k]);
    }
    printf("\n# albums like the seed's\n");
    albums_like(seed);
    char to_path[MAX_PATH];
    int to = argc > 5 ? record_of(argv[5], to_path) : (int)(rand() % H.n);
    printf("\n# bridge from #%d to #%d\n", seed, to);
    if (to >= 0)
        bridge(seed, to);
    printf("\n# only included: energy up and down, sampler\n");
    cfg.only_inc = 1;
    energy_curve(CURVE_RISE_FALL, -1);
    sampler();
    return 0;
}
