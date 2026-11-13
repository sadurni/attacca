# Attacca Desktop

A playlist generator for the PC that listens to your music itself: no beets, no Essentia, no
Chromaprint. It measures every track with the same analysis as the
[Attacca](../android) music player for Android, so the PC and the phone describe a track alike:

- **Edges**: the first and last seconds of actual sound of each track (which notes sound, the
  shape of the spectrum, level, activity, timbre and tempo), for chaining tracks so the end of one
  leads into the start of the next.
- **Sound profile**: what each track sounds like, from a sound recognition network run by
  Attacca's own code over a few windows spread over three stretches of it: sound families and the tracks closest to one.
- **Features**: 30 s from the middle of each track: loudness, dynamics, brightness, bass,
  noisiness, percussiveness, tempo, pulse clarity, major/minor mode, key clarity and voice. From
  them and from the sound profile, five moods (energetic, cheerful, calm, melancholic, tense) and
  sound families, relative to your own collection. The moods come from the sound network's sense of
  happy, sad, tender, exciting and angry music (the track's own measures only when it has no sound
  profile); a song between two moods counts as either, in lists and in the library for players.
  The moods and families are kept beside the library and only worked out again when its tracks or
  their measures change, so the library opens without waiting for them.
- **Silence and loudness**: where the sound starts and ends, and the EBU R128 loudness.

## Status

