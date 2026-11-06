#!/bin/bash
# Builds attaccaplg.rock against a Rockbox source tree (run inside Linux / WSL).
#   ./build.sh sim      → simulator (build-sim), installs it in its simdisk
#   ./build.sh target   → Shanling Q1 (build-q1), leaves the .rock in out/
#   ./build.sh android  → Rockbox for Android on ARMv6 / Android 2.3 (build-android),
#                         leaves out/android/attaccaplg.rock. Needs ANDROID_NDK_PATH (NDK r10e,
#                         default ~/rbdev/android-ndk-r10e).
# Environment: RBROOT (Rockbox git, default ~/rbdev/rockbox), RBTARGET (default shanlingq1),
# PATH must include the Rockbox toolchain for target builds (~/rbdev/x-tools/bin).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
RBROOT="${RBROOT:-$HOME/rbdev/rockbox}"
RBTARGET="${RBTARGET:-shanlingq1}"
MODE="${1:-sim}"
export PATH="$HOME/rbdev/x-tools/bin:$PATH"

# Plugin source, registered as an "apps" plugin that also opens .bpcr2 files.
ln -sf "$HERE/attaccaplg.c" "$RBROOT/apps/plugins/attaccaplg.c"
grep -qx "attaccaplg.c" "$RBROOT/apps/plugins/SOURCES" || sed -i '0,/^chessclock.c$/s//attaccaplg.c\nchessclock.c/' "$RBROOT/apps/plugins/SOURCES"
# Touchscreen app builds (Android) use their own list.
grep -qx "attaccaplg.c" "$RBROOT/apps/plugins/SOURCES.app_build" || sed -i '0,/^chopper.c$/s//attaccaplg.c\nchopper.c/' "$RBROOT/apps/plugins/SOURCES.app_build"
# Rockbox for touchscreen phones builds only 18 plugins: add every other one that builds there
# (the rest, mostly games, have no keymap for Android; text is viewed with view_text there).
APP_EXTRA_SOURCES="text_editor 2048 alarmclock amaze announce_status bounce codebuster countdown_timer
    cue_playlist dart_scorer db_folder_select demystify dice dict disktidy file_picker fire fireworks
    jackpot keybox keyremap lastfm_scrobbler lastfm_scrobbler_viewer logo lrcplayer main_menu_config
    matrix maze mazezam md5sum metronome mosaique open_plugins otp periodic_table pitch_screen plasma
    playing_time rb_info resistor robotfindskitten rockblox1d rocklife settings_dumper shopper snow
    speedread starfield stats wav2wv wavview windows_lnk"
for p in $APP_EXTRA_SOURCES; do
    grep -qx "$p.c" "$RBROOT/apps/plugins/SOURCES.app_build" || sed -i "0,/^chopper.c\$/s//chopper.c\n$p.c/" "$RBROOT/apps/plugins/SOURCES.app_build"
done
for d in shortcuts goban xrick; do
    grep -qx "$d" "$RBROOT/apps/plugins/SUBDIRS.app_build" || sed -i "0,/^lua_scripts\$/s//lua_scripts\n$d/" "$RBROOT/apps/plugins/SUBDIRS.app_build"
done
grep -q "^attaccaplg," "$RBROOT/apps/plugins/CATEGORIES" || echo "attaccaplg,apps" >> "$RBROOT/apps/plugins/CATEGORIES"
grep -q "^bpcr2," "$RBROOT/apps/plugins/viewers.config" || echo "bpcr2,apps/attaccaplg,-" >> "$RBROOT/apps/plugins/viewers.config"

# "Open with…" on a song opens the plugin with that song as the seed; when it starts playing the
# list it asks for the WPS, which Rockbox ignored from "Open with…" (back to the file browser).
grep -q 'ret == PLUGIN_GOTO_WPS) onplay_result = ONPLAY_START_PLAY; /\* attaccaplg \*/' "$RBROOT/apps/onplay.c" \
    || sed -i '/int ret = filetype_list_viewers(selected_file.path);/{n;n;s|$|\n    else if (ret == PLUGIN_GOTO_WPS) onplay_result = ONPLAY_START_PLAY; /* attaccaplg */|}' "$RBROOT/apps/onplay.c"

# Bookmarks are written through "/<name>.tmp", which breaks names under "<HOME>" (Rockbox as an
# app: lists in <HOME>/Playlists, "Could not save bookmark"): no slash before those.
grep -q '/\* attaccaplg: <HOME> \*/' "$RBROOT/apps/bookmark.c" \
    || sed -i 's|open_pathfmt(buf, bufsz, oflags, "/%s.tmp", filename);|open_pathfmt(buf, bufsz, oflags, "%s%s.tmp", *filename == '\''<'\'' ? "" : "/", filename); /* attaccaplg: <HOME> */|' "$RBROOT/apps/bookmark.c"

