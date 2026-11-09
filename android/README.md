<p align="center">
  <img src="../docs/icon.svg" alt="Attacca icon" width="96">
</p>

<h1 align="center">Attacca</h1>

<p align="center">
  A local-music player for Android with its own audio processing chain:<br>
  gapless and true crossfades, silence trimming, ReplayGain, crossfeed and a full DSP<br>
  with per-device profiles — built for large playlists.
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="Media3" src="https://img.shields.io/badge/Media3-1.8-FF6F00">
  <a href="../LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-blue"></a>
</p>

> *Attacca [atˈtakka]: in music, go straight on to the next movement, without a pause.*

> ### “Rediscover your music collection: hear it in new ways, with the best sound.”

**Your own music, played as if someone had listened to every track first.** Attacca measures
your files right on your phone — how each track starts and ends, how it sounds, its mood — and
plays them so one song flows into the next.

- 🔗 **Chained lists.** It hears how each track ends and the next begins (tempo, key, timbre,
  energy) and puts any list in an order where every change feels natural. A whole list from a
  single track, in one tap.
- 🎚️ **A complete sound chain.** Graphic and parametric equalizer with headphone corrections,
  dynamic and virtual bass, stereo width, crossfeed, compressor and true-peak limiter; gapless
  playback, crossfades that follow how each track ends, silence trimming, ReplayGain. A profile
  for each output: headphones, car, speaker.
- 🌄 **O'rama: watch the song.** A moving landscape drawn from the song's waveform, mood and
  beat: someone runs, rides or flies through castles, cities, seas and space, jumping on the
  strong beats, through tunnels with a torch, towards a gate into the next track's world. And a few little
  surprises to find.
- 🎭 **Moods and sound families, found by ear.** A sound-recognition network runs on the phone
  itself and groups your music by how it sounds: lists by mood and family, without anyone
  else's tags.
- 🎨 **Make it yours.** Themes with their own colours, fonts, backgrounds and icons, and a Now
  playing screen you lay out piece by piece; a gallery of themes to start from.
- 🔒 **Private by design.** No internet permission, no account, no ads, no tracking. Free and
  open source, in 32 languages.
- 💻 **Not only on the phone.** [Attacca Desktop](../desktop) (Windows, Linux, macOS), a
  [web page](../web) and a [plugin for Rockbox players](../rockbox) chain your music the same way
  and share what was measured.

---

## Contents

