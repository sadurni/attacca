# Attacca Web

One HTML page (`attaccaplg.html`) that makes **chained** playlists in the browser from the library
exported by Attacca Desktop (`attaccaplg.bpcr2`, the same file the Rockbox plugin reads). Nothing to
install and nothing sent anywhere: the file is read in the browser.

- **From a song** (search by artist, title, album), with same mood (a song between two moods counts
  as either) and sound family, and **Fit the
  filters to the song** (10 times the songs asked for, at least twice), as Attacca.
- **By filters**: mood, sound family, artist, genre tag, years, BPM, song length (1 to 20 minutes
  unless changed), energy, positivity, voice, only included. 150 songs per list unless changed.
- **Automatic**: one list per mood and sound family.
- **Random song of a mood**: a song at random among those of the moods chosen (of any without one),
  and the songs closest to it, as Attacca's random list. The **bridge** can swap its ends.
- **Reorder a list**: an M3U/M3U8 (from any device) in chained order; songs are found by their
  path.
- Lists shown before downloading (songs, length, how well they chain); downloaded as M3U8 with the
  paths of the music folder given (the one exported by default; Windows or Unix style).
- In the languages of Attacca (English, Spanish, Catalan, Galician, Basque, Portuguese, Brazilian
  Portuguese, French, Italian, German, Dutch, Swedish, Polish, Romanian, Russian, Ukrainian, Greek,
  Turkish, Japanese, Korean, Simplified and Traditional Chinese), the browser's at first.

The scoring is the Rockbox plugin's (integers): both give the same lists from the same file.

Export the file in Attacca Desktop (**Attacca → For Rockbox and the web generator**) or with
`attacca-desktop player-db attaccaplg.bpcr2 --path-to "/storage/emulated/0/Music"`, open
`attaccaplg.html` and drop the file on it.
