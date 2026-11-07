# Privacy

Attacca, Attacca Desktop, Attacca Web and Attacca for Rockbox collect nothing and send nothing.

- **No internet**: the Android app does not even ask for the internet permission. Nothing it
  measures, nothing you play and none of your settings ever leave your devices. Its *Search the
  web* button for a cover only opens your own browser, with the artist and album to search for.
- **The one exception, on your request**: Attacca Desktop's **Covers** page looks for album covers
  online only when you ask it to; it sends the album and artist names to an open music database
  (MusicBrainz) and fetches the pictures from its Cover Art Archive. Nothing else is sent.
- **Everything is measured on your own devices**, from the audio files themselves (the sound
  recognition network runs on the phone or the PC, from a file shipped with the app).
- **What is stored, and where**: the analysis, the listening statistics and the settings stay in the
  app's own storage; your lists, themes, corrections of moods and logs (`attacca-exits.txt`,
  `attacca-transitions.txt`) are plain files in `Music/Attacca` on the phone, which you can read,
  copy or delete. Backups go where you choose.
- **Sharing between your devices** (the `.attlib` and `.bpcr2` files) happens only when you export a
  file and take it there yourself.
- **Attacca Web** runs entirely in your browser: the library you open is read there and never sent
  anywhere.

## Permissions of the Android app

- **Access to all files / music**: to read the music folder, the cover pictures next to the music,
  and the playlists other apps save, and to keep your own lists and themes in `Music/Attacca`.
- **Notifications**: the player's controls, and the progress of long jobs (scanning, analysing).
- **Bluetooth (connect)**: to know which device is playing, so each one can have its own sound
  profile and continue its own list.
- **Run in the background, ignore battery optimisation**: so playback, starting on connecting a
  device and long analyses are not stopped by the system.
- **Display over other apps**: only to open the player when a device it continues a list for is
  connected.
- **Change audio settings, keep the device awake**: the volume set for each device, and playback
  that does not stop with the screen off.
