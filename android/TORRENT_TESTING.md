# Torrent cache fix: validation and remaining work

This is a local draft against `morad3741/Local-Cache:main`, commit
`895f47c2cf530a6ba1a991bd4313f2c899f11c3c`. It is not a TV-validated fix.

## Changes under test

- Fetch metadata in a fresh empty directory with `UPLOAD_MODE` set and
  `AUTO_MANAGED` cleared in the add parameters, before the handle exists.
- Copy the metadata, close/remove its handle, and carry its connected peer endpoints
  into a fresh payload add. Tracker and explicit magnet peer parameters are preserved.
  This reconnect step needs native startup-time measurement before release.
- Supply the selected `.part` path, safe names for skipped files, and file priorities
  in `add_torrent_params`. The payload handle therefore starts with its storage
  mapping; no asynchronous `renameFile` operation remains in this flow. Rename-alert
  success/failure/delay/foreign-event tests are consequently not applicable here.
- Let the fresh payload add verify existing files normally, with no guessed
  have-pieces, seed mode, `NO_VERIFY_FILES`, or trusted resume data. Completion requires
  the selected pieces, native disk closure, and finalization of that mapped file only.
- Keep checking time separate from network time. A progressing check can exceed five
  minutes; ten minutes without checking progress fails with a storage message. Network
  first-data/stall limits remain bounded. Publish no readable piece map while checking.
- Read worker snapshots on UI/HTTP paths. Keep HTTP playback waiting during checking;
  the worker owns its progress watchdog. Native/storage errors stop the worker promptly.
- Keep cancellation teardown and deletion in the worker job. A replacement joins the
  complete predecessor chain. Wait for the matching disk-closed paused alert and
  removed handle before reuse. Failure to confirm closure blocks further mutation.
  Quota/wipe operations also protect pending writers and native auxiliary files.

## Commands and observed results in this environment

From the repository root:

```sh
bash tools/check-torrent-policy.sh
bash -n tools/check-torrent-policy.sh
git diff --check
```

**PASS:** 20 dependency-free Java policy/file checks; shell syntax and diff checks
passed. The Java checks use a controlled monotonic clock and temporary files with
sentinels, including a sparse file with 97 GiB logical length, collisions, symlinks,
selected-file offsets, seeks, checking progress, and network stalls. They exercise
the production `TorrentProgress` and `TorrentFiles` classes, not JNI or Android.
The runner uses the JDK compiler module and targets Java 17. Its first attempt using
`--release 17` failed because this environment's trimmed JDK lacks that release's
symbol data; `-source 17 -target 17` compiled and ran successfully.

From `android/`, these commands were attempted:

```sh
GRADLE_USER_HOME=/workspace/gradle-home bash ./gradlew :app:tasks --all
GRADLE_USER_HOME=/workspace/gradle-home bash ./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug :app:lintStandardDebug
GRADLE_USER_HOME=/workspace/gradle-home bash ./gradlew -PtorrentNativeTests=true :app:testStandardDebugUnitTest --tests app.localcache.torrent.TorrentNativeTest
```

All exited **1 during wrapper bootstrap**, before project configuration, while
downloading `gradle-9.3.0-bin.zip`, with `java.net.SocketException: Operation not
permitted`. Gradle tasks could not be discovered. The Kotlin/Gradle changes have
therefore **not been compiled** in this environment.

| Check | Actual result |
| --- | --- |
| Dependency-free policy/file suite | PASS, 20 checks |
| Baseline Gradle tasks/build/tests/lint | NOT RUN; wrapper bootstrap blocked by executor network access |
| Full Gradle JVM suite, including four registry regressions | NOT RUN; same bootstrap blocker |
| Linux JNI suite with a real synthetic seeder | NOT RUN; same bootstrap blocker; dependencies unavailable |
| Android APK build and lint | NOT RUN; same bootstrap blocker; Android SDK tools unavailable |
| Three Android lifecycle/cached-playback tests | NOT RUN; no SDK, adb, emulator, or attached device |
| Regression red/green against original TorrentEngine | NOT RUN; requires the native/full app toolchain |
| TCL SDK 34 + USB playback, seek, responsiveness | NOT RUN; no TV/USB access |
| Independent review | NOT RUN; self-review only |

