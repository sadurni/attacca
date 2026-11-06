# Contributing

Thank you for wanting to help. Issues and pull requests are welcome.

## Reporting a problem

Open an issue with:

- which part (Android app, Attacca Desktop, the web page or the Rockbox plugin) and its version
  (Android: Settings → About; desktop: its About page);
- the device and system (phone model and Android version; Windows / Linux / macOS; player and
  Rockbox version);
- what you did, what you expected and what happened;
- for the Android app, `Music/Attacca/attacca-exits.txt` (why the app closed, with the error) and,
  for a problem with a transition, `Music/Attacca/attacca-transitions.txt` with the time it
  happened. They hold file names of your music: look them over before attaching them.

## Building

See the README of each part: [`android/`](android/README.md), [`desktop/`](desktop/README.md),
[`rockbox/`](rockbox/README.md), [`web/`](web/README.md). What the phone and the desktop share is in
`core/`: a change there must build and pass the tests on both (`cd android && ./gradlew
testDebugUnitTest`, `cd desktop && ./gradlew test`).

## Guidelines

- Kotlin for the app, the desktop and `core`; C for the Rockbox plugin (integer arithmetic only); one
  HTML file for the web page.
- Code, comments and texts of the interface in English; translations in the `values-*` folders (the
  app has 23 languages: a new text needs all of them).
- Everything is measured from the audio, on the user's own devices: no network services, no
  tracking, no dependencies that are not free software.
- The library formats (`.attlib`, `.bpcr2`) are read by every part: a change to them must keep the
  others reading older and newer files.