A desktop app (Windows installer, or portable) and a command line: scanning the music, making
chained playlists and sharing the measured library with Attacca ([PLAN.md](PLAN.md), in Spanish),
plus a [Rockbox plugin](rockbox/README.md) and a [web generator](web/README.md) that make chained
lists on their own from the library exported for them (`attaccaplg.bpcr2`, which also says how
each song starts and ends, for the plugin's automatic crossfade). The places exported to are
kept (the file and the music folder as that player sees it): export again to one of them, or to
all those connected, without choosing anything.

## The window

- **Playing** and the **player bar** at the foot of the window: Attacca Desktop plays music too,
  as Attacca does on the phone. Each track from where its sound starts to where it ends (the
  silences the scan measured left out), all at the same loudness (from the measured LUFS, never
  past a track's peak), blended into the next one as the crossfade says (automatic: judged from how
  each track ends and the next one starts, as Attacca's automatic crossfade), and the tracks of an
  album played in order one into the next without a blend or a pause; tracks of any sample rate
  join without a gap (everything is brought to 44.1 kHz stereo with Attacca's resampling filter).
  Damaged MP3s play to the end: stretches written twice in a row are left out, and broken frames
  are skipped instead of stopping the track.
  Shuffle, repeat (all or one), previous / next, seek, volume, and the queue (double-click a track
  to play it, take tracks out, clear it); it is kept between sessions. **Settings → Playback**: the
  output (any sound card or device), the crossfade length and style, the silences and the
  loudness. Play from **Tracks** (double-click, or Play / Play next / Add to the queue) and from
  **Lists** (Play). **Playing** shows the cover (the folder's picture or the one in the file), the
  track's year, genre, mood, sound family, tempo and loudness, what comes next, and its waveform
  (click or drag on it to move; what is left out — the silences before and after the sound — shows
  faint between two marks, and the blend into the next track lighter); files, playlists (M3U, PLS, XSPF, CUE) and folders of the PC can
  be opened there. The player bar has favourites and a sleep timer (in so many minutes, at the end
  of the track or of the album).
- **Attacca World** (World in the menu on the left, or the 3D button in O'rama): the song as a land in 3D to go through behind the
  traveller and the four dogs, as on the phone, drawn by the processor. Drag to turn the camera,
  the wheel to bring it closer, the right button to raise it; a click on the traveller makes a move;
  the eye button shows it through the traveller's own eyes (Settings → Window to open it so).
- **O'rama** (in the menu on the left): the playing song as a moving landscape, as Attacca's
  O'rama on the phone. Its land is the song's waveform; its world comes from its mood and sound
  family in the library, and the look takes some of the cover's colours:
  - a castle land with a rider;
  - a city by day or a neon city at night;
  - the seaside;
  - meadows and mountains;
  - a cold sea with someone adrift on an iceberg;
  - space.

  The weather follows how it sounds. The traveller moves at its tempo and, on the strong beats,
  hops, flips, does cartwheels or wheelies; others pass by now and then (flying saucers, balloons,
  dogs, dragons, dolphins, fireworks…), more things stand by the way where the song is louder, and
  the track's record with its cover turns up adrift at sea, as a kite, half buried on the moon or on
  a billboard. Each track is a level that fades into the next; long stretches far quieter or louder
  than the rest change its world for a while, and tracks alike in a row get another traveller, hour
  or shades. Birds, trees, lights and animals move to the beat. The view comes closer in quiet
  parts and opens up in loud ones; at a track's peak there is a rainbow, a shower of stars or
  fireworks; on the way, bridges, tunnels, villages, a train, big wheels, ruins, arches of ice and
  rockets, things rushing by in front, a dog or a bird for company, and before the end a sign with
  the next track and its place in the queue (7/23), then a gate into the next track's world (a
  walled town's gate, a gate in a fence, a barrier, a neon arch, bunting, a gate of ice, a ring into
  space). The traveller takes the cover's accent, standing out from what is behind it, and carries
  a torch through tunnels (a car switches its lights on, a bike its lamp); vehicles and horses lean
  with the slopes, legs and arms bend at the knees and elbows, and houses and ruins hide it as it
  passes behind them. The record's sleeve shows the album and the artist; under the title, the
  track's mood and sound family (and a few little surprises to find), on a see-through dark box (as are the buttons). Instead of the player bar it has a
  small floating player (a compact block in the corner when the window is wide): the time and length of the track, what comes next (click it for the
  queue), a heart for the favourites, and a bar that is the map of this track and the next (click or drag to move, click the
  next one to go to it). The buttons at the top right, one under the other and half tucked away until clicked, show the landscape alone
  over the whole screen (F11; Esc or a click to come back) and switch between 60 and 30 frames a second.
- **Lists kept, as on the phone** (Lists → Kept lists): the lists made are kept on their own (a new
  one replaces the one of the same name), the queue can be saved as a list of your own (Playing →
  Save as a list…), tracks and albums are added to your lists from the right-click menu; each list
  goes on where it was left (track and time) or from the start, and can be renamed or deleted.
  Favourites (the tracks marked with ♥, kept in `Favorites.m3u8` beside the library, as the phone
  keeps its own) comes first, to play like any list; the phone's Favorites.m3u8 (from
  Music/Attacca on the phone) can be added to it, each track found in the library by its folders.
  The app opens on Playing; with nothing queued it offers to go on with the list played last.
- **A list per output** (Settings → Playback): each output (speakers, headphones, a USB or
  Bluetooth device) keeps its own queue where it was left, as each Bluetooth device in Attacca;
  opening the app takes up the list of the output in use, and connecting an output that has its
  own list plays there (or just loads it); disconnecting the output in use pauses.
- **Right-click** on tracks (Music, Tracks, the queue, the lists made) and albums: play, play next,
  add to the queue, add to a list, favourite, a list from the track, a bridge from or to it, its
  information (tags, file, format, mood, family, BPM, energy, positivity, loudness) and its folder.
- **On the system**: the keyboard's media keys (play/pause, next, previous, stop) work with the
  window in the background (Windows, Linux on X11, macOS); an icon in the system tray with the
  transport, the mini player and quitting, where the music can go on playing when the window is
  closed (Settings → Playback); a **mini player** always on top (cover, track, transport,
  position); shortcuts in the window: Ctrl+Space play / pause, Ctrl+→ / ← next / previous,
  Ctrl+↑ / ↓ volume; Page Up / Page Down move the page's list (Ctrl+Home / Ctrl+End to its start
  or end), and every list has a scrollbar to drag.
- **Music**: the library by album (a grid of covers), artist, genre, decade, mood and favourites,
  with search; play, shuffle, play next or add to the queue a whole album or group, or
  double-click a track.
- **Sound**: Attacca's sound processing, the same code as the phone's: preamp, equalizer (10
  bands, or parametric bands pasted as text from a headphone correction file), dynamic and virtual
  bass, stereo width, balance and channels (mono, swapped, karaoke…), crossfeed or virtual
  speakers for headphones, compressor and limiter; every change moves smoothly while playing.
  Each output (sound card, headphones…) keeps its own sound, as the phone's device profiles.
  *Tune the sound step by step* is the phone's sound guide: each effect heard A/B/C on a stretch
  of the library picked for it, a test of how deep the output plays, before against after.
  Settings → *Fine tuning*: a check-up (tracks not analysed or without loudness, damaged tracks,
  outputs without a sound of their own) and guides by ear for the transitions, the volume and the
  chaining: *Chaining to your taste* moves how much the beat, the sound, the energy and the key
  count when lists are chained (also for the command line and the library exported for Rockbox
  and the web).
- **Covers**: every album's cover at a glance, with those without one marked. *Find the missing
  covers* looks for them online in an open music database ([MusicBrainz](https://musicbrainz.org)
  and its [Cover Art Archive](https://coverartarchive.org)) by album and artist; clicking an album
  (or *Search for a cover online…* on its page in Music) shows the editions found to choose
  another. What is found is kept by Attacca Desktop (in its data folder, `covers/`) and shows
  wherever the album's cover does; you decide whether it is also saved as `cover.jpg` in the
  album's folder, so the phone and other players see it too. Nothing else is sent anywhere: only
  the album and artist searched for.
- **Damaged tracks** (Library): every MP3 checked, several at once, for broken frames inside the
  music (bursts of noise) or stretches written twice (the music jumps back) — the same check as
  the phone's; files the scan could not read count too. They are marked ⚠ in Tracks, listed with a
  search, left out of lists unless that is turned off, and a damaged MP3 can be repaired (also from
  the right-click menu of a track, an album or a kept list): written again without its broken or
  repeated pieces, a stretch with bits gone wrong all through it left out whole, the original kept
  beside it as `.bk` (not taken for music; a later repair starts again from it) or deleted. A
  track repaired while it plays is read again from where it was.
- **Library**: the music folder, scanning (progress, stop), what was measured, the moods and sound
  families of the collection (click one for a list of it), which tracks lists may use.
- **Tracks**: the whole library, searchable and sortable; start a list from any track.
- **Lists**: by filters, by template, automatic (one per mood and family), from a track (with a
  button that fits the filters around the track, leaving ten times the tracks asked for), other
  lists as Attacca's (energy that rises and falls, warming up, winding down, a sampler of the
  sound families, the opposite of a track, a list for a set time, whole albums like a track's
  album, a bridge from one track to another, tracks like a whole playlist without its own), or an
  existing playlist reordered. Filters by mood, sound family, artist, genre, years, BPM, track
  length (1 to 20 minutes unless changed), energy, positivity, voice and the inclusion list (an
  M3U8, or Attacca's rules file, worked out again each time); 150 tracks per list unless changed.
  See the lists (tracks with their length, total length, how well they chain)
  before saving them as M3U8, XSPF or Rockbox M3U, with the paths of the device that plays them.
- **Attacca**: import the library measured on the phone, or export this one for it; export the
  library for the Rockbox plugin and the web generator.
- **Themes**: make and change themes for the phone and for this window, in the phone's own format
  (shared through core): copy one of the gallery's or the built-in ones, or open a zip exported
  from the phone, then change its colours (light and dark versions, with a picker), corners, text
  size, typefaces (also TTF/OTF files of its own), what Now playing shows behind it, a background
  picture per screen (with versions for light colours and landscape, and how much to soften it),
  the phone's Now playing layout (upright and landscape: its parts, rows of buttons, information
  lines, boxes) and its own icons. Parts of the layout are dragged with the mouse by ⠿ (onto the
  middle of a box: into it), and ⋯ puts one into a box or takes it out, or copies or cuts it, to
  paste in another box, the other layout or another theme, with its pictures. Previews follow every change: the phone's screens (Now playing,
  folders, lists) on phones of several sizes and densities and a tablet, upright or in landscape,
  light or dark, and this window at several sizes. Use it here, or export it as a zip to open on
  the phone (Settings → Appearance → Import a theme).
- **Settings**: language (the phone app's: English, Spanish, Catalan, Galician, Basque,
  Portuguese, Brazilian Portuguese, French, Italian, German, Dutch, Swedish, Polish, Romanian,
  Russian, Ukrainian, Greek, Turkish, Japanese, Korean, Simplified and Traditional Chinese), theme (the phone's: the system's, White, Dark, Black
  and the gallery's, or a theme exported from the phone (its zip), with its colours and background
  pictures — each page shows the one of the phone's matching screen; the accent can come from the
  playing track's cover, as on the phone), edge length for chaining (as in Attacca),
  tracks measured at a time, playback (output, crossfade, silences, loudness, tray, media keys).
- **beets** (in Settings, when beets is installed): import albums with it (in
  a terminal, since it asks while it tags), scan its music folder, run any of its commands and see
  what it prints, and give it what Attacca measures as fields of its own — `attacca_mood`,
  `attacca_family`, `attacca_bpm`, `attacca_energy`, `attacca_positivity` — so that, for example,
  `beet ls attacca_mood:calm` lists the calm tracks. Tracks are matched by their file, so beets
  should keep the files where Attacca Desktop scans them.

## What's new

See [CHANGELOG.md](../CHANGELOG.md). In 1.1.0: the **Covers** page (covers looked for online, kept
here and, if wanted, saved in the album folders) and O'rama's smoother ground, with level bridges,
stations and roads.

## Use

```
attacca-desktop scan <music folder> [--db FILE] [--jobs N]
attacca-desktop stats [--db FILE]
attacca-desktop analyze <audio file>
attacca-desktop edges [SECONDS]                          edge length for chaining (4-20, default 8)
attacca-desktop silences [--jobs N]                      only the silences tracks lack (brought from a phone)
attacca-desktop export FILE.attlib                       the library for Attacca (or another PC)
attacca-desktop player-db FILE.bpcr2 --path-to BASE      the library for the Rockbox plugin and the web
attacca-desktop import FILE.attlib [<music folder>]      what Attacca (or another PC) measured
attacca-desktop import FILE.attlib --no-music            the file's library as it is, without its music
```

Lists:

```
attacca-desktop generate [filters] [--count N] [--start TRACK] [--name NAME]
attacca-desktop template "{mood}_{family}" [filters]     one list per value (mood, family, genre,
                                                    artist, albumartist, album, year, decade, folder)
attacca-desktop auto [filters]                           one list per mood and sound family
attacca-desktop seed TRACK [--count N] [--same-mood] [--same-family] [--fit]
attacca-desktop more KIND [TRACK] [--to TRACK] [--total N] [--list PLAYLIST]
                                                    rise-fall, warm-up, wind-down, sampler,
                                                    opposite, time (--total minutes), albums,
                                                    bridge (--to), like (--list)
attacca-desktop reorder PLAYLIST [--start TRACK]         m3u, m3u8, pls, cue or xspf, in chained order
attacca-desktop find TEXT
attacca-desktop inclusion LIST [--mode allow|block]      which tracks lists may use (--included): an
                                                    M3U8 or Attacca's rules file (.rules.txt)
attacca-desktop import-beets-include BEETS.db            that list from beets' "include" marks
```

Filters: `--mood`, `--family`, `--artist`, `--genre`, `--year 1990-1999`, `--bpm 90-130`,
`--energy 50-100` and `--positivity 0-40` (percent of the library), `--voice voice|instrumental`,
`--included`, `--minutes 1-20` (track length: that is the default; `--minutes any` for every
length). `--count N`: tracks per list, 150 unless given (`--count all`: every track that passes). Output: `--out DIR`, `--format m3u8|xspf|rockbox`, `--path-to BASE` (where the music
is on the device that plays the lists), `--spacing N` (tracks before an artist repeats). Lists
chain each track's end into a similar start, skip the silence at the edges (M3U8 and XSPF, as VLC,
AIMP and Attacca read them) and avoid repeating an artist too soon (a list from a song takes no
more songs of one artist than that allows, here, in the Rockbox plugin and in the web page).

`scan` measures what is new or changed since the last scan (by path, size and date) and forgets
files that are gone.

Attacca and Attacca Desktop share the library in `.attlib` files (tags, the silence at the edges,
loudness, waveform, chaining edges and sound features, and the inclusion list), so a collection is
measured once, on the phone or on the PC. Tracks are matched by their path within the music folder
and their size; what does not match is measured by the next scan. A library can also be imported
without its music on this PC (window: Attacca → Import without the music; `import … --no-music`):
lists, reordering and the exports work as usual (lists without a device path hold the paths within
the music folder); choosing the music folder later keeps what was measured. The library is the window's unless `--db` says otherwise.

Formats: MP3, Ogg Vorbis, FLAC, WAV and AIFF out of the box; AAC, WMA, Opus and others when
[ffmpeg](https://ffmpeg.org) is on the PATH.

## Install

- Windows: `AttaccaDesktop-<version>.msi` (brings its own Java), or the portable zip of the same app.
- Linux (x64): `attacca-desktop_<version>-1_amd64.deb` (Ubuntu 24.04 and alike: installs in
  `/opt/attacca-desktop` with a menu entry) or `AttaccaDesktop-<version>-linux-x64.tar.gz` (any
  Linux: unpack and run `AttaccaDesktop/bin/AttaccaDesktop`). Both bring their own Java; with a
  command (`AttaccaDesktop scan …`, `AttaccaDesktop seed …`) the same program works as the command line.
- Any system with Java 17 (Windows, Linux, macOS): `attacca-desktop-<version>.zip`, then
  `bin/attacca-desktop-window` (the window) or `bin/attacca-desktop` (the command line).

The library and settings are kept in `%APPDATA%/AttaccaPLG` (Windows), `~/.config/AttaccaPLG`
(Linux) or `~/Library/Application Support/AttaccaPLG` (macOS) — the program's earlier name, kept so
an existing library is found; window and command line share them.

## Build

JDK 17 to build, from this folder: `gradlew run` (the window), `gradlew installDist` (command line
in `build/install/attaccaplg`), `gradlew packageMsi createDistributable` (Windows installer and app
folder), `gradlew distZip` (the zip for any system). Linux and macOS packages must be built on
those systems: `tools/build_linux.sh` (in Linux or WSL, with a JDK 17) makes the Linux ones in
`build/linux`; on macOS, `gradlew packageDmg`. The analysis is the phone's own code, in `../core`
(built with this one, `includeBuild`), and so are the sound classifier's weights
(`../core/assets/sound`).

## License

MIT, as Attacca. The sound profile uses the weights of the YAMNet sound classifier
(https://www.kaggle.com/models/google/yamnet), under the Apache License 2.0.