- [What's new](#whats-new)
- [Features](#features)
- [Sound processing](#sound-processing)
- [Screenshots](#screenshots)
- [Requirements](#requirements)
- [Building](#building)
- [Installing](#installing)
- [Project structure](#project-structure)
- [How it works](#how-it-works)
- [Testing](#testing)
- [Languages](#languages)
- [Roadmap](#roadmap)
- [Third-party libraries](#third-party-libraries)
- [License](#license)

---

## Features

### Playback
- **Gapless playback** between tracks, with ReplayGain applied at the exact sample boundary; the
  effects run on across the joint (no filter reset, no look-ahead flushed as silence) and decoder
  padding between tracks is dropped, so a gapless album sounds as one stream. Tracks at other
  sample rates or in mono are brought to 44.1 kHz stereo (windowed-sinc filter), so the output
  never stops to start over in another format between two tracks.
- **Damaged MP3s** play through: stretches a faulty copy wrote twice in a row (heard as the music
  jumping back) are left out, and so are broken frames (cut short by garbage, or garbage that looks
  like a frame of another kind), which would decode as bursts of noise; what is left plays on
  without a pause, and the crossfade into the next track reads the file the same way.
- **True crossfade** (configurable length, per list or global): the end of each track is decoded
  ahead of time in the background and blended into the next one on equal-power curves — no
  stall or dropout when the blend starts. Albums can be kept gapless (a track followed by the
  next one of its album, by track number or file name, plays on without a blend; tracks of one
  album that a generated or shuffled list puts together blend like any others), and the
  crossfade can be limited to shuffle. The blend is a plain
  **crossfade**, a **mix** (the ending track plays out whole under the rising next one), a
  **custom** shape (when the ending track fades, how soon the next one is whole) or
  **automatic**: each transition is decided from the tracks' analysis — an ending that already
  fades out is not faded again and the blend lasts as long as its own fade, an ending that stays
  loud to its last note keeps it with a short blend, a soft intro is not faded in. A new setting
  applies from the coming transition.
- **Crossfade on a skip by hand** (optional, up to 6 s): next, previous or another track chosen
  while playing — what was playing fades out from the very instant heard, read from the audio
  output, while the new track comes in.
- **Leading/trailing silence trimming**, analysed once per file in the background and cached
  (before a track plays; for the whole music folder, with the analysis for chaining or at once from
  Settings → Chaining, reading only the first and last seconds of each file); pauses inside gapless
  albums are respected.
- **Waveform** of the whole track as the seek bar (top half or mirrored), with trimmed and
  crossfaded parts shown dimmed; the position follows what the audio output plays, so it never
  steps back. Or, in its place, a strip of O'rama made to fit (a tap opens the whole O'rama), the
  waveform then small between the times; a small button in the corner of either changes from one
  to the other.
- When a folder played from Folders ends, playback can go on with the next folder (in Folders
  order) or a random one of the music folder. When a list ends, Now playing is left empty.
- **Go to the end** (track menu, cover gesture, a button): to the last seconds of the track (10 s
  unless changed), to hear how it ends and its transition.
- Fades on pause/resume/skip, sleep timer (minutes, a clock time, or the end of the track, the
  album, the artist's tracks or the list — that last track then plays out whole, without
  crossfade), playback
  speed and, apart from it, pitch in semitones, A-B repeat, bookmarks, lyrics (`.lrc` and
  embedded), tag editor, sharing tracks with other apps. In the tag editor, *Search the web*
  opens the browser with the artist and album to find the cover; the picture saved there is then
  set for the album with *Choose a picture* (Attacca itself has no internet access).
- **Damaged tracks** are found as the library is scanned, as they play (broken or repeated MP3
  frames, files the player stops on) or with Settings → Library → *Check new and changed tracks*
  (each file read through once, several at once, with the time left in a notification: broken
  frames, stretches written twice, files cut short or unreadable; a check stopped halfway goes on
  later with what is left, and *Check all again* checks every track once more; their list has a
  search). They are counted in the statistics, marked ⚠ in Folders, Music, the lists and the title
  box of Now playing (△ for a folder, album, artist or list with some inside) and left out of the
  lists Attacca makes unless that is turned off. A damaged MP3 can be repaired — from that list or
  the ⋮ menu of a track, a folder (all the damaged ones in it), an album, an artist or a list:
  written again without its broken and repeated frames (a stretch with bits gone wrong all through
  it is left out whole, and the frame after each cut that kept part of its sound in what was cut
  plays silent: a short jump instead of noise; the encoder's information frame made to match),
  the original kept beside it as `.bk` (not taken for music; a later repair starts again from it)
  or deleted, as chosen. A track repaired while it plays is read again from where it was.
- **Other sounds**: a notification or another app sounding briefly lowers the volume, pauses the
  music or is ignored, as chosen; after a call the music can come back by itself.
- **Track information** screen: pictures first, then file, audio format (codec, bit rate, sample
  rate, bit depth), analysis results (silence trimmed, part played, crossfade), how often it was
  played and skipped, which of your lists hold it, and every tag.
- **Listening statistics**: a track counts as played once half of it (or four minutes) has really
  been heard — seeking ahead and pauses do not count — and as skipped when you move on before
  that. They give the lists **By listening** (most played, last played, not heard for a long time,
  never played, most skipped), the *Listened* filter of generated lists, the play count shown in
  the player's list box (at the right of the list's name) and the `{plays}` /
  `{lastplayed}` fields of themes. Optionally every play also goes into a **listening log** in the
  standard portable-player scrobbling format (`Music/Attacca/scrobbler.log`), for a scrobbling tool
  on a computer to send on: Attacca itself never uses the Internet.
- **Attacca O'rama**: the playing song as a moving landscape, upright or wide (the landscape
  button among the player's tools, the track menu ⋮, or a cover gesture). The land is the song's
  waveform, so loud parts rise as hills and tall buildings. The world comes from the song's mood and
  sound family:
  - a dark land of castles under a moon, with a rider and a banner;
  - a city by day with someone running, or a neon city at night with a car;
  - the seaside with a cyclist and sailing boats;
  - meadows, forests and mountains with someone walking;
  - a cold sea with icebergs adrift, someone standing on one and now and then a whale's tail;
  - space, with a ship over the surface of a moon.

  The near ground follows the music smoothly and the traveller jumps its quick rises; bridges,
  stations and roads stay level, and there the rises become things to jump over (crates, barrels,
  logs, rocks, bricks, benches, suitcases, ramps and traffic cones). At a station the train runs
  beyond the platform, behind the traveller.

  Its colours follow the cover's: the land its main colour, the horizon and small bright things its
  accent (the same one the theme takes). The weather follows the sound: heavy, dark music brings a
  storm with lightning on the strong beats, bright music a high sun, sad songs clouds and soft rain,
  the cold sea snow and northern lights. The traveller goes at the song's tempo, steps on its beat
  (the runner slows to a walk in quiet parts) and, on the strong beats of what is heard (taken
  after the sound processing), hops, jumps, flips, does cartwheels, wheelies, rears up or loops,
  bolder the livelier the music. Others pass by now and then: flying saucers, shooting stars and
  an astronaut in space, balloons, geese, deer and rabbits over the meadows, a helicopter, a blimp
  and dogs in the city, drones, fireworks and a motorbike in the neon city, bats, a dragon and
  wolves by the castles, seagulls, dolphins and crabs at the seaside, seals in the cold sea. Things
  stand by the way, more of them where the song is louder: cows and sheep, windmills, fences,
  pigeons that fly off as the traveller comes, benches, sunshades, a lighthouse, penguins on the
  icebergs, little green ones waving on the moon. The track's record with its cover turns up too:
  adrift at sea, flown as a kite, half buried on the moon, on a billboard or a banner. Each track is
  a level: the next one's land is already ahead, and the old land fades into the new one. Long
  stretches far quieter or louder than the rest of a track (a ballad that turns into hard rock and
  back) change its world and pace for a while; tracks alike in a row get another traveller, hour or
  shades. Flocks, bats and butterflies beat their wings on the beat and rise on strong beats; trees,
  grass, clouds, lamps, neon windows, the sun, penguins, pigeons, cattle and little green ones move
  with the music too. The land follows each track's own quiet and loud, so its hills and valleys
  show plainly, and what stands on it sits on the ground. The view comes closer in quiet parts,
  opens up in loud ones and shakes a little on strong low beats; the sun goes across the sky along
  the track; at its peak something big happens (a rainbow, a shower of stars, a red moon,
  fireworks). On the way there are places to pass: a river with a bridge, tunnels, a village with
  its church (houses in the traveller's own scale; sometimes the way goes right through it, between the houses), a station with a train going through, big wheels, a giant screen with the cover,
  ruins, arches of ice, a moon base, a rocket that takes off as the traveller goes by; grass,
  branches and lamp posts rush by close in front; now and then a dog, a bird or a little drone
  keeps the traveller company, and in some tracks the traveller changes halfway (in a puff of
  dust). Before the end a sign shows the next track's title and its place in the list (7/23), and
  the level ends at a gate into the next track's world: a walled town's gate, a gate in a fence, a
  barrier that lifts, a neon arch, bunting by the sea, a gate of ice, a ring into space (a finish
  arch when the next track is not known). The traveller takes the cover's accent, light or dark
  enough to stand out from what is behind it, with a light of its own in dark places: in a tunnel
  it takes the torch at the way in and leaves it at the way out (a car switches its lights on from
  the roof down, a bike its lamp). Bikes, cars and horses lean with the slopes, people and animals
  bend their knees and elbows, and houses, ruins and domes on the way hide the traveller as it goes
  behind them. The record's sleeve shows the album and the artist; under the track's title, its
  mood and sound family (title, artist and album on a see-through dark box; the buttons on the right stay half tucked away until a tap brings them out, and the sun and moon keep clear of both). A small
  floating player (across the bottom when upright, a compact block in the corner when wide; the
  land rises over it) shows the time and length of the track and what comes next (tap it for the current list):
  previous, play / pause, next, a heart to add the track to Favorites, and a bar that is the map of this track and the next one (tap or
  drag to move, tap the next one to go to it). The whole screen button leaves the landscape alone
  (system bars too); a tap brings the rest back. Flat shapes in a few layers, drawn at 60 or 30 frames a
  second (30 saves battery); the screen stays on while it shows. Tracks not analysed yet get a
  neutral land that still moves with the sound.

### Lists and library
- Folder browser that opens in your music folder (back where you left it inside it) and keeps
  each folder's scroll position when you go back up, with a fast search (the music folder is kept
  in memory while browsing), multi-selection (play, add to the current list or to
  any list, create a list from a track) and folder covers; **go to the folder** of the playing
  track from its menu, without moving the browser away from where you left it. **Favorite
  folders** under the storages and, from any folder, behind the folder-with-star button at the top.
- **Ignored folders and files**: never in Music nor in the lists Attacca makes by itself
  (generated, smart, endless, by listening, the next folder, a folder played with its
  subfolders), whatever the inclusion list says — e.g. copies of albums kept in an artist's
  folder. They are still scanned and analysed, shown and found in Folders, and a list can be
  made from one of them.
- **Music**: the music folder as a music database — artists, albums (as a list or a grid of
  covers), compilations, tracks, genres, decades, recently added, and by mood and sound family
  from the analysis — made from the tags the library scan keeps, so it opens at once. Albums keep
  the discs of a set together or each folder apart (as album shuffle does); artists go by album
  artist or by each track's artist. Album lists go by title or, from the menu, by year (the year
  is read from the tag itself, also from the ID3v2.4 date Android's own reader leaves out), as
  covers or a list; an artist's page keeps its own view and order (by year at first). The menu
  shows only the options of the view on screen. An
  album shows its cover, year, genre, length and plays; a
  tapped track plays its album from there. Shuffling several albums asks whether by albums or by
  tracks, and then at random or **chained**: tracks chained from a random one, or whole albums
  chained to each other (each album's end leading into the next one's start). **Go to album** and
  **Go to artist** open the playing track there, from its menu, a cover gesture, a button or a
  long press on the title box; the album opens on the track, marked.
  Several whole albums played one after another (an artist, albums shuffled or chained) open
  grouped by album, by tag or by folder as albums are taken.
  **Create list like this** on an album or an artist: the tracks closest to any of theirs (they
  themselves left out), chained.
- Playlists: **M3U / M3U8, PLS, XSPF and CUE**, including clip points (`#EXTVLCOPT`); lists are
  saved as M3U8, PLS or XSPF.
- Favorites, your own lists, generated lists, every playlist ever opened and recent folders:
  Favorites and the lists folder at the top, then a tab each for your lists, the generated ones,
  the lists by listening and the opened ones (the last tab used comes back); the lists played last
  are also one tap away in the side menu. Lists can be put in your own
  order within each group, selected (one group at a time or all) to delete them, and saved lists
  renamed — their settings, resume point, devices and inclusion rules follow the new name. A
  deleted list is removed everywhere: the devices that continued it, its settings, resume point
  and place among the recent lists go with it (a list deleted some other way is dropped the
  first time it is missed, never bringing the app down).
  With an inclusion list there is also **Favorites, only included**: a list of its own that
  follows Favorites by itself (new favorites that are included join at its end, those taken out
  or no longer included leave it) and keeps its own order, so it can be chained or sorted apart
  while Favorites stays as it is; it is worked out again only when Favorites or the inclusion
  list change, and the other lists show meanwhile. Chaining a saved list starts from the selected track, else from
  where the list was left, else from its first track; the current list starts from the playing one.
- **Smart lists**: lists made from conditions — artist, album artist, album, title, genre,
  folder, path, year, length, tempo, energy, positivity, mood, voice, added lately, played (never,
  lately, not lately, at least so many times), skipped, the inclusion list — each of which can be
  turned round, in an order (as found, random, by artist, album or year, newest, most or last
  played, or chained) and up to a number of tracks, with the count of matching tracks shown while
  editing. They are made again after each library scan and each time they are opened, and stay
  ordinary M3U8 files (their rules in a `.smart.txt` next to them, editable by hand too), so other
  apps and devices can play them.
- **Endless lists** (globally or per list): when a list's last track starts, ten similar tracks
  chained from it are added (same mood and sound family first, nothing the list has or that was
  heard today, keeping to the inclusion list); they are not saved into the list.
- **List tools**: remove repeated tracks or those played in the last days, and combine a list with
  another — add its tracks, keep only those both share, or take its tracks out. The result
  changes your own list or is saved as a new one.
- Saving an unsaved list (a generated one, picked files) or a playlist opened from elsewhere
  turns the playing list into the saved one, at the same track and position.
- A **lists folder** of your choice (for example, where another app saves M3U8 files), opened
  from the Lists screen; its browser remembers its own last folder, apart from the music one.
- **Open with Attacca** from file managers, browsers and share sheets: audio files and playlists
  are recognised by extension even when sent with a generic type.
- Current list with each track's length at the end of its row, search, multi-selection, drag-to-reorder, sorting (title, artist, album,
  track, year, file name, path, date, duration) and **collapsible groups** (album, artist, year
  or folder) that can be reordered as a whole. The actions used most are a row of icons under its
  title (sliding sideways when they do not fit; a long press shows each one's name): save, chain,
  chain by albums, group, sort and the list's settings. **Changes to the whole list
  can be undone** — chaining, sorting, grouping, shuffling the albums, keeping only the included
  tracks, removing tracks — from the message they leave at the bottom (a few seconds) or from the
  ⋮ menu, which keeps the last change of any kind (moving or adding tracks too) until another one
  takes its place or another list opens; the playing track goes on as it was.
- **Saved lists without playing them**: open one and the same buttons chain it, chain it by
  albums, mix its albums, group or sort it, set it up (shuffle, crossfade, ReplayGain, trimming)
  or open the list tools; a list that is not one of yours is saved as a new one of yours. Each
  change made to one of your lists this way can be undone from its message.
- **Lists made are kept** (optional, Settings → Chaining): lists made from a track (quick and
  random ones too) and generated lists are saved among your lists as soon as they play. While a
  list is being made the buttons that make lists wait, and a second quick list from the playing
  list's own track is not made again. Quick and random lists can leave out the tracks played in
  the last days (Settings → Chaining).
- **Shuffle by album**: albums in random order, each album's tracks in order — gathered from
  wherever they are in the list. Every album is kept, and the albums of each artist are spread
  evenly over the whole list (as far apart as the list allows, with no pile of leftovers at the
  end). Albums are taken by their album tag (all the discs of a set together) or by folder (each
  disc of a double album or a box set apart), as chosen in Settings → Playback.
- **Chain by albums**: reorders the current list by whole albums, the end of each album leading
  into the start of the next, starting with the playing album and keeping the albums of an
  artist apart.
- **Chain a list**: reorders the current list so the end of each track sounds like the start of
  the next (notes, timbre, level and activity), starting with the playing track and keeping
  tracks by the same artist apart. Tracks are analysed once, at low priority and without
  blocking the app — when a list is chained, or for the whole music folder in the background or
  on demand, several files at a time (as many as you choose, up to one per core) and keeping
  the phone awake while the music folder is analysed; the seconds compared at each end can be set.
  Analysing is much faster with the screen on (with it off the phone slows down to save battery):
  an option keeps the screen on while the music folder is scanned or analysed with Attacca open.
  Long jobs (scanning or analysing the music folder, chaining a list) show their progress in a
  notification: files done out of all, percentage and time left.
- **Generated lists**: moods (energetic, cheerful, calm, melancholic, tense) and **sound
  families** found automatically in your collection are estimated from the audio: families (up to
  16, more the larger the collection) and which tracks are closest to a track come from a sound
  recognition network Attacca runs itself, on the phone, over three stretches of each track. The
  moods come from the same network's sense of happy, sad, tender, exciting and angry music (never
  from how loud a track was mastered); a track between two moods shows both, "Calm (or
  Melancholic)", and counts as either when choosing by mood. A mood that does not fit can be set by
  hand from the track menu (**Mood…**), for the track, its album or the artist; a track's own mood
  also goes to the tracks that sound most like it (kept in `Music/Attacca/moods.txt`). The moods and
  families are kept between runs and only worked out again when the analysed tracks or the moods
  given by hand change, so they show at once when the app opens. One chained list
  per mood and family, or **a list from any track** (the closest tracks of its mood and family,
  chained from it, taking no more tracks of one artist than the artist spacing allows), with filters: artist, genre tag, years, tempo, track length (1 to 20 minutes
  unless changed), energy, mood, voice and listening (leave out the tracks played lately, or
  ever); as many tracks as **Tracks per list** says (150 unless changed in Settings → Chaining).
  Settings only hold the starting values: each list can take other tracks per list and inclusion
  as it is made, with the usual sizes a tap away (50, 100, 150, 300, 500), and the settings stay
  as they are. Quick and random lists go straight with the starting values; a long press on
  their buttons ("Quick list with options…", the dice) asks first. For a
  list from a track, one tap fits those filters around the track, as tight as they can be while
  leaving ten times the tracks asked for. **Quick list from this track** (track menu, a Now
  playing button in every theme, a cover gesture) does all that at once and plays it: same mood
  and family, fitted filters, widened when they leave too few, the tracks per list set.
  **Random list** (the dice in Lists, a Now playing button, a cover gesture, or
  shaking the phone while music plays) does the same from a track taken at random, of the mood
  you choose; with the screen off or the app out of view it goes on in the last mood. The shake
  is side to side, back and forth or any way, light to strong: the first two never clash with
  the up-and-down or twisting gestures some phones use for the torch or the camera.
- **Other lists**, made and played at once: energy that rises to a peak and calms down again,
  warming up or winding down little by little (each track also near the one before), a sampler
  of the most typical tracks of every sound family, and tracks like your favorites not heard for
  some days (or never); from the playing track, its opposite (the tracks least like it), whole
  albums that sound like its album (chained album by album) and a list lasting about the minutes
  you give. A **bridge** goes from one track to another: select the two in a list, in Music or in
  Folders (the selection stays while you search or open an album, so the second one can be looked
  for), and tap the road button; a panel shows both ends, which can be swapped, with the tracks
  and inclusion of that list. Tracks not analysed yet are analysed on the spot. A saved list
  can also make a list like the whole of it. All of them are chained by how each track ends and
  the next one starts (the bridge and the energy curves weigh that next to their way forward), and
  while a list is being made a notice at the top of the screen says so; its X stops it.
- **Inclusion list**: an M3U8 list read as an allow list or a block list; with “Only included”
  on, lists take only the tracks it allows, and the current list can keep only those. Whether the
  lists Attacca makes (generated, from a track or a group, quick, random, other lists, endless)
  take only the included ones is set once in Settings; every list starts from that and can change
  it as it is made, for that list only.
- **Several inclusion lists**, one for each occasion (with the children, with friends, just you…):
  kept by name in Settings → Inclusion lists, each with its own M3U8, mode and rules, one in use at
  a time or none (*Everything*). The one in use is changed in a tap from Lists, the ⋮ menu of Now
  playing and the dialogs that make lists, and a device can put one in use when it connects
  (Devices). The library for players carries all of them, for the Rockbox plugin and the web page
  to choose from. A single list from an earlier version becomes the first of them.
  Tracks, albums, artists and folders (with subfolders) are added or taken out from their
  menus; they are kept as rules in a text file next to the list, which is computed from them
  (so new files in those folders, albums or artists join it after each scan). A list edited by
  hand can turn back into rules. One screen shows all its rules and the resulting list, with a
  summary: rules can be deleted, switched between add and take out or edited as text, and
  tracks taken out of the list. Every list and the folder browser mark what the list leaves
  out: an orange “!” before a track (also next to the title in Now playing, or a folder with all
  its music left out) and “◐” before a folder with part of its music left out; the settings show
  the legend.
- **Settings per list**: shuffle, repeat, ReplayGain mode, silence trimming and crossfade.
- Designed for **lists of thousands of tracks**: tags are cached in a database, a scan of the
  music folder reads them once, and reordering never moves tracks one by one.
- **Library statistics** from the scan: tracks, artists, album artists, albums, genres, years,
  total time, size and audio formats, and the **missing** tracks: known to the library (from a
  scan or a restored backup) but not on this device. They stay out of Music, of the lists and
  of what is played (a list's missing entries are skipped, and kept in its file when the list is
  edited), come back when the files are there again, and can be forgotten for good.
- **Portable lists**: export any list as M3U8 (the file picker opens in the lists folder when
  one is set); with a music folder set, exported paths are
  relative to it and imported lists find their tracks even when they were written on another
  device or a computer.
- Backup and restore of settings, profiles, caches and lists in a single zip file, also
  **automatically** every day, week or month into a folder of your choice, keeping the last few.
- Library exchange with Attacca Desktop, the app for the PC (`.attlib` file): tags, pre-play
  analysis, chaining edges, sound features and the inclusion list, so the collection is measured
  once, on the phone or on the PC.
- **Library for players** (`.bpcr2` file): the analysed music folder with its moods, sound
  families and chaining edges worked out in integers, for the Rockbox plugin and the web list
  generator of Attacca Desktop, with the paths as the player sees its music folder (e.g.
  `/<microSD1>/Music`) — the same file Attacca Desktop writes. It also says how each track starts and
  ends (its silences, whether it fades out by itself or ends hard, whether it starts softly), so
  the Rockbox plugin can do the automatic crossfade on the player. The files exported to are
  kept, each with its player's music folder: one more export writes one of them, or all, without
  choosing them again.
- Every file and folder picker (music folder, lists folder, export, backup, inclusion list)
  opens where it was last used.

### Devices
- **DSP profiles assigned to output devices** (speaker, wired headphones, each Bluetooth
  device…), switched automatically and without clicks. **Sound templates** start a profile from
  what suits a kind of device: headphones (strong crossfeed, as speakers in front), earbuds (light
  crossfeed, a gentle bass lift), car (levels evened out over the road noise, more bass), portable
  speaker and phone speaker (bass heard through its harmonics, even levels) or home stereo (nothing
  added). Sound → *Template…* applies one, and the first time a new device plays Attacca offers one
  for it, as that device's own profile.
- **A list per device**: each device (the phone speaker included) continues its own list, which
  can follow the last list played on it. The list is loaded when the device connects and when
  the player opens with it as the output.
- Each device folds to its name and a one-line summary (type, profile, list, volume); the one
  playing now opens by itself, and one button opens or folds them all.
- **Fixed volumes per device**: the media volume and the notification volume can each be set
  for a device, every time it becomes the output (e.g. quieter notifications in the car).
- **Start playing when a Bluetooth device connects** (e.g. the car), also with the player
  closed: the app opens on the player and plays the device's list; if Android does not let it
  start, a notification plays it with a tap.
- **Fixed volume per device**, applied whenever it becomes the output.
- Media session with notification, lock screen and headset/car controls; a close button in the
  notification ends everything (the phone's controls show it with the favorite button; the
  previous/next album buttons are for the car). Title, artist, album and cover go with every track of the list
  from the start (from the tag database), so car stereos and watches show them; the cover over
  Bluetooth needs a car with AVRCP 1.6 cover art and that version chosen on the phone.
- **Android Auto**: the current list, your lists (favorites included), the generated ones, the
  lists played lately and the music folder, browsable down to each track; a list or folder plays
  from where it was left, a track plays its list from it. Previous / next album (or group of the
  list), favorite and close buttons (optional).
- **Home-screen widget**, changing as it is resized: the cover and play / pause; one row with
  title, artist, the time going on and previous / play-pause / next; two rows with a large cover,
  the album, what comes next and the list; tall and narrow, the cover big on top and more below as
  it grows; and, bigger, the cover large or a still of the track's O'rama landscape (renewed as it
  plays), as chosen in Settings → Appearance. A heart adds the track to Favorites. It takes the
  theme's colours; its buttons also work with the app closed. Settings → Appearance can place it.
- **Lock screen** (Settings → Appearance): its picture can follow the track playing — its cover
  on a dark copy of itself with the title, or a still of its O'rama landscape — set again with each
  track (turned off, the lock screen takes the home screen's picture); and Attacca can show over
  the lock screen while music plays, when it was open as the screen went off.
- **Folders with covers**: each folder (its picture, or its first track's) and track shows its
  cover, and a button shows a folder as a grid of covers, like the albums in Music (the first
  level, the music folder itself, keeps its own choice).

### Interface
- **First steps**: after choosing the music folder, a short **setup guide** goes through the
  analysis in the background, how you listen most of the time (a sound template for the default
  profile) and how one track gives way to the next (automatic blends, a classic crossfade or one
  after another, albums kept whole, silence skipping); it is in Settings → Setup guide whenever
  wanted again. **Settings** open with a search that finds any setting by its name, the
  essentials (music folder, background analysis, transitions, silence skipping) and the rest
  grouped by theme: playing, lists and chaining, library, look and feel, advanced.
- Jetpack Compose, Material 3; white, dark and black themes with the accent color taken from
  the cover art, kept for what is active (playing, selected, switched on); one type scale and
  three corner radii across the app; system bars follow the app's theme.
- **Your own themes**, made in the app (Settings → Appearance → Theme): start from any theme and
  change its colors (with the colors of the playing cover at hand), the accent from the cover,
  corner roundness, text size, typeface (or own TTF/OTF fonts for the text and the titles, kept
  in the theme) and the Now playing background (plain, accent gradient
  or blurred cover), with a live preview, contrast warnings and undo; turning a dark theme into a
  light one (or back) can convert its colors keeping their hues. A theme can also have both a
  light and a dark version and follow the phone (the built-in System themes, White by day and Black
  or Dark by night, are like that, and show the track's mood and sound family in small under the
  album). Each theme is a JSON file in `Music/Attacca/Themes` (its
  pictures and fonts in a folder of the same name), so it can also be changed by hand, and goes
  into the backup; any theme can be saved as a zip and a zip (or a theme JSON) imported.
- **Theme gallery** with the themes that come with Attacca, each shown as Now playing looks in
  it and named after a film with an attack in its title: Attacca of the 50 Foot Woman (a vinyl
  record), Attacca at Dawn (northern lights), Gattacca (a starry sky), Attacca of the Ents (autumn
  leaves), Attacca of the Mushroom People (a green meadow), Attacca of the Killer Bees (a wheat
  field in the sun, golden at dusk), Shark Attacca (a cliff with a lighthouse: sunny by day, lit
  under the moon by night), Mars Attacca! (the red planet by day and under its two moons), Attacca
  Ships on Fire (golden rays bursting behind the title, the cover beside the times and the file's
  details, after a classic portable-player screen) and Attacca the Block (a hi-fi system with its
  displays, keys and push buttons). Using one puts a copy
  among your themes, to edit or delete freely; the originals stay in the gallery and are
  improved with new versions of the app, which never touch your copies. Settings → Appearance
  shows only the two System themes and your own. Gallery themes, and your copies of them while
  unchanged, are named in the app's language.
- **Your own pictures in themes**: background pictures for Now playing, each screen and each box
  (with both versions, one picture by day and another by night),
  and own icons for any button and for icons of the app (folder, list, track, the side menu). Pictures are prepared when added (icons cropped square, backgrounds
  sized to the screen), fitted to their place and softened so text reads well; pictures of one
  theme can be reused in another. They live in the theme's folder and travel with it. A shape
  icon takes the theme's colours; a full-colour picture (not tinted) is the whole button, like a
  key of a hi-fi system, and lights up with an accent edge while it is on. The playing track's
  cover can also be the background of any box (whole, cropped or stretched, softened and blurred)
  and, blurred, of every screen.
- **Your own Now playing**: a theme also lays out Now playing. Arrange, remove or add its parts
  (cover, title box, list box, progress bar, rows of buttons, information lines, boxes, space) and
  choose the buttons of each row among 28 (seek 10 s, lyrics, speed, A-B, add to list, quick list, random list, track
  information…). The title and list boxes have their own lines, to rearrange, remove or add to,
  each with its text size; the list box shows the next track (as the play order has it) unless
  taken out, and the themes with a list box show it too.
  Boxes hold any parts, other boxes too, one under the other or side by side with a width for each,
  framed or not. ⋮ on a part puts it into a box beside it or takes it out of its box (the title
  and list boxes too), and copies or cuts it: Paste puts it at the end of any list of parts, in
  another box, the landscape layout or another theme, with its pictures (parts that appear once
  paste only where they are missing). Information lines come from a template with any field of the playing track: tags
  (any tag by its key), the audio stream (format, bitrate, sample rate, bit depth), the file, the
  list, the next track and its artist, the measured loudness, the tempo, the mood and the sound family, e.g.
  `{format}[ · {bitrate}][ · {samplerate}]`. The editor previews the whole screen as you change
  it. Landscape follows the same layout (the cover and the list box at the left, the rest at the
  right), or one of its own. On short screens or with big text or display sizes, a column that
  does not fit is drawn smaller so every part shows, and the cover keeps a fair size.
- **Coherent navigation**: Back always returns to the screen you came from (Now playing is home),
  the main sections open the side menu from their header (☰), and screens inside another show ←.
- **Swipe actions on rows** of the current list, other lists and Folders (remove with Undo, play
  next, add to the current list or to favorites), chosen in Settings → Gestures.
- **A long press on any button shows what it does**, and some offer a short menu of related
  actions: Favorite opens Favorites on the playing track; next and previous go by album, to the
  start or to the end; shuffle and repeat choose their mode or stop at the end of the album or
  list; the sleep timer offers 15, 30 or 60 minutes or an end; the quick list button makes lists
  like the album or the artist, a random list or other lists; the navigator opens the last lists;
  play / pause can pause at the end of the track or the album; Sound turns every effect off for
  a moment, to hear the music without them, and switches the profile of the current output. A
  long press on the list box opens Lists, and on the title box the track's album in Music.
- Selected rows and the marked one (the playing track, the one gone to) stand out with an accent
  fill and an accent bar on their left edge, clear whatever the colour of the cover.
- First start: choose the music folder (scanned once) or restore a backup (nothing to scan again),
  with the state of each permission; a permission Android no longer asks for opens the app's
  system settings instead of doing nothing.
- Floating side menu, slide transitions (the cover and title slide sideways on track changes,
  from the left when going back), a mini player on every screen, an always-visible
  draggable scrollbar and a list summary (position, total time, size).
- Now playing shows title, artist and album and the list's details (position, track number,
  year, genre) each behind its icon; list rows show the artist next to the folder.
- Configurable gestures on the cover (a tap, swipes and double taps per edge; a long press on the
  cover opens their settings). A tap shows the cover over the whole screen by default, as large
  as the picture is (folder picture or the one in the file), to pinch, double tap and drag
  around. Portrait and landscape
  layouts whose controls fit narrow screens and large display sizes, optional keep-screen-on
  while playing.
- Settings and sound options grouped in category pages; an About screen with version, build
  date, What's new (the changes of each version), the source code and F-Droid links once
  published, components and licenses.
- **Help** (the ? in the side menu) with a topic for each part of the app, and **tips** that
  grow as speech bubbles out of the part of the screen they talk about, lit up over the dimmed
  screen, the first time each screen opens after installing a new version (they can be turned
  off, or shown again, in Settings → Appearance).
- 32 languages (Arabic right to left), chosen in the app apart from the phone's — also from the flag at the top of the
  side menu (Catalan, Basque and Galician with their own flags, drawn by the app), whose list
  names every language in itself and in English, so the way back is always readable.

## What's new

The changes of each version are in [CHANGELOG.md](../CHANGELOG.md) and in the app (About → What's
new). In 1.2.1: O'rama can take the waveform's place in Now playing, and O'rama shows the cover by
the title. In 1.2.0: a list made from the track playing goes on with it, a new widget in four sizes
(the largest with O'rama's landscape), damaged tracks marked and repaired from more places with
better repairs, the inclusion list in use at the top of Lists, folders with covers (also as a
grid), the lock screen's picture from the track playing, and nine more languages (Hindi,
Kannada, Arabic, Tamazight, Kabyle, Swahili, Hausa, Yoruba, Amharic).

## Sound processing

Every stage is written in Kotlin and runs inside Media3's audio pipeline in 32-bit float:

```
ReplayGain + preamp ─► crossfade ─► EQ ─► bass boost ─► virtual bass
                     ─► stereo width ─► channels / balance / EQ precut
                     ─► crossfeed ─► compressor ─► look-ahead limiter ─► output
```

| Stage | What it does |
|-------|--------------|
| **ReplayGain** | Track/album/auto modes, preamp, clipping prevention (a peak or gain tag written on the wrong scale is ignored, so it can never mute a track). Files without ReplayGain tags are measured (EBU R128 integrated loudness) before they play and get their own track and album gain. |
| **EQ** | Graphic, 10 one-octave bands, or **parametric**: up to 10 bands, each a peak or a low or high shelf with its frequency, gain and Q. A headphone correction in the usual parametric EQ text (a `Preamp` line and `Filter` lines) is imported from a file or pasted, and saved back; each output profile keeps its own. An automatic precut lowers the level as much as the bands raise any frequency, so boosts never push the music into the limiter. |
| **Bass boost** | Gentle curve (default): a first-order shelf rising gradually from the low mids down, as a tone control, with a steady boost; the level comes down only as far as the track's headroom (from its ReplayGain peak) falls short, so the rest of the music keeps its level instead of being pushed down by the limiter. Deep curve: a steep low shelf whose gain backs off so the bass stays under a ceiling. |
| **Virtual bass** | For the phone speaker or small earbuds: the deep bass below a cutoff is rectified into its harmonics, band-limited and added, so the ear hears the bass the speaker cannot play. |
| **Stereo width** | Mid/side width 0–200 %, optionally keeping the bass centred. |
| **Channels** | Stereo, mono, left or right on both sides, swapped, or karaoke (what both channels share taken out); with balance. |
| **Crossfeed** | Bauer stereophonic-to-binaural filter (light / medium / strong / custom) or **virtual speakers**: a spherical-head model with head shadow, interaural delay and faint early reflections. |
| **Compressor** | Feed-forward, soft knee, stereo linked, attack/release, make-up by hand or automatic (what compression takes from a full-scale signal). |
| **Limiter** | Look-ahead brick-wall limiter with smooth release, on the **true peak**: the detector also checks three points between each pair of samples (4× oversampling), where the wave the converter rebuilds can rise above both. |

**Sound effects of the phone** (Settings → Car and background): Attacca never offers its audio session to
equalizer apps, and it takes over the standard Android effects of its session at the highest
priority and keeps them off, so an app that finds the session anyway cannot change the sound.
Equalizer apps and sound processing built into the phone (Dolby and similar) are detected and
listed, with a one-time warning, since effects on the whole output act after Attacca and can only
be turned off in their own settings.

Every setting moves smoothly while it changes (EQ bands, width, channels, balance), so dragging a
slider never clicks; profile switches fade out and in; heavy background work runs at low priority so the audio thread
always comes first, also with the screen off. Recursive filters are kept out of denormal numbers,
so hard-panned recordings with one silent channel cost no more to process than any other.

**Performance settings** (Settings → Car and background): the audio buffer — how far ahead of what you hear
the sound is processed — is learned automatically, starting at 2 s and growing by a second after
each dropout the phone actually has, up to 5 s (or set by hand from 0.5 to 5 s; a longer buffer
survives longer stalls, while sound changes take that long to be heard). Detected dropouts are
counted there. "Fewer wake-ups" (on by default) lets the playback thread sleep until the buffer
needs more audio instead of waking every 10 ms, roughly halving its wake-ups with the screen off
and avoiding dropouts with demanding effects without a longer buffer.

## Screenshots

**Now playing in some of the themes of the gallery** — Shark Attacca by day, Mars Attacca! (the
title box shows the cover itself, blurred), Attacca of the Killer Bees with its mood and sound
family line, the same theme in its dark version, Attacca the Block and Gattacca:

<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-marina.jpg" width="200" alt="Marina">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-marte.jpg" width="200" alt="Marte">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-trigo.jpg" width="200" alt="Trigo">
</p>
<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-trigo-dusk.jpg" width="200" alt="Trigo, dark version">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-minicadena.jpg" width="200" alt="Minicadena">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-estelar.jpg" width="200" alt="Estelar">
</p>

**And the rest of the gallery** — Attacca at Dawn, Attacca of the Ents, Attacca of the Mushroom
People, Attacca Ships on Fire and Attacca of the 50 Foot Woman:

<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-aurora.jpg" width="160" alt="Attacca at Dawn">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-autumn.jpg" width="160" alt="Attacca of the Ents">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-prado.jpg" width="160" alt="Attacca of the Mushroom People">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-rays.jpg" width="160" alt="Attacca Ships on Fire">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/player-vinilo.jpg" width="160" alt="Attacca of the 50 Foot Woman">
</p>

**Landscape**, with a layout and a picture of its own (Shark Attacca leaves the lighthouse in
sight, by night and by day) or laid out by itself:

<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/landscape-marina-night.jpg" width="400" alt="Marina at night, landscape">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/landscape-marina-day.jpg" width="400" alt="Marina by day, landscape">
</p>
<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/landscape-marte.jpg" width="400" alt="Marte, landscape">
</p>

**O'rama** — the playing song as a moving landscape, in each of its worlds: a dark land of
castles (the traveller's four dogs along), the city by day with a train, the neon
city at night, the seaside, meadows and mountains, the cold sea under an arch of ice, and space;
and wide:

<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/orama-castle.jpg" width="130" alt="O'rama: castles">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/orama-city.jpg" width="130" alt="O'rama: the city">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/orama-neon.jpg" width="130" alt="O'rama: the neon city">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/orama-coast.jpg" width="130" alt="O'rama: the seaside">
</p>
<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/orama-meadow.jpg" width="130" alt="O'rama: meadows">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/orama-ice.jpg" width="130" alt="O'rama: the cold sea">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/orama-space.jpg" width="130" alt="O'rama: space">
</p>
<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/orama-wide.jpg" width="560" alt="O'rama, wide">
</p>

**Lists and folders** — the current list grouped by album, a playlist, Lists with the generated
lists by mood and family, the folder browser and the side menu:

<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/queue-groups.jpg" width="200" alt="Current list grouped by album">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/list-view.jpg" width="200" alt="A playlist">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/lists.jpg" width="200" alt="Lists">
</p>
<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/folders.jpg" width="200" alt="Folders">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/side-menu.jpg" width="200" alt="Side menu">
</p>

**Menus and dialogs** — the track menu, the current list menu, a list from a track (with its mood
and family) and lists by mood and family:

<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/track-menu.jpg" width="200" alt="Track menu">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/queue-menu.jpg" width="200" alt="Current list menu">
</p>
<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/seed-list.jpg" width="200" alt="Create list from this track">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/generate-lists.jpg" width="200" alt="Generate lists by mood and family">
</p>

**Settings and themes** — settings by category, Appearance with your themes, the theme gallery, the
theme editor with the live Now playing preview and a box being edited, and sound processing:

<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/settings.jpg" width="200" alt="Settings">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/appearance.jpg" width="200" alt="Appearance">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/theme-gallery.jpg" width="200" alt="Theme gallery">
</p>
<p align="center">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/theme-editor.jpg" width="200" alt="Theme editor">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/title-box.jpg" width="200" alt="Editing the title box">
  <img src="../fastlane/metadata/android/en-US/images/phoneScreenshots/sound.jpg" width="200" alt="Sound processing">
</p>

## Requirements

- Android **8.0 (API 26)** or later.
- "All files access" (Android 11+) or storage permission, to read your music folders and
  playlists with their relative paths.
- Optional: "Nearby devices" permission, "Display over other apps" and no battery optimization,
  only to start a list when a Bluetooth device connects while the player is closed.

## Building

You need **JDK 17** and the **Android SDK** (platform 36).

```bash
git clone <this repository>
cd attacca/android
./gradlew assembleDebug          # debug build
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # release build
```

- `local.properties` must point to your SDK (`sdk.dir=…`); Android Studio creates it.
- Gradle picks an installed JDK 17 by itself (`gradle/gradle-daemon-jvm.properties`), whatever
  `JAVA_HOME` points to.
- The app is one part of a larger project: the code that analyses music and makes lists lives in
  `../core` (shared with Attacca Desktop) and is built with the app (`includeBuild`); the sound
  classifier's weights come from `../core/assets`.
- Release signing: put a `keystore.properties` next to this file (never in the repository) with
  `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it, the release build is
  signed with the debug key, so it installs over debug builds.
- Reproducible builds: the build date shown in About is the date of the last commit, and the APK
  carries no dependency metadata block, so the same source gives the same APK.
- Version codes are major × 100 + minor × 10 + patch (100 = 1.0.0). The notes of each version are
  written once in `../fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, where F-Droid
  reads them; the build copies them into the app for its What's new screen (English when a
  language has none).
- `../tools/export_soundnet.py` makes the sound classifier's weights
  (`../core/assets/sound/soundnet.bin`) from the published YAMNet model; see the script for how.

Toolchain: AGP 8.13, Gradle 8.14, Kotlin 2.2, Compose BOM 2025.12, Media3 1.8.

## Installing

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

On first launch grant access to your files, pick your music folder in *Settings → Library* and
let it scan.

If Attacca ever closes by itself, `Music/Attacca/attacca-exits.txt` says why: Android's reason for
each such exit (an error, closed for lack of memory, not responding…), written at the next start,
and the error itself when the app fails. `Music/Attacca/attacca-transitions.txt` notes how each
change of track went, with the time: the track that ended, how it blended into the next one (shape
and length), whether its end was ready and how well it lined up with what had played, and any
dropout — so a fault heard at a given moment can be found.

## Project structure

```
app/src/main/java/com/sadurni/attacca/
├── dsp/        Audio processors: gain, crossfade, EQ, bass, width, crossfeed, compressor, limiter
├── playback/   Media session service, renderer, crossfade tails, device monitor, queue state
├── analysis/   One-pass file analysis (silence, waveform), tail decoder, caches
├── library/    Files, playlists (M3U/PLS/CUE), tags, sorting, album shuffle, scanning, lyrics
├── chain/      Track analysis on the phone, generated lists, the analysis passes
├── settings/   Settings, DSP profiles, per-list settings, gestures, backup
└── ui/         Compose screens: player, lists, browser, queue, settings, theme

../core/src/main/kotlin/com/sadurni/attacca/   (shared with Attacca Desktop)
├── chain/      Edges, features, sound network and profiles, moods and families, chaining, lists
├── analysis/   Loudness (EBU R128) and the analysis record
└── library/    Playlist formats, inclusion rules, path matching, the library exchange formats
```

## How it works

- **One audio stream, sample-exact.** All processing happens in a custom
  `AudioProcessorChain` inside Media3's `DefaultAudioSink`. The renderer tells the chain when a
  new track starts, so ReplayGain and the crossfade switch exactly at the track boundary.
- **Crossfade without stalls.** A crossfaded track is clipped where the blend starts; its tail
  is decoded beforehand on a background thread (MP3 times counted frame by frame, as the
  player does, since seeking in VBR files is only estimated), aligned to what was played by
  cross-correlation within a few seconds, and mixed into the next track as it arrives. The
  next track is never re-clipped while the player may already be reading it. Decoder padding at the clip
  point is detected and dropped.
- **Analysis once.** Each file is decoded once, before it plays, to find its audible bounds, build
  its waveform and measure its loudness (ITU-R BS.1770 K-weighting, gated 400 ms blocks); results
  are cached in SQLite and invalidated when the file changes.
- **One walk of the library.** The music folder is walked once per scan; the analysis, inclusion
  rules and list generation that follow, and the folder search, reuse that walk.
- **Chaining.** Only the first and last seconds of sound of each file are decoded. Each edge is
  described by its chroma (share of each pitch class), spectral shape in 16 bands, level, onset
  activity, 13 cepstral timbre coefficients over a mel filter bank and its tempo with the beat's
  clarity. A link scores tempo (half or double counting as equal), timbre, spectral shape,
  activity, key compatibility (circle of fifths) and chroma, with weights learnt from music cut
  where it flows on; a list is ordered greedily, always following with the remaining track whose
  start is closest to the end of the previous one, and then improved by moving runs of one to
  three tracks wherever the links gain as a whole (weak links count more), keeping the artist
  spacing. Thirty seconds from the middle give the
  track's features (level, dynamics, brightness, bass, noisiness, percussiveness, tempo, key
  mode, voice); moods come from energy and positivity. The sound profile is the embedding of a
  MobileNet sound classifier (YAMNet's weights, 16-bit) run in pure Kotlin on four one-patch
  windows spread over each of three stretches (at a quarter, half and three quarters of the
  track), with their timbre coefficients; only those windows are decoded, each file opened once
  for its edges and its windows;
  families come from k-means over its main directions (principal components) and the timbre.
  Files are analysed in parallel on their own low-priority thread pool, each worker taking the
  next file when it finishes one; a partial wake lock, renewed with every file, keeps the pass
  going with the screen off, and a foreground service under its progress notification keeps
  Android from treating the app as idle. The threads stay out of the system's background group
  (which many phones keep on their slowest cores) unless music is playing.
- **Files known again after a clock change.** Each cache (tags, analysis, chaining) knows a file
  by its size and modification time; memory cards keep that time as local time, so a change of
  summer/winter time shifts every file by an hour once the phone restarts. With the same size, a
  shift of whole or half hours (up to 14) counts as the same file: nothing is read or analysed again.
- **A memory card mounted late.** Right after the phone restarts, Android may start the player
  before the card with the music is mounted. The player waits for the music folder (and the track
  it left off on) before loading the list, a track whose folder is not there yet is waited for
  instead of skipped, and a cover not readable yet is not remembered as missing.
- **Big collections in memory.** The collection lists are made from is kept ready, without the
  sound profiles (they only make the families and likeness vectors); profiles are held as bytes and
  the principal components are fitted on a sample, so a collection of tens of thousands of tracks
  takes some megabytes. An old collection is let go before a new one is made.
- **Artist spacing kept.** After the greedy chaining, a track that had to come too soon after
  another of its artist (only such tracks were left) is moved to where it keeps the spacing and
  loses least in links; the Rockbox plugin and the web page do the same.
- **Large lists.** Tags live in a persistent cache; sorting and album shuffles rebuild the list
  around the playing track in a handful of operations, so playback is never interrupted.

## Testing

The JVM unit tests run the real processors inside Media3's `AudioProcessingPipeline`,
reproducing how the audio sink drains and flushes at track changes:

```bash
./gradlew testDebugUnitTest
```

They cover the DSP stages, loudness measurement (EBU Tech 3341 cases), gapless joints through the whole chain, crossfade alignment and track changes, ReplayGain, playlist parsing
and portable paths, denormal-free filters, silence trimming, sorting, album shuffle and its artist spreading, edge descriptors, chaining,
track features, moods, families and list generation, parametric EQ bands and correction files, the
true-peak limiter, smart-list rules and the list tools.

## Languages

English (default), Spanish (Spain and Latin America), Catalan, Basque, Galician, Portuguese
(Portugal and Brazil), French, Italian, German, Dutch, Swedish, Polish, Romanian, Greek, Russian,
Ukrainian, Turkish, Japanese, Korean and Chinese (simplified and traditional). Translations live in
`app/src/main/res/values-*/strings.xml`; the language can be set in Settings → Language.

## Roadmap

- Network folders maybe later (local music is the focus: no internet radio).
- Maybe later: more formats through FFmpeg, bit-perfect USB DAC output.

## Third-party libraries

- [AndroidX Media3](https://developer.android.com/media/media3) (ExoPlayer, session) — Apache 2.0
- [Jetpack Compose](https://developer.android.com/jetpack/compose), Material 3 and Material
  Icons, AndroidX — Apache 2.0
- [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines) — Apache 2.0
- [AndroidX Palette](https://developer.android.com/jetpack/androidx/releases/palette) (accent
  color from the cover) — Apache 2.0
- [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger) (tag editing) — LGPL 2.1
- [YAMNet](https://www.kaggle.com/models/google/yamnet) sound classifier weights (sound families
  and likeness; run by Attacca's own code) — Apache 2.0

## License

Attacca is released under the [MIT License](../LICENSE). Third-party libraries keep their own
licenses (see above); the app lists them, with their versions, in its About screen.
