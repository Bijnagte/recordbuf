#!/usr/bin/env bash
# Downloads an Amazon Corretto JDK for each jlink target platform into <dest>/<platform>, which is where
# the generator's JReleaser jlink config looks (generator/build/target-jdks). jlink builds a runtime for
# another OS/architecture when it is given that platform's JDK, so one machine can build every zip.
#
# Why Corretto and not Temurin: cross-platform jlink needs the TARGET JDK's jmods directory. Temurin 25
# no longer ships jmods (JEP 493 lets a JDK link only itself without them), so a Temurin target cannot be
# used from another platform. Corretto 25 still ships them. The runtime inside each release zip is
# therefore Corretto's.
#
# Usage: scripts/fetch-target-jdks.sh <java-feature-version> <dest-dir> [platform ...]
#   scripts/fetch-target-jdks.sh 25 generator/build/target-jdks
#   scripts/fetch-target-jdks.sh 25 generator/build/target-jdks linux-x86_64     # just one
#
# The feature version must match the JDK that runs Gradle (jlink requires the same major version).
# Each archive is verified against the SHA-256 that Corretto publishes for it.
set -euo pipefail

feature="${1:?usage: $0 <java-feature-version> <dest-dir> [platform ...]}"
dest="${2:?usage: $0 <java-feature-version> <dest-dir> [platform ...]}"
shift 2

# <JReleaser platform> <Corretto arch> <Corretto os>
all_platforms="linux-x86_64 x64 linux
linux-aarch_64 aarch64 linux
osx-x86_64 x64 macos
osx-aarch_64 aarch64 macos"

wanted="${*:-linux-x86_64 linux-aarch_64 osx-x86_64 osx-aarch_64}"
mkdir -p "$dest"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

for platform in $wanted; do
  line="$(printf '%s\n' "$all_platforms" | grep "^$platform " || true)"
  [ -n "$line" ] || { echo "unknown platform: $platform" >&2; exit 1; }
  # shellcheck disable=SC2086
  set -- $line
  arch="$2"; os="$3"
  file="amazon-corretto-$feature-$arch-$os-jdk.tar.gz"

  echo "==> $platform ($file)"
  archive="$work/$platform.tar.gz"
  curl -fsSL -o "$archive" "https://corretto.aws/downloads/latest/$file"
  expected="$(curl -fsSL "https://corretto.aws/downloads/latest_sha256/$file" | awk '{print $1}')"
  actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
  if [ -z "$expected" ] || [ "$expected" != "$actual" ]; then
    echo "checksum mismatch for $platform: expected '$expected', got '$actual'" >&2
    exit 1
  fi

  rm -rf "$work/extract" "${dest:?}/$platform"
  mkdir "$work/extract"
  tar -xzf "$archive" -C "$work/extract"
  root="$(find "$work/extract" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  # macOS archives nest the JDK home under Contents/Home; Linux archives are the home itself.
  if [ -d "$root/Contents/Home" ]; then root="$root/Contents/Home"; fi
  [ -d "$root/jmods" ] || { echo "no jmods directory in the $platform JDK" >&2; exit 1; }
  mv "$root" "$dest/$platform"
  echo "    ok: $dest/$platform"
done
