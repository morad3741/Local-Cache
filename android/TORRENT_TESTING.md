# Torrent reliability and TCL C8K USB validation

This PR targets large torrent downloads to USB, including 80–90 GB remux files.
A passing host/emulator run does not establish throughput, filesystem compatibility,
or playback stability on the actual TCL C8K.

## Storage, cancellation and playback invariants

- Metadata runs in upload mode with auto-management disabled, before the payload
  has a handle. Approval uses the selected file's real size.
- A fresh payload add receives its mapped cache filenames and file priorities in
  its add parameters. No asynchronous file rename gates network writes.
- Existing sparse files undergo native verification. Logical length never grants
  readable bytes or completion. Completion requires verified selected pieces,
  matching storage-close/removal barriers, and finalization of the mapped file.
- Native alert handles are borrowed memory. Compare them with the worker-owned
  handle during the callback, then publish only scalar paused/removed/error state.
  No borrowed alert handle is retained for a later worker poll.
- Checking uses a progress watchdog separately from network time. Rechecking
  invalidates old readable snapshots and progress high-water marks.
- Cancellation/replacement joins the preceding worker including its teardown and
  deferred deletion. Failed storage closure blocks further mutation.
- The foreground service owns an asynchronous, serialized process lifecycle. Stop
  fences new download admission, cancels HTTP/native workers, joins the entire
  predecessor chain, then closes libtorrent before a replacement can start. Old
  service generations cannot stop their replacements. A failed close is retried
  before new startup. No native destruction runs on the Android main thread.
  Playback requests rejected by admission receive 503 with Retry-After instead
  of waiting for a worker that was never started.
- Quota reserves logical size, while physical space reserves the unwritten
  allocation measured with `st_blocks * 512`. Unknown allocation conservatively
  reserves the full file. After eviction, re-read real usable space rather than
  adding a sparse file's logical length to it. Keep 5% drive headroom, capped at
  5 GiB, with a 64 MiB minimum. Oversized movies are rejected before eviction.
- Known FAT32 mounts reject files above 4 GiB before payload transfer. Filesystem
  detection through Android FUSE is incomplete; a successful large write still
  needs validation on the TV's firmware.
- Registry label updates preserve the entry held by the worker. Playback refreshes
  recent-access protection while reading, including during long HTTP responses.

## Work bounded for TV CPU and memory

No CPU/RAM specification is inferred from the model name. Diagnostics record the
actual model, SDK, ABI, processor count, system RAM, Java heap limit and native heap.

The session uses POSIX disk IO, one download and one checking job. Constrained
devices use 40 peer connections, a 4 MiB disk queue and 256 KiB maximum receive
buffer per peer; other devices use 64, 8 MiB and 512 KiB respectively. Peer discovery
is capped at 2048 entries, metadata at 16 MiB, hashing at one thread and disk IO at
one or two threads. These are resource limits, not measured TCL performance claims.

Worker piece reads are bounded to a 4096-piece prefix scan and a 1025-piece seek
snapshot plus opening/cue bits. Piece priority changes use one batched delta instead
of constructing a vector for every piece in the movie. The seek window advances as
the HTTP response consumes it; UI/HTTP paths publish offsets and read Java snapshots.

An active transfer renews the bounded wake lock every hour, so it can continue past
the original eight-hour timeout. Actual TV standby and long-duration behavior remain
device acceptance work. HTTP's 90-second read timeout measures inactivity, not total
download duration.

USB recovery, server startup/trim and explicit TV storage probes/config/deletion
run in the background. UI status reads never wait on the server lifecycle monitor.
Space summaries use one background scan at most every 30 seconds, with no queue of
overlapping polls. They can therefore lag actual disk usage by up to 30 seconds;
download approval always reads current space directly.

HTTP downloads approve size from the actual GET response, removing separate
HEAD/range probes and making header waits cancellable. Identity encoding and
64-bit Content-Range/length checks protect resume offsets. A shorter successful
range stays incomplete; a server ignoring Range restarts from zero. Finalization
requires the advertised response and representation sizes to match received bytes.
This does not authenticate the content or add persistent ETag validation.

## Diagnostics

Enable diagnostic logging in `/settings` before reproducing a problem and export
`/logs` afterwards. Logging is opt-in.

This test build is **0.5.9 (version code 81)**. Every diagnostic record in Logcat
and persisted/exported events includes `appVersion` and `versionCode`, so rolled
logs remain attributable after an upgrade. The export header and startup event
also identify the package, flavor and build type. HTTP user agents use the build's
actual version instead of stale hardcoded values. Startup identity reaches Logcat
even when detailed recording is off; file persistence remains opt-in.

Every export has a retained hardware-context section, even after ordinary events
leave the bounded log. It includes the Android-reported TV manufacturer/model,
product/board/hardware, SoC name on API 31+, Android version and firmware/build/security
patch, ABI/cores and measured RAM/heap. Selected storage includes mode, label/root/path,
visible filesystem/mount/read-only state, capacity/free/usable bytes and cache limit,
plus Android volume description/UUID/state/removable/emulated information.

USB mass-storage candidates include vendor/product IDs, manufacturer/product names,
device release and bus address when Android exposes them. No USB permission prompt,
connection opening, write probe or serial-number access is used. Public Android APIs
do not reliably map a USB device to a volume or expose negotiated link speed; logs
mark these unavailable. A visible FUSE mount does not identify the underlying exFAT
or NTFS format. SD readers or firmware may hide the physical card's model entirely.

Inventory runs on a separate single background worker, coalesces requests and caches
samples for up to one minute. Selection changes trigger a new sample. Export shows
sample age/matching state and pending/unavailable fields; a slow old drive's result
is never presented as the new selection. It cannot hold up log export or persistence.

