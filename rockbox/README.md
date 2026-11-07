# Attacca for Rockbox

[Rockbox](https://www.rockbox.org) plugin that makes **chained** playlists on the player itself
(the end of each song flows into the start of the next one), with the same rules as Attacca and
Attacca Desktop. It reads the library exported by Attacca Desktop: `attaccaplg.bpcr2`. Nothing is measured
on the player.

## Features

- **Any song as the seed, without playing it first**: in the file browser, the song's context menu
  → *Open with…* → *attaccaplg* opens the plugin on that song (its title shows for a moment), and
  everything below that uses "the current song" takes that one instead; when the list starts
  playing, the player screen opens. From the database browser, *Show in Files* first leads to the
  file. The library is the usual one (the last opened, or `attaccaplg.bpcr2` at the root or in
  `.rockbox`).
- **From the current song**: the songs closest to the one playing (by default of the same mood,
  where a song between two moods counts as either;
  same sound family too if asked), with 10 % extra candidates so the songs the artist spacing could
  not place don't pile up at the end, chained from it.
- **Fit the filters to the current song**: years, BPM, energy, positivity and voice set around the
  song's, as tight as they can be while leaving 10 times the songs asked for (at least twice). They
  stay in **More filters**, to change by hand.
- **Generate by filters**: mood, sound family, genre tag, artist, and the **more filters**: years,
  BPM, energy and positivity (percent of the library), voice or instrumental, only included (with
  a library that carries several inclusion lists: **Music**, which of them lists keep to, or
  Everything), and
  song length (minutes from / to: 1 to 20 unless changed; "off" for no limit). Songs of unknown
  length always pass.
- **Reorder current playlist**: chains the playlist loaded in Rockbox, starting with the current
  song (or the first one).
- **Other lists**, as Attacca's, all chained end into start: energy that rises to a peak and
  calms down again, warming up, winding down (each song also near the one before; they start at
  random, or with the current song when set), a sampler of the most typical songs of every sound
  family, the opposite of the current song, a list from the current song lasting about the
  minutes set, whole albums like the current song's album (each folder an album, in path order),
  a bridge from the current song to another chosen by artist and title (or from that one to the
  current song), and a list from a **random song of a mood** (or of any; its second mood counts),
  as Attacca's random list. The energy curves keep
  every song's description in memory (about 7 MB for 21,000 songs): they stop playback like
  "Generate by filters".
- Number of songs (150 unless changed; a setting already saved on the player keeps its value),
  artist spacing (default: the one Attacca Desktop exported with).
- The result is saved as `/Playlists/<name>.m3u8` and, if **When done: Play** is set, it starts
  playing right away.
- In the languages of Attacca (English, Spanish, Catalan, Galician, Basque, Portuguese, Brazilian
  Portuguese, French, Italian, German, Dutch, Swedish, Polish, Romanian, Russian, Ukrainian, Greek,
  Turkish, Japanese, Korean, Simplified and Traditional Chinese), following the Rockbox language
  (English for the others; the font must have the language's letters).

- **Library**: any number of libraries can be kept on the player under their own names (anything
  ending in `.bpcr2`, at the root or in `.rockbox`); this menu item chooses which one the plugin
  uses, and it is remembered.

## Automatic crossfade

**Automatic crossfade: Yes** in the plugin's menu does Attacca's automatic crossfade with
Rockbox's own one. When the plugin is closed it stays in the background and, at every song
change, sets up the next join from how the two songs end and start (the library knows it):

- a song that fades out by itself plays out whole over its own fade while the next one comes in;
- a hard, loud ending gets a short blend (2 s), so the next song does not pile up on it;
- otherwise both fade over the **crossfade length** set in the plugin (10 s unless changed);
- a next song that starts softly is not faded in;
- the next song of the same album (same folder, later in it) follows on without a blend;
- the silence at the end of one song and at the start of the next goes inside the blend: the next
  song starts that much earlier, and its fade-in waits for its sound (whole seconds, rounded down;
  up to 7 s of leading silence and 15 s of blend with the silence after the ending).

It needs a library exported with this version of Attacca or Attacca Desktop (older ones lack how
songs start and end: the plugin says so). Turning it on keeps your own crossfade settings, turns
Rockbox's crossfade on (automatic track change) when it was off and sets the fade-out delay and
duration to their maximum once, so Rockbox makes room for the longest joins (playback may pause
a moment then); turning it off puts your settings back. Songs that are not in the library, and
skips by hand, use your own settings (Rockbox leaves out the fade-in delay on a skip by hand).
Opening another plugin pauses it; it comes back when that plugin closes. Rockbox runs one
background plugin at a time.

**Jump over long silences** (off unless turned on) goes further: when a song has 3 s or more of
silence after its sound, or more than fits in Rockbox's fade-out, the plugin moves on to the next
song itself at the right moment (the blend plus the next song's leading silence, up to 7 s, before
the end of the sound), so the join happens where the music ends whatever the silence; and a song
starting with more than 7 s of silence is played from where its sound starts (that join is then a
clean cut, as Rockbox drops what it had ready). A jump is a skip for Rockbox, so this sets its
crossfade to "Always" (skips by hand blend too); on a skip Rockbox never mixes and leaves out the
fade-in delay, so a song that fades out by itself stays whole by a fade-out delay instead. Songs
jumped from have been played almost whole, so they count as played. Seeking by hand (fast forward,
rewind) holds the jump until the song has played on steadily for 1.5 s, and a seek that lands more
than 3 s past the jump leaves the end of that song to Rockbox's own crossfade.

**Skips by hand** (next, previous, another song chosen, a new list) are your own: **Fade on
skips** (off unless turned on) blends them with your own crossfade settings, else they cut cleanly.
Rockbox keeps one set of crossfade values for every change, so the plugin leaves those for skips in
place for most of each song and puts in the join it planned only near the song's end, just before
Rockbox prepares it; after any change, by hand or not, the next join follows the plugin's settings
again. With Fade on skips on (or Jump over long silences) Rockbox's crossfade is set to "Always";
otherwise to "Automatic track change only" when it was off.

**Keep albums whole** (on unless turned off) keeps the songs of an album from blending into each
other, also **with the automatic crossfade off** and Rockbox's own crossfade on (for example on
"Always"): when the next song is the next one of the same album and folder, or, for songs the
library does not know, the next file of the same folder, it follows on without a blend; every other
change keeps your own crossfade. Albums made to run on (live albums, songs that run into each
other) then sound as they were made: shortly before such a change the plugin sets Rockbox's
crossfade to "Manual track skip only", so the change uses Rockbox's own gapless playback (a
crossfade of no length still blended the two songs on some players), and back to its usual mode
before a change that blends (the theme's crossfade icon follows; the music never stops for it). Turned off, the automatic crossfade blends inside albums too.
Like the automatic crossfade, it works from the background once the plugin has been opened and
closed: after switching the player on, open the plugin once.

**Each join is written down** in `attaccaplg-joins.txt`, beside the library file (Rockbox opens
it as text): the time, the two songs, how they are joined and why (a crossfade, a song that fades
out by itself and plays out whole, a loud ending kept with a short blend, the same album without
a gap, the same folder in file order, or your own crossfade), the fade-out and fade-in in
seconds and, with jumps, where the jump comes. Most joins are plain crossfades of the length set,
so they sound alike; the file tells them apart. It starts again past 32 KB.

Silence skipping as such is not available: Rockbox playlists have no per-track start and end
points; the automatic crossfade hides the silences between songs instead.

## What's new

See [CHANGELOG.md](../CHANGELOG.md). In 1.1.0: **Keep albums whole**, also with the automatic
crossfade off.

## Installation

1. In Attacca Desktop: **Attacca → For Rockbox and the web generator**: write the music folder as
   Rockbox sees it (e.g. `/<microSD1>/Music`) and export `attaccaplg.bpcr2`. From the command
   line: `attacca-desktop player-db attaccaplg.bpcr2 --path-to "/<microSD1>/Music"`.
2. Copy `attaccaplg.bpcr2` to the root of the player (or to `/.rockbox/`).
3. Copy `attaccaplg.rock` to `/.rockbox/rocks/apps/`.
4. Optional, to open `.bpcr2` files from the file browser: add this line to
   `/.rockbox/viewers.config`:
   ```
   bpcr2,apps/attaccaplg,-
   ```
5. Run it from **Plugins → Applications → attaccaplg** (or by opening the `.bpcr2` file).

The plugin remembers its settings (in `/.rockbox/rocks/apps/attaccaplg.cfg`) and the library path.
After exporting the library again just copy it again.

## Which build to use

A Rockbox plugin is built for one player and for the plugin interface of one Rockbox version:
Rockbox refuses a `.rock` made for another one ("Incompatible plugin version"). The builds
published with each release say what they were made against; the current ones are for **Rockbox
built from commit `f3ffe789ef` (2026-10-29, plugin API version 286)**: the Shanling Q1, Rockbox
for Android (ARMv6) and the simulator. Rockbox itself built from that same source comes with
them, so player and plugin always match. For another player or Rockbox version, build it yourself
(see `build.sh`: it needs the Rockbox source and its toolchain, in Linux or WSL), from the same
Rockbox source as the firmware on the player.

## Memory and speed

Rockbox for Android is built here with 16 MB (the port's own 8 MB leave under 2 MB for sound,
too little for the longest joins: Rockbox stopped with "audio_reset_buffer_noalloc(): EOM"). A
plugin built for 8 MB or less keeps its blends short (up to 1 s of delay and 4 s of fade).

The library is read from disk on demand (168 bytes per song plus 120 for its edges: 21,000 songs
take about 8 MB), so it doesn't need to fit in memory. Seed mode and small pools work in the
plugin buffer; large pools (e.g. "Generate by filters" over the whole library) use the audio
buffer, which **stops playback** while generating (it is replaced by the new playlist anyway).

All the arithmetic is integer (most players have no FPU). The edge comparison is Attacca's
(harmony, timbre, level and activity), with the exponentials from a table. As in Attacca, the
greedy chain is then improved: runs of one to three songs move wherever the links gain as a whole
(weak links count more), keeping the artist spacing; here in windows of 200 songs (Attacca uses
600, there is no room for more). The results match Attacca Desktop except for rounding in near ties and
in chains longer than 200 songs.

## Compatibility

A Rockbox plugin only loads on the **same Rockbox version** it was built for (plugin API). The
included builds are for Rockbox `f3ffe789ef` (2026-10-29), and Rockbox itself built from it:

- `out/rockbox-shanlingq1.zip`: **Rockbox for the Shanling Q1**, with this plugin inside. Unzip it
  at the root of the card, replacing the `.rockbox` folder (keep a copy of the old one; your
  settings are in `.rockbox/config.cfg`). The bootloader already installed stays.
- `out/attaccaplg.rock`: the plugin alone for the **Shanling Q1** (to `/.rockbox/rocks/apps/`).
- `out/android/rockbox-armv6.apk`: **Rockbox as an Android app for ARMv6, Android 2.3** (native code
  for ARMv6 and API 9, Java checked against Android 2.3.3), e.g. the Samsung Galaxy Ace, with this
  plugin inside. It is signed with its own key: uninstall the Rockbox installed before (its
  settings go with it) and install this one. Besides the 16 MB, it carries two fixes to Rockbox
  for Android: an update copies the new plugins in once (and only then, not at every start), and
  bookmarks of playlists kept in `Playlists` are saved (they gave "Could not save bookmark"). Rockbox
  for touchscreen phones comes with only 18 plugins; this one has 74: every plugin that builds
  there (among them the text editor, lyrics player, playing time, stats, pitch screen, cue
  playlists, shortcuts, metronome and several games). The others, mostly games, have no keys set
  for Android; text is viewed with *view_text* (the full text viewer is one of them). Plugins made
  for buttons may be awkward on a touchscreen. A `viewers.config` of
  your own in `/sdcard/rockbox` takes the place of the app's: one copied from another player names
  plugins Rockbox for Android lacks (such as `text_viewer`); delete it to use the app's.
- `out/android/attaccaplg.rock`: the plugin alone for that Android app. There the plugins live in
  the app's private folder, so installing one by hand needs **root**:
  ```bash
  adb push attaccaplg.rock /sdcard/
  adb shell su -c "cp /sdcard/attaccaplg.rock /data/data/org.rockbox/app_rockbox/rockbox/rocks/apps/ && chmod 644 /data/data/org.rockbox/app_rockbox/rockbox/rocks/apps/attaccaplg.rock"
  ```
  Rockbox's "root" there is the storage (`/sdcard`): put `attaccaplg.bpcr2` there; export it with
  the music folder as the phone sees it (e.g. `/mnt/sdcard/musica`).
- `out/attaccaplg-sim.rock`: the Rockbox simulator.

For another player or version, build it from source.

## Building

On Linux (or WSL), with a Rockbox source tree and its toolchain (`tools/rockboxdev.sh`):

```bash
RBROOT=~/rbdev/rockbox ./build.sh target    # RBTARGET=shanlingq1 by default
./build.sh android                          # ANDROID_NDK_PATH: NDK r10e
./build.sh sim
```

Rockbox itself, from the same trees: `make zip` in the Q1's build folder gives `rockbox.zip`; for
the Android app, with an Android SDK holding `platforms/android-10` and `build-tools/28.0.3`
(`ANDROID_SDK_PATH`) and a JDK: `make apk ANDROID_PLATFORM_VERSION=10` in the Android build folder
(the native code stays API 9 and ARMv6; the Java code is compiled against Android 2.3.3).

`test/host_test.c` runs the plugin's logic on a PC against a `.bpcr2` (fitted filters, a list from
a song, the other lists and the joins of the automatic crossfade), to compare with Attacca Desktop:

```bash
cd test && gcc -O2 -I. -o host_test host_test.c
./host_test attaccaplg.bpcr2 "/<microSD1>/Music/Artist/Album/01-Song.mp3" 50 same_mood
```

## License

GPL v2 or later (as Rockbox plugins).