Do not treat the passing helper checks as validation of `renamed_files`, upload-mode
metadata discovery, JNI compatibility, alert ordering, storage closure, connection
speed, full Kotlin compilation, or TCL playback.

## Running the complete suites

Use a full JDK 17 or newer and Android SDK platform 34 with the build tools required
by the repository's Android plugin. Install adb and use a dedicated Android test
device for instrumentation. Run the task discovery command above first.

The normal Gradle unit task runs `TorrentPolicyTest` (the same 20 checks) and four
`CacheRegistryTorrentTest` cases. JNI tests are excluded by category unless
`-PtorrentNativeTests=true` is supplied. That opt-in adds the matching Linux x86_64
native artifact to test runtime and excludes Android Bionic native jars from the
host unit-test runtime. It does not change APK dependencies.

`TorrentNativeTest` runs the production engine against synthetic multi-file content
and a loopback seeder. Its selected movie has a nonzero offset and shared boundary
pieces. Seven cases cover storage approval, diagnostics off, original-path sentinels,
corrupt/sparse resume, colliding paths, cancellation in metadata/setup/payload, late
writes, and retry after clean session shutdown. Real network transfer checks use
short bounded observations; timeout policy tests use the controlled Java clock.

On a dedicated test device:

```sh
bash ./gradlew :app:connectedStandardDebugAndroidTest
```

`DownloadLifecycleTest` uses real Android preferences isolated by name, temporary
storage, production coroutines/HTTP, and a local MockWebServer. It covers wipe versus
an active writer, deferred selected deletion, rapid HTTP A -> B -> A switching, and
playing a finalized torrent with new torrent downloads disabled.
It does not establish torrent JNI or USB behavior.

Before making this release-ready, run the native tests against original `main` and
the fix with the same test fixtures, record the actual red/green outcomes, and add
native failure/late-release and cancellation-during-a-long-check coverage. Do not
mark unexecuted cases as passing. Obtain an independent review of the diff and results.

## TCL/USB acceptance run

Use synthetic or freely licensed content, not personal torrents or logs. Record the
exact APK versionCode and build commit, TV model/SDK, USB filesystem and free space.

1. Test a small file and large/sparse fixtures, plus a multi-file selection with a
   nonzero offset. Verify the approved path and selected bytes, and measure time
   from click to metadata, checking, first playable bytes and stable playback.
2. Resume a deliberately incomplete/corrupt `.part`. Confirm hash checking occurs,
   progress is shown separately, no old bytes are exposed, and a check progressing
   beyond five minutes is allowed. Verify an unplugged/unresponsive drive and a dead
   swarm produce a clear bounded failure.
3. Exercise play, tail probes, forward/back seek, peer rediscovery after checking,
   remote-control navigation, phone settings and notification updates during USB IO.
4. Cancel/delete during metadata, setup, checking and payload; immediately retry and
   switch A -> B -> A. Confirm a single writer, no late writes, and preserved sentinel
   files. Exercise quota cleanup, wipe and storage selection while stopping.
5. Check HTTP/debrid playback and resume, final-file playback, explicit file choice,
   and error reporting. Collect sanitized ANR/crash traces if the black screen recurs.

## Scope boundaries

Fast Resume persistence is not implemented. The JSON sidecar remains metadata only.
Automatic initial verification can still take substantial time on a large USB file.
Re-adding the payload reconnects peers; its connection behavior is not yet validated.

Historical original torrent directories and dormant native partfiles are not cleaned
recursively. Newly mapped skipped paths stay separate from the original metadata
paths. The draft only deletes its own empty metadata directory. Nested/sparse/physical
quota accounting and comprehensive orphan cleanup remain separate work; the quota
changes here protect writers and serialize deletion without changing its size model.

The reported black screen, crash and USB/native performance have not been reproduced.
Startup storage scans and service shutdown can still perform blocking work; cached
status/readability do not establish that every UI/lifecycle path is nonblocking.

Keep a PR **Draft** until core build/JVM/native validation and review have run. TV
validation and any remaining acceptance gaps must be stated explicitly in its body.