Each transfer has a correlation ID. Torrent logs include metadata/setup/teardown
timings, state and flags, checking progress, readable/verified bytes, throughput,
peer/seed/candidate counts, DHT nodes, tracker failures, peer disconnections,
native error codes and operations, drive space and periodic memory/network context.
HTTP logs include DNS resolution, connection/TLS progress, response status and range,
payload progress, time spent reading the network, buffered writes, flushes and sync.
Runtime logs mark storage recovery, startup, writer joins and native close, with
begin/end/error events and elapsed time so an unfinished stage can be identified.

High-volume native events are counted and rate-limited. Torrent progress is sampled
every 15 seconds after the opening samples. Log lines redact full signed URLs,
tracker path/query passkeys, magnet links and common credential fields, retaining
hosts, ports and error details.

Persistence uses a bounded background queue and internal app storage, so it cannot
block an alert callback on USB IO. Two rotating files are capped at 1 MiB each,
memory retains at most 900 bounded lines, and exports show dropped-line/write-failure
counters and a sanitized tail from the previous process. Abrupt process death can
lose the final queued second; rotation intentionally limits retention.

## Automated checks

From the repository root:

```sh
bash tools/check-torrent-policy.sh
bash -n tools/check-torrent-policy.sh
git diff --check
```

The dependency-free suite runs **29 behavioral checks** against production Java:
checking/network/rechecking time budgets, offset/hole/seek boundaries, bounded work
on a 90 GiB small-piece torrent, separate logical/physical space budgets, TV buffer
limits, credential redaction, and finalization/path preservation including a sparse
97 GiB file. It does not transfer a real 90 GiB payload.
The same command also runs **5 blocked-backend lifecycle scenarios** and **11 HTTP
size/range cases**, including a 90 GiB representation and malformed resume responses.

A controlled recheck regression was run against the original PR's
`TorrentProgress.java`: it rejected healthy new progress at minute 13. The identical
harness passes against the fix.

With JDK 17 and Android SDK platform 34 / build tools 35.0.0:

```sh
cd android
bash ./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug :app:lintStandardDebug
bash ./gradlew -PtorrentNativeTests=true :app:testStandardDebugUnitTest --tests app.localcache.torrent.TorrentNativeTest
bash ./gradlew :app:connectedPersonalDebugAndroidTest
```

The Gradle wrapper is pinned to 8.13 for AGP 8.13.2. The normal JVM suite includes
the policy runner, five registry regressions and two lifecycle/HTTP policy runners.
Eight opt-in Linux x86_64 JNI
tests run the production engine against a synthetic local seeder: approval gating,
mapped nonzero-offset/shared pieces, sparse/corrupt resume, path collisions,
cancellation in metadata/setup/payload, shutdown/retry and repeated alert/storage
release. The host native artifact is explicitly extracted and selected through
`libtorrent4j.jni.path`; it never enters the APK.

Ten Android API 34 instrumentation tests cover active-writer wipe protection,
deferred deletion, rapid A → B → A replacement, cached torrent playback with new
torrents disabled, concurrent bounded/redacted diagnostic persistence, cancellation
of a GET with stalled headers and restart fencing, incomplete successful ranges,
preservation of a partial when the resume offset is wrong, and a real HTTP 503
response while download admission is closed. The hardware cases verify retained
context after log eviction, read-only sampling of a changed/missing storage path,
and nonblocking export while storage inventory is blocked. The `personal` flavor
supplies the emulator ABI.

The PR's **Torrent validation** workflow runs JVM/build/lint/JNI checks and Android
emulator lifecycle tests, retaining reports as artifacts and the standard debug APK
as `local-cache-standard-debug` for the TV acceptance run. The run linked from the
PR body is the authority for the exact tested commit and results. The initial review
caught two Kotlin peer-vector compilation errors; running the previously unexecuted
JNI suite also exposed the missing host library extraction and a private-torrent
fixture that disabled the metadata extension it needed to test.
Loopback peers are assigned to the seeder's global rate-limit class so cancellation
and shutdown tests cannot accidentally run after an unlimited local transfer finishes.

## Actual TCL C8K acceptance still required

Record the APK commit/version, full model/firmware/ABI, measured RAM/heap, USB model,
port, filesystem, capacity and free space. Use a format that this firmware can both
read and write with files above 4 GiB; do not infer exFAT/NTFS support from a brand name.

1. Download a small synthetic file, then an 80–90 GB remux to USB. Capture metadata,
   checking, first readable bytes, sustained payload/write rates, memory pressure
   and UI responsiveness, with and without playback.
2. Resume an incomplete/corrupt large file. Confirm normal hash checking, increasing
   check progress beyond five minutes, no exposure of unchecked bytes, and correct
   physical free-space reservation. Test a full drive and an unplugged/read-only USB.
3. Exercise opening/tail probes, forward/back seeks beyond one snapshot window,
   Ethernet/Wi-Fi disconnect and recovery, unavailable trackers and a dead swarm.
   Distinguish DNS/connect failures, lack of peers and disk backpressure in the log.
4. Cancel/delete during metadata, checking and payload, then immediately retry and
   switch A → B → A. Verify sentinels, a single writer and no late writes.
5. Test an overnight transfer exceeding eight hours and TV standby/wake. Capture
   sanitized Android crash/ANR traces if the original black screen recurs.

Fast Resume persistence, recursive historical-directory/orphan cleanup and comprehensive
nested quota accounting remain separate work. The black screen's cause and the TV's maximum USB/network throughput remain
unconfirmed. Keep this PR Draft until the remaining device acceptance gaps are
explicitly reviewed; host/emulator checks alone do not verify the TCL fix.
