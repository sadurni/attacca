/* Development: the bits of Rockbox's plugin API that attaccaplg.c uses, on top of the C library,
   so the plugin's logic runs on a PC (see host_test.c). Not part of the plugin. */
#ifndef HOST_PLUGIN_H
#define HOST_PLUGIN_H
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

#define MAX_PATH 260
#define HZ 100
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#define TIME_AFTER(a, b) ((long)(b) - (long)(a) < 0)
#define TIMEOUT_NOBLOCK 0
#define UNIT_INT 0
#define ROCKBOX_DIR "/.rockbox"
enum plugin_status { PLUGIN_OK, PLUGIN_GOTO_WPS };

struct mp3entry { char path[MAX_PATH]; };
struct playlist_track_info { char filename[MAX_PATH]; };
struct simplelist_info { const char *title; int count, selection; const char *(*get_name)(int, void *, char *, size_t); };
struct user_settings { char lang_file[32]; };

#define TYPE_INT 1
#define TYPE_STRING 2
struct configdata { int type, min, max; union { int *int_p; char *string; }; const char *name; void *x; };
static inline int configfile_load(const char *f, struct configdata *c, int n, int v) { (void)f; (void)c; (void)n; (void)v; return 0; }
static inline int configfile_save(const char *f, struct configdata *c, int n, int v) { (void)f; (void)c; (void)n; (void)v; return 0; }

struct plugin_api {
    off_t (*lseek)(int, off_t, int);
    ssize_t (*read)(int, void *, size_t);
    int (*open)(const char *, int, ...);
    int (*close)(int);
    void *(*memcpy)(void *, const void *, size_t);
    int (*memcmp)(const void *, const void *, size_t);
    void *(*memset)(void *, int, size_t);
    size_t (*strlcpy)(char *, const char *, size_t);
    size_t (*strlcat)(char *, const char *, size_t);
    int (*strcmp)(const char *, const char *);
    int (*strncmp)(const char *, const char *, size_t);
    size_t (*strlen)(const char *);
    char *(*strrchr)(const char *, int);
    int (*snprintf)(char *, size_t, const char *, ...);
    void (*splash)(int, const char *);
    void (*splashf)(int, const char *, ...);
    void (*splash_progress)(int, int, const char *, ...);
    int (*action_userabort)(int);
    void (*qsort)(void *, size_t, size_t, int (*)(const void *, const void *));
    int (*rand)(void);
    void (*srand)(unsigned);
    void *(*plugin_get_buffer)(size_t *);
    void *(*plugin_get_audio_buffer)(size_t *);
    void (*plugin_release_audio_buffer)(void);
    struct mp3entry *(*audio_current_track)(void);
    bool (*dir_exists)(const char *);
    int (*mkdir)(const char *);
    int (*fdprintf)(int, const char *, ...);
    bool (*warn_on_pl_erase)(void);
    int (*playlist_create)(const char *, const char *);
    void (*playlist_start)(int, unsigned long, unsigned long);
    int (*playlist_amount)(void);
    int (*playlist_get_track_info)(void *, int, struct playlist_track_info *);
    void (*simplelist_info_init)(struct simplelist_info *, char *, int, void *);
    bool (*simplelist_show_list)(struct simplelist_info *);
    bool (*set_int)(const char *, const char *, int, int *, void *, int, int, int, const char *(*)(char *, size_t, int, const char *));
    long *current_tick;
    struct user_settings *global_settings;
};
extern const struct plugin_api *rb;
#endif
