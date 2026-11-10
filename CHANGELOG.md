# Changelog

What changed in each version, for every part. The app shows its own part in About → What's new;
the [releases](https://github.com/sadurni/attacca/releases) have the files.

## 1.3.0

### Attacca (Android)

- **Sound guide**: Sound → Tune the sound step by step goes through the effects one at a time on
  the device in use. Each step plays a stretch of your own music picked for it (the bassiest for
  the bass, the brightest for the treble, the widest for crossfeed, the most varied for the
  levels) and you compare two to four choices, A, B, C; left and right with a sound of its own;
  how deep the device plays, with tones one by one or a bass line going down, decides what the
  bass step offers (a boost, or the bass it cannot play heard through its harmonics). At the end,
  before against after, saved as the device's own profile. Also offered when a new device plays
  for the first time and in the first-start guide.
  Saving asks where: the profile in use, replaced, or a new profile with a name.
- **Sound profiles**: Delete… lists every profile but Default (with how many devices use each)
  and asks before deleting; the profile buttons wrap onto more lines on a narrow screen.
- **O'rama**: the strip in Now playing and the whole O'rama are one landscape: switching between
  them goes on where it was (also on the desktop).
- **Transitions by ear** (Settings → Crossfade): the end of one real song into the start of
  another as each choice joins them (one after the other, automatic blend, crossfade of 4 or 8 s),
  a song with silence at its end (left out or kept), two tracks in a row of an album (without
  gaps or blended); saved as the playback settings.
- **Volume by ear** (Sound): the loudest and quietest songs of the library one after the other
  (as the files are or evened out), a quiet and a loud track of one album (as recorded or each
  song the same), the overall level; saved in the profile in use.
- **Check-up** (Settings): tracks not analysed, loudness not measured, damaged tracks, effects of
  the phone or other apps on top, battery saving, devices without a sound of their own; each with
  what it affects and a button to fix it, and the guides by ear.
  Which tracks carry ReplayGain tags is now kept in the library (read after each scan), so the
  loudness count is exact and measuring covers only the tracks with nothing to level them by.
- **O'rama**: in the strip of Now playing, what flies over (balloons, airships, clouds) keeps its
  shape instead of being squashed into a ball; the four dogs change places in the pack now and then
  (going ahead or dropping back gradually) and wander more within it.
  The sun, the moon and space's planets stay behind everything that flies by, and clouds hide the sun whole.
  What crosses the sky fades in and out instead of popping up (balloons rise from behind the hills),
  and a station's train comes in from the left of the screen and goes out on the right.
  The peak of a track stands out more: the sky darkens a little behind it and each effect shines
  (a glowing rainbow, more and brighter shooting stars and fireworks, a beating red moon), and over
  the ice the northern lights fill the sky.
- **Automatic crossfade**: a track whose file ends in the middle of its sound (a live track cut
  before the applause dies, an album running on into the next track, a mix) was kept whole as if
  it ended hard and stopped dead; it now fades out.
- **Chaining to your taste** (Settings → Chaining, and the check-up): the end of a song of your
  music goes into two songs that could follow it, eight times; one matches the end better in one
  thing (the beat, the sound, the energy, or the key and notes) and the other in the rest, and you
  keep the one that follows better. What you keep moves how much each counts when lists are
  chained; the result shows each before and after, and can go back to Attacca's own weights.

### Attacca Desktop

- The sound guide in Sound, for the output in use, with samples from the library.
- Settings → Fine tuning: the check-up and the guides by ear for the transitions, the volume and
  the chaining (to your taste, as on the phone; also for the command line).
- The library exported for Rockbox and the web carries the chaining weights.
- The automatic crossfade fades a track cut off in the middle of its sound.

### Attacca for Rockbox

- **Fine tuning by ear** in the plugin's menu: the sound (Rockbox's own settings: balance and
  mono with a sound of the plugin's, how deep the player goes with tones or a bass line, bass,
  treble, crossfeed or width, compressor, before and after), the transitions (one after the
  other, automatic, crossfade of 4 or 8 s, silences, albums) and the volume (ReplayGain), each
  choice heard on the player; the list that was playing comes back afterwards.
- The automatic crossfade no longer stops dead a song cut off in the middle of its sound (with
  a library exported again from Attacca Desktop 1.3.0).
- Lists chain to your taste when the library comes from an Attacca or Attacca Desktop whose
  chaining guide was used (the web generator too).

## 1.2.1

### Attacca (Android)

- **O'rama in Now playing**: Settings → Appearance → Waveform can show a strip of O'rama in its
  place, made to fit the strip, with the sun or moon in sight (a tap opens the whole O'rama); the
  waveform, as chosen, goes small between the times and still moves through the track. A small
  button in the corner of the waveform or the strip changes from one to the other.
- **O'rama**: the cover, small, in the title's box; the four dogs in a pack each in front of the
  one ahead, so no face hides behind another's back; the houses of a village behind the way stand
  behind its ground; in space the ground stands apart from the hills behind it; the dogs' glass
  helmets close round their necks.

### Attacca Desktop

- O'rama in Now playing in place of the waveform (the button in its corner, or a right click).
- O'rama: the cover in the title's box (a click goes to Now playing), and the same changes to the
  dogs, villages and space.

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
