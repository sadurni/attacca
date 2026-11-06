#!/bin/bash
# Builds the Linux version of Attacca Desktop (run inside Linux or WSL; jpackage only makes a
# system's own formats). Copies desktop/ and core/ to ~/attacca-build, runs the tests and the
# packaging with the JDK 17 in $JAVA_HOME (default ~/jdk17), and leaves in desktop/build/linux/:
#   attacca-desktop_<v>-1_amd64.deb             installs in /opt/attacca-desktop, with a menu entry
#   AttaccaDesktop-<v>-linux-x64.tar.gz         the same app unpacked anywhere (brings its own Java)
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$HOME/attacca-build}"
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk17}"
export PATH="$JAVA_HOME/bin:/usr/bin:/bin"

mkdir -p "$WORK"
for part in desktop core; do
    rsync -a --delete --exclude build --exclude .gradle --exclude .kotlin --exclude .idea "$REPO/$part/" "$WORK/$part/"
done
# The phone's gallery of themes (their colour files), which the desktop packs.
mkdir -p "$WORK/android/app/src/main/assets/themes"
rsync -a --delete --include '*/' --include '*.json' --include '*.jpg' --include '*.png' --include '*.webp' --exclude 'icon-*' --exclude '*' "$REPO/android/app/src/main/assets/themes/" "$WORK/android/app/src/main/assets/themes/"
cd "$WORK/desktop"
sed -i 's/\r$//' gradlew
chmod +x gradlew
./gradlew --no-daemon -q test createDistributable packageDeb

# build.gradle.kts has Windows line ends: no CR in the version.
VERSION=$(tr -d '\r' < build.gradle.kts | sed -n 's/^version = "\(.*\)"/\1/p')
OUT="$HERE/build/linux"
mkdir -p "$OUT"
cp build/compose/binaries/main/deb/*.deb "$OUT/"
tar -czf "$OUT/AttaccaDesktop-$VERSION-linux-x64.tar.gz" -C build/compose/binaries/main/app AttaccaDesktop
ls -la "$OUT"