CONFIGURE_ARGS=()
if [ "$MODE" = sim ]; then
    BUILD="$(dirname "$RBROOT")/build-sim"
    TYPE=s
elif [ "$MODE" = android ]; then
    # The upstream Android build targets ARMv7 and API 19 (Android 4.4); older phones such as the
    # Galaxy Ace (ARMv6, Android 2.3) need ARMv6 code linked against API 9.
    export ANDROID_NDK_PATH="${ANDROID_NDK_PATH:-$HOME/rbdev/android-ndk-r10e}"
    export ANDROID_SDK_PATH="${ANDROID_SDK_PATH:-$HOME/rbdev/no-android-sdk}" # not needed for plugins
    grep -q 'androidcc 9 armeabi "-march=armv6' "$RBROOT/tools/configure" \
        || sed -i 's|androidcc 19 armeabi "-march=armv7-a -mtune=cortex-a9 -mfloat-abi=softfp"|androidcc 9 armeabi "-march=armv6 -mtune=arm1176jzf-s -mfloat-abi=softfp"|' \
            "$RBROOT/tools/configure"
    # Android 2.3 refuses an APK signed with MD5withRSA (Rockbox's default): SHA-1 instead.
    sed -i 's/-sigalg MD5withRSA/-sigalg SHA1withRSA/g' "$RBROOT/android/android.make"
    # The plugins come out of libmisc.so only when it is newer than those already there, but
    # Android 2.3 gives it the date inside the APK (1980): an update kept the first plugins. The
    # APK's own date (when it was installed) counts too.
    grep -q 'getPackageCodePath()).lastModified()' "$RBROOT/android/src/org/rockbox/RockboxService.java" \
        || sed -i 's#|| (libMisc.lastModified() > arbitraryFile.lastModified());#|| (libMisc.lastModified() > arbitraryFile.lastModified())\n                        || (new File(getPackageCodePath()).lastModified() > arbitraryFile.lastModified());#' \
            "$RBROOT/android/src/org/rockbox/RockboxService.java"
    # …and once copied, the mark file must not stay at 1980 (that copied them at every start).
    grep -q 'Math.max(libMisc.lastModified(), new File(getPackageCodePath()).lastModified())' "$RBROOT/android/src/org/rockbox/RockboxService.java" \
        || sed -i 's#arbitraryFile.setLastModified(libMisc.lastModified());#arbitraryFile.setLastModified(Math.max(libMisc.lastModified(), new File(getPackageCodePath()).lastModified()));#' \
            "$RBROOT/android/src/org/rockbox/RockboxService.java"
    BUILD="$(dirname "$RBROOT")/build-android"
    RBTARGET=android
    TYPE=n
    # 16 MB instead of the port's 8: with 8 only about 2 MB are left for sound, too little for
    # long crossfades (the plugin's automatic ones).
    CONFIGURE_ARGS=(--lcdwidth="${LCD_WIDTH:-320}" --lcdheight="${LCD_HEIGHT:-480}")
else
    BUILD="$(dirname "$RBROOT")/build-q1"
    TYPE=n
fi
mkdir -p "$BUILD"
cd "$BUILD"
[ -f Makefile ] || "$RBROOT/tools/configure" --target="$RBTARGET" --type="$TYPE" "${CONFIGURE_ARGS[@]}" > configure.log 2>&1 \
    || { tail -20 configure.log; exit 1; }
# Rockbox's configure keeps 8 MB for Android whatever --ram says: set it in the Makefile.
if [ "$MODE" = android ] && ! grep -qx "export MEMORYSIZE=${ANDROID_RAM:-16}" Makefile; then
    sed -i "s/^export MEMORYSIZE=.*/export MEMORYSIZE=${ANDROID_RAM:-16}/" Makefile
    make clean > /dev/null
fi
make -j"$(nproc)" > make-plugins.log 2>&1 || { tail -30 make-plugins.log; exit 1; }
grep -E "warning|error" make-plugins.log | grep attaccaplg || true
mkdir -p "$HERE/out"
if [ "$MODE" = sim ]; then
    make install > /dev/null
    cp apps/plugins/attaccaplg.rock "$HERE/out/attaccaplg-sim.rock"
elif [ "$MODE" = android ]; then
    mkdir -p "$HERE/out/android"
    cp apps/plugins/attaccaplg.rock "$HERE/out/android/attaccaplg.rock"
else
    cp apps/plugins/attaccaplg.rock "$HERE/out/attaccaplg.rock"
fi
ls -la "$HERE/out"
