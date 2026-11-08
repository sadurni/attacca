# Changelog

What changed in each version, for every part. The app shows its own part in About → What's new;
the [releases](https://github.com/sadurni/attacca/releases) have the files.

## 1.2.0

### Attacca (Android)

- **A list from the track playing goes on with it.** Create list from this track, the quick list
  and the other lists made from the playing track keep that track where it is: the new list is
  put around it, without starting it again.
- **New widget**, changing as it is resized: the cover and play; one row with the time going on;
  two rows with the album, what comes next and the list; tall and narrow, the cover big on top and
  more below as it grows; and, bigger, the cover large or a still of the track's O'rama landscape
  (renewed as it plays), as chosen in Settings → Appearance. A heart adds the track to Favorites.
  It takes the colours of the theme in use.
- **Damaged tracks**: a search in their list; marked ⚠ also in the title box of Now playing, in
  Music and in the lists (△ for an album, artist, folder or list with some of them); Repair in the
  ⋮ menus of the title, a file in Folders, a track in Music and the playing list, and Repair
  damaged tracks for a folder (all the damaged ones in it), an album, an artist or a list. A track
  repaired while it plays or waits in the list is read again, from where it was.
- **Better repairs.** In a stretch with bits gone wrong all through it (not only in the frames
  that break), the whole stretch is left out, and the frame after each cut that kept part of its
  sound in what was cut plays silent: a short jump instead of bursts of noise. A file repaired
  before is repaired again from its `.bk`. When much is lost, it says a new copy would be better.
- **The inclusion list in use**, at the top of Lists: tap it to change it.
- **Folders with covers**: each folder and track shows its cover, and a button shows a folder as a
  grid of covers, like the albums in Music; the first level (the music folder) keeps its own choice.
- **Lock screen** (Settings → Appearance): its picture can follow the track playing (its cover with
  the title, or the landscape of O'rama), and Attacca can show over it while music plays when it
  was open as the screen went off.
- **O'rama**: when the red moon comes, the usual moon gives way.
- **Nine more languages**: Hindi, Kannada, Arabic (right to left), Tamazight (Tifinagh), Kabyle,
  Swahili, Hausa, Yoruba and Amharic.

### Attacca Desktop

- The same better repairs; Repair in the right-click menu of a track, Repair damaged tracks for an
  album, a group and a kept list; a search in the damaged tracks; a repaired track playing is read
  again from where it was.
- A list made from the track playing goes on with it.
- Nine more languages (Arabic right to left), and the red moon of O'rama as on the phone.

### Attacca Web

- Nine more languages.

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
