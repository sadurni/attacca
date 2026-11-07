# Changelog

What changed in each version, for every part. The app shows its own part in About → What's new;
the [releases](https://github.com/sadurni/attacca/releases) have the files.

## 1.1.0

### Attacca (Android)

- **Setup guide.** After choosing the music folder, a few steps through what matters first: the
  analysis in the background, how you listen most of the time (a sound template) and how one track
  gives way to the next. It can be run again from Settings → Setup guide.
- **Settings reorganised.** A search that finds any setting by its name, the essentials at the top
  (music folder, analysis, transitions, silence skipping) and the rest grouped by theme.
- **Sound templates.** Headphones (strong crossfeed), earbuds, car, portable speaker, home stereo
  and phone speaker: Sound → Template… starts a device's profile from one, and the first time a
  new device plays, Attacca offers one for it.
- **Several inclusion lists**, one for each occasion (with the children, with friends, just you…):
  kept by name in Settings → Inclusion lists, one in use at a time (or Everything). Changed in a tap
  from Lists, the ⋮ menu of Now playing and the dialogs that make lists; a device can put one in
  use when it connects. Adding a track or folder asks which list it goes to; a list can be
  duplicated to start another. A single list from before becomes the first of them.
- **Damaged tracks.** Found as the library is scanned, as they play (from 0.3 s left out), or with
  Settings → Library → Check new and changed tracks (several files at once, with the analysis
  threads, the time left on screen and in a notification; stopped, it goes on later with what is
  left; Check all again checks every track once more); only damage that is heard counts: from three broken frames inside the music, or a
  stretch written twice (a frame or two at the ends, next to the tags, is no damage). Counted in
  the statistics, marked ⚠ in Folders and Music (△ for a folder with some inside) and left out of
  the lists Attacca makes (a setting). A damaged MP3 can be repaired: written again without its broken or repeated pieces, the original kept beside it as
  `.bk` or deleted, as you choose.
- **Covers from the web.** In a track's tags, Search the web opens the browser with the artist and
  album to find the cover (Attacca itself still has no internet access).
- **O'rama.** Bridges and stations stay level and roads smooth, with crates, barrels, logs,
  rocks, benches, suitcases, ramps and cones to jump where the music rises; the train runs beyond
  the platform, behind the traveller; the iceberg floats in the sea; the land stands out more from
  the sky, and lighthouses stand on their rock.

### Attacca Desktop

- **Several inclusion lists**, as on the phone (Library → Which tracks lists may use): one in use,
  duplicated to start another, and all of them written into the library for players.
- **Damaged tracks**, as on the phone (Library → Damaged tracks): every MP3 checked (several at
  once) for broken frames inside the music or stretches written twice, files the scan could not
  read counted too; marked ⚠ in the track list, left out of lists unless turned off, and damaged
  MP3s repaired (the original kept as `.bk` or deleted).
- **Covers.** A new page shows every album's cover: the missing ones can be looked for online all
  at once, and any album's can be looked for again and chosen among the editions found (also from
  the album's page in Music). What is found is kept by Attacca Desktop; you decide whether it is
  also saved as `cover.jpg` in the album's folder. Covers come from an open music database
  (MusicBrainz) and its cover archive (Cover Art Archive).
- O'rama: the same changes as on the phone.

### Attacca for Rockbox

- **Inclusion lists**: libraries written by this version carry all of them; *More filters →
  Music* chooses which one lists keep to (or Everything).
- **Library**: a new menu item chooses among the `.bpcr2` files at the root of the player and in
  `.rockbox` (any name ending in `.bpcr2`).
- **Fade on skips**: skips by hand blend with your own crossfade or cut cleanly, as you choose;
  the plugin's join for each song is set only near its end, so changes by hand no longer upset
  the joins that follow. Joins without a blend use Rockbox's own gapless playback (its crossfade
  set to skips by hand only for that change), and Rockbox's own crossfade is given back as you
  left it.
- **Keep albums whole** (on unless turned off): the songs of an album follow each other without a
  blend, also with the automatic crossfade off and Rockbox's own crossfade on "Always"; songs the
  library does not know count as an album when they are the next file of the same folder.

### Attacca Web

- **Inclusion lists**: a library that carries several lets you choose which one lists keep to
  (Music: …).

## 1.0.0

Initial version.
