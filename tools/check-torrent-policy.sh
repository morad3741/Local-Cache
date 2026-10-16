#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
checks_dir="android/app/build/torrent-policy-checks"
mkdir -p "$checks_dir"
java com.sun.tools.javac.Main -source 17 -target 17 -Xlint:-options -d "$checks_dir" \
  android/app/src/main/java/app/localcache/torrent/TorrentProgress.java \
  android/app/src/main/java/app/localcache/torrent/TorrentFiles.java \
  android/app/src/test/java/app/localcache/torrent/TorrentPolicyChecks.java
java -cp "$checks_dir" app.localcache.torrent.TorrentPolicyChecks
