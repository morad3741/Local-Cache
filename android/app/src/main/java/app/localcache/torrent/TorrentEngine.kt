package app.localcache.torrent

import android.util.Log
import app.localcache.DiagLog
import app.localcache.storage.CacheEntry
import org.libtorrent4j.AlertListener
import app.localcache.DeviceDiagnostics
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TorrentPausedAlert
import org.libtorrent4j.alerts.TorrentRemovedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TrackerErrorAlert
import org.libtorrent4j.alerts.PeerErrorAlert
import org.libtorrent4j.alerts.PeerDisconnectedAlert
import org.libtorrent4j.alerts.ListenFailedAlert
import org.libtorrent4j.swig.add_torrent_params
import org.libtorrent4j.swig.byte_vector
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.int_string_map
import org.libtorrent4j.swig.int_byte_pair
import org.libtorrent4j.swig.int_byte_pair_vector
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.peer_info_vector
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.status_flags_t
import org.libtorrent4j.swig.torrent_info
import org.libtorrent4j.swig.tcp_endpoint_vector
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * BitTorrent side of the cache, used when a stream is a magnet instead of a debrid HTTP link.
 *
 * Everything is shaped to look exactly like the HTTP downloader to the rest of the app: one
 * torrent at a time, one video file, written to the same `<cacheKey>.<ext>.part` path, renamed
 * to the final name when it finishes. Playback then reads the growing file the same way.
 *
 * The one thing callers must respect is that a partially downloaded torrent file is **sparse**:
 * `File.length()` reports the full size from the first byte written. [CacheEntry.downloadedBytes]
 * is the contiguous, actually-readable prefix and is the only safe measure for playback.
 */
object TorrentEngine {
    private const val TAG = "TorrentEngine"

    /** Magnet metadata is usually seconds; a dead torrent should not hang the queue for long. */
    private const val METADATA_TIMEOUT_MS = 90_000L

    /** Give a torrent this long to produce its first byte before calling it dead. */
    private const val FIRST_BYTES_TIMEOUT_MS = 5 * 60_000L

    /** Stop waiting when a live torrent stops making progress entirely. */
    private const val STALL_TIMEOUT_MS = 10 * 60_000L

    private const val CHECKING_STALL_TIMEOUT_MS = 10 * 60_000L
    private const val DISK_RELEASE_TIMEOUT_MS = 60_000L

    private const val POLL_MS = 1_000L

    /** Upload cap so seeding never competes with the movie the user is watching. */
    private const val UPLOAD_LIMIT_BYTES_PER_SEC = 1024 * 1024

    /** Pieces to mark TOP when playback seeks past what has been downloaded. */
    private const val SEEK_PIECE_WINDOW = 8

    /** Opening window we keep at TOP_PRIORITY. One JNI array, not 24 deadline calls. */
    private const val HEAD_PIECES = 8

    /**
     * Players probe the last megabytes of an MKV for the cue index. Treating that as a
     * seek makes libtorrent fetch the end of the file and leave a hole at piece 1, which
     * looks like 0% forever.
     */
    private const val TAIL_PROBE_BYTES = 16L * 1024 * 1024

    /**
     * How far ahead of the playhead to look for readable pieces. Playback only ever reads a
     * couple of MB at a time, so scanning a bounded window of the snapshot is sufficient.
     */
    private val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "avi", "m4v", "mov", "ts", "webm", "mpg", "mpeg")

    private val lock = Any()

    @Volatile
    private var session: SessionManager? = null

    @Volatile
    private var loadError: String? = null

    /** The torrent currently being written, so playback can ask for pieces it needs sooner. */
    @Volatile
    private var active: Active? = null

    /** What the engine is doing right now, for the notification and /settings. */
    @Volatile
    private var phase: String? = null

    @Volatile
    private var storageError: String? = null

    /** A failed native shutdown must never permit deletion or another writer. */
    fun storageReleaseError(): String? = storageError

    @Volatile
    private var listenerAttached = false
    private val alertCounts = ConcurrentHashMap<AlertType, AtomicLong>()
    private val lastAlertAt = ConcurrentHashMap<AlertType, Long>()
    private val alertContexts = ConcurrentHashMap<Long, String>()

    private val alertListener = object : AlertListener {
        override fun types(): IntArray? = null

        override fun alert(alert: Alert<*>) {
            if (!DiagLog.enabled) return
            val type = runCatching { alert.type() }.getOrNull() ?: return
            if (isNoisyAlert(type)) return
            runCatching {
                val count = alertCounts.computeIfAbsent(type) { AtomicLong() }.incrementAndGet()
                val now = monotonicMs()
                val critical = type == AlertType.FILE_ERROR || type == AlertType.TORRENT_ERROR || type == AlertType.LISTEN_FAILED
                if (!critical && count > 3 && now - (lastAlertAt[type] ?: 0L) < 10_000) return
                lastAlertAt[type] = now
                val context = (alert as? TorrentAlert<*>)?.handle()?.swig()?.id()?.let { alertContexts[it] } ?: "session"
                val details = when (alert) {
                    is FileErrorAlert -> "op=${alert.operation()} code=${alert.error().value}"
                    is TrackerErrorAlert -> "code=${alert.error().value} failures=${alert.timesInRow()}"
                    is PeerErrorAlert -> "op=${alert.operation()} code=${alert.error().value}"
                    is PeerDisconnectedAlert -> "op=${alert.operation()} code=${alert.error().value} reason=${alert.reason()}"
                    is ListenFailedAlert -> "op=${alert.operation()} code=${alert.error().value} socket=${alert.socketType()}"
                    else -> ""
                }
                DiagLog.t("alert attempt=$context type=$type count=$count $details ${alert.message()}")
            }
        }
    }

    private class Active(
        val cacheKey: String,
        val handle: TorrentHandle,
        val fileOffset: Long,
        val fileSize: Long,
        val pieceLength: Long,
        val firstPiece: Int,
        val lastPiece: Int,
        val numPieces: Int,
        @Volatile var cursor: Int,
        @Volatile var playhead: Long = 0L,
        @Volatile var snapshot: TorrentPieces.Snapshot? = null,
        @Volatile var checking: Boolean = true,
        @Volatile var lastPrioCursor: Int = Int.MIN_VALUE,
        @Volatile var lastPrioSeek: Int = Int.MIN_VALUE,
    ) {
        var topPieces: Set<Int> = emptySet() // Worker-owned; at most two windows and the cue piece.
    }

    /** Copy callback data while alerts are valid; correctness never depends on diagnostics. */
    private class TransferEvents : AlertListener {
        val paused = CopyOnWriteArrayList<TorrentHandle>()
        val removed = CopyOnWriteArrayList<TorrentHandle>()
        val errors = CopyOnWriteArrayList<Pair<TorrentHandle, String>>()

        override fun types(): IntArray = intArrayOf(
            AlertType.TORRENT_PAUSED.swig(), AlertType.TORRENT_REMOVED.swig(),
            AlertType.FILE_ERROR.swig(), AlertType.TORRENT_ERROR.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is TorrentPausedAlert -> paused.add(alert.handle())
                is TorrentRemovedAlert -> removed.add(alert.handle())
                is FileErrorAlert -> errors.add(alert.handle() to
                    "Storage ${alert.operation()} (code ${alert.error().value}): ${alert.error().message}")
                is TorrentErrorAlert -> errors.add(alert.handle() to "Torrent (code ${alert.error().value}): ${alert.error().message}")
            }
        }
    }

    data class Result(val ok: Boolean, val error: String? = null)

    /** True once the native library has loaded. Reports why it did not when it fails. */
    fun nativeError(): String? = loadError

    private fun ensureSession(): SessionManager? {
        session?.let { if (it.isRunning) return it }
        synchronized(lock) {
            session?.let { if (it.isRunning) return it }
            return try {
                val manager = session ?: SessionManager().also { session = it }
                if (!manager.isRunning) {
                    if (!listenerAttached) {
                        manager.addListener(alertListener)
                        listenerAttached = true
                    }
                    // mmap I/O breaks on exFAT USB sticks; posix writes are slower and reliable.
                    val params = SessionParams(defaultSettings())
                    params.setPosixDiskIO()
                    manager.start(params)
                    manager.startDht()
                    Log.i(TAG, "libtorrent session started")
                    DiagLog.i("libtorrent session started (posix disk IO, listen 0.0.0.0:0)")
                }
                loadError = null
                manager
            } catch (e: Throwable) {
                // UnsatisfiedLinkError on an ABI we did not ship lands here.
                loadError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "could not start libtorrent session: $loadError", e)
                DiagLog.e("libtorrent session failed: $loadError")
                session = null
                listenerAttached = false
                null
            }
        }
    }

    private fun defaultSettings(): SettingsPack = SettingsPack().apply {
        val tuning = DeviceDiagnostics.tuning()
        DiagLog.i("torrent session tuning $tuning ${DeviceDiagnostics.snapshot()}")
        connectionsLimit(tuning.connections)
        activeDownloads(1)
        activeSeeds(0)
        activeChecking(1)
        activeLimit(1)
        maxPeerlistSize(2048)
        sendBufferWatermark(64 * 1024)
        setMaxMetadataSize(tuning.metadataBytes)
        setInteger(settings_pack.int_types.max_peer_recv_buffer_size.swigValue(), tuning.receiveBufferBytes)
        setInteger(settings_pack.int_types.checking_mem_usage.swigValue(), tuning.checkingBlocks)
        setInteger(settings_pack.int_types.aio_threads.swigValue(), tuning.ioThreads)
        setInteger(settings_pack.int_types.hashing_threads.swigValue(), tuning.hashingThreads)
        setInteger(settings_pack.int_types.file_pool_size.swigValue(), 32)
        uploadRateLimit(UPLOAD_LIMIT_BYTES_PER_SEC)
        downloadRateLimit(0)
        // Random port, IPv4 only. Pinning 6881 plus IPv6 made the whole listen
        // setup fail on boxes where v6 is disabled or 6881 is already taken.
        listenInterfaces("0.0.0.0:0")
        setEnableDht(true)
        setEnableLsd(true)
        setDhtBootstrapNodes(
            "router.bittorrent.com:6881,dht.transmissionbt.com:6881," +
                "router.utorrent.com:6881,dht.libtorrent.org:25401",
        )
        // uTP connect timeouts ate the whole connection budget on the TV; TCP actually
        // reached seeders. Incoming uTP stays on so peers can still reach us.
        setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
        setBoolean(settings_pack.bool_types.prioritize_partial_pieces.swigValue(), true)
        maxQueuedDiskBytes(tuning.diskQueueBytes)
    }

    fun shutdown() {
        synchronized(lock) {
            active = null
            val manager = session
            if (listenerAttached && manager != null) {
                runCatching { manager.removeListener(alertListener) }
                listenerAttached = false
            }
            val stopped = runCatching { manager?.stop() }.isSuccess
            if (stopped) {
                storageError = null
                session = null
                alertContexts.clear()
                alertCounts.clear()
                lastAlertAt.clear()
            } else {
                storageError = "Could not close torrent storage; restart Local Cache"
            }
        }
    }

    /**
     * Stops exposing pieces immediately. The worker pauses, waits for disk IO, then removes
     * the handle; its owner must join that worker before deleting or reusing its paths.
     */
    fun abort(cacheKey: String) {
        val current = active ?: return
        if (current.cacheKey != cacheKey) return
        current.snapshot = null
        current.checking = true
        phase = "stopping torrent and closing storage…"
        Log.i(TAG, "aborted $cacheKey")
    }

    /** Latest worker phase/peer counts, safe to read from the notification and /settings. */
    fun statusDetail(): String? {
        return phase
    }

    fun isChecking(cacheKey: String): Boolean = active?.let { it.cacheKey == cacheKey && it.checking } == true

    /**
     * Downloads one file out of [entry]'s magnet into [partFile], then renames it to [finalFile].
     *
     * Blocks until the file is complete, cancelled, or judged dead. [onMetadata] is called once
     * the real file size is known and must return false to abort (for example: no room on disk).
     */
    fun download(
        entry: CacheEntry,
        partFile: File,
        finalFile: File,
        cancelled: () -> Boolean,
        onMetadata: (totalBytes: Long) -> Boolean,
    ): Result {
        storageError?.let { return Result(false, it) }
        val manager = ensureSession()
            ?: return Result(false, "Torrent engine unavailable: ${loadError ?: "native library not loaded"}")

        val saveDir = partFile.parentFile
            ?: return Result(false, "No cache folder for torrent download")
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            return Result(false, "Cannot create cache folder ${saveDir.absolutePath}")
        }

        val magnet = MagnetLinks.withFallbackTrackers(entry.url)
        val infoHash = MagnetLinks.infoHashHex(entry.url)
            ?: return Result(false, "Magnet has no usable info hash")
        val sha1 = runCatching { Sha1Hash.parseHex(infoHash) }.getOrNull()
            ?: return Result(false, "Magnet info hash could not be parsed")

        var handle: TorrentHandle? = null
        val metadataDir = File(saveDir, ".torrent-metadata-${UUID.randomUUID()}")
        var metadataDirCreated = false
        val events = TransferEvents()
        val attempt = "${entry.cacheKey.take(12)}-${UUID.randomUUID().toString().take(8)}"
        val startedAt = monotonicMs()
        manager.addListener(events)

        try {
            // An old writer must be quiescent before this attempt can reuse its paths.
            manager.find(sha1)?.let { leftover ->
                if (!pauseAndRemove(manager, leftover, events)) return Result(false, storageError)
            }
            if (cancelled()) return Result(false, null)

            TorrentFiles.validate(partFile, finalFile)
            entry.downloadedBytes = 0
            entry.verifiedBytes = 0
            check(metadataDir.mkdir()) { "Cannot create torrent metadata folder" }
            metadataDirCreated = true

            report(entry, "finding peers for the torrent…")
            Log.i(TAG, "adding $infoHash for ${entry.cacheKey} -> ${saveDir.absolutePath}")
            DiagLog.t(
                "torrent start attempt=$attempt hash=$infoHash key=${entry.cacheKey} save=${saveDir.absolutePath} " +
                    "part=${partFile.name} existingLogical=${partFile.length()} allocated=${app.localcache.storage.DiskQuota.allocatedBytes(partFile)}",
            )

            // Set the gate in add_torrent_params, before adding. AUTO_MANAGED could
            // otherwise clear UPLOAD_MODE while storage approval is still pending.
            val metadataHandle = addTorrent(manager, parameters(magnet, metadataDir), "$attempt/metadata")
            handle = metadataHandle
            val metadata = awaitMetadata(metadataHandle, entry, cancelled, events)
            if (cancelled()) return Result(false, null)
            if (metadata == null) {
                val peers = metadataHandle.status().numPeers()
                return Result(false, "No peers sent the torrent details ($peers peers reached)")
            }

            // Own a copy before removing the metadata handle. The payload add below is
            // a fresh torrent with its final storage mapping and initial priorities.
            val torrentInfo = TorrentInfo(torrent_info(metadata.swig()))
            // Reuse the peers that supplied metadata. A second tracker announce can be
            // rate limited, and waiting for DHT again is especially costly on a TV.
            val peerInfo = peer_info_vector()
            metadataHandle.swig().get_peer_info(peerInfo)
            val knownPeers = tcp_endpoint_vector()
            for (i in 0 until peerInfo.size) knownPeers.add(peerInfo[i].remote_endpoint())
            if (!pauseAndRemove(manager, metadataHandle, events)) return Result(false, storageError)
            handle = null

            val files = torrentInfo.files()
            val fileIndex = pickVideoFile(torrentInfo, entry.fileIndex)
            if (fileIndex < 0) return Result(false, "Torrent has no video file")

            val fileSize = files.fileSize(fileIndex)
            check(fileSize > 0) { "Selected torrent file is empty" }
            entry.totalBytes = fileSize
            Log.i(
                TAG,
                "metadata ok for ${entry.cacheKey}: ${files.fileName(fileIndex)} ($fileSize bytes, " +
                    "file $fileIndex of ${torrentInfo.numFiles()})",
            )
            DiagLog.t(
                "metadata attempt=$attempt elapsedMs=${monotonicMs() - startedAt} name=${files.fileName(fileIndex)} size=$fileSize " +
                    "index=$fileIndex/${torrentInfo.numFiles()} " +
                    "pieceLen=${torrentInfo.pieceLength()} pieces=${torrentInfo.numPieces()} " +
                    "offset=${files.fileOffset(fileIndex)}",
            )

            if (!onMetadata(fileSize)) return Result(false, entry.lastError)
            if (cancelled()) return Result(false, null)

            TorrentFiles.validate(partFile, finalFile)
            val payload = parameters(magnet, saveDir)
            val peers = payload.peers
            for (i in 0 until knownPeers.size) peers.add(knownPeers[i])
            payload.peers = peers
            payload.set_ti(torrentInfo.swig())
            val names = int_string_map()
            val priorities = byte_vector()
            for (i in 0 until torrentInfo.numFiles()) {
                // No path from torrent metadata can alias another cache entry.
                val name = if (i == fileIndex) partFile.name else ".${partFile.name}.unselected-$i"
                TorrentFiles.validateCacheFile(saveDir, File(saveDir, name))
                if (i != fileIndex && File(saveDir, name).exists()) {
                    return Result(false, "Unselected torrent cache path already exists: $name")
                }
                names[i] = name
                priorities.add((if (i == fileIndex) Priority.DEFAULT else Priority.IGNORE).swig().toByte())
            }
            payload.set_renamed_files(names)
            payload.set_file_priorities(priorities)
            val added = addTorrent(manager, payload, "$attempt/payload")
            handle = added

            val pieceLength = torrentInfo.pieceLength().toLong()
            if (pieceLength <= 0) return Result(false, "Torrent piece size was 0")
            val fileOffset = files.fileOffset(fileIndex)
            val firstPiece = (fileOffset / pieceLength).toInt()
            val lastPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt()
            runCatching { added.setSequentialRange(firstPiece, lastPiece) }
            DiagLog.t(
                "sequential flags=${flagNames(added)} first=$firstPiece last=$lastPiece " +
                    "head=${minOf(lastPiece, firstPiece + HEAD_PIECES - 1)} " +
                    "cue=$lastPiece pieceLen=$pieceLength",
            )

            val current = Active(
                cacheKey = entry.cacheKey,
                handle = added,
                fileOffset = fileOffset,
                fileSize = fileSize,
                pieceLength = pieceLength,
                firstPiece = firstPiece,
                lastPiece = lastPiece,
                numPieces = torrentInfo.numPieces(),
                cursor = firstPiece,
            )
            active = current
            applyPlaybackPriorities(current)

            if (cancelled()) return Result(false, null)
            // No resume flags or guessed have-pieces: libtorrent checks the mapped files
            // on this fresh add. Length (including sparse length) never grants trust.
            added.unsetFlags(TorrentFlags.UPLOAD_MODE)

            DiagLog.t("pump start firstPiece=$firstPiece lastPiece=$lastPiece offset=$fileOffset")
            val result = pump(
                entry,
                added,
                firstPiece,
                lastPiece,
                fileOffset,
                fileSize,
                pieceLength,
                cancelled,
                events,
                attempt,
            )
            if (!result.ok) return result

            if (cancelled()) return Result(false, null)
            active = null
            if (!pauseAndRemove(manager, added, events)) return Result(false, storageError)
            handle = null
            if (cancelled()) return Result(false, null)
            TorrentFiles.finish(partFile, finalFile, fileSize)

            entry.status = "complete"
            entry.downloadedBytes = finalFile.length()
            entry.verifiedBytes = finalFile.length()
            entry.totalBytes = finalFile.length()
            entry.lastError = null
            Log.i(TAG, "complete ${entry.cacheKey} (${finalFile.length()} bytes)")
            DiagLog.i("torrent complete attempt=$attempt elapsedMs=${monotonicMs() - startedAt} bytes=${finalFile.length()}")
            return Result(true)
        } catch (e: Throwable) {
            Log.e(TAG, "torrent failed for ${entry.cacheKey}: ${e.message}", e)
            DiagLog.e("torrent failed attempt=$attempt elapsedMs=${monotonicMs() - startedAt} exception=${e.javaClass.simpleName}: ${e.message}")
            return Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            active = null
            entry.bytesPerSec = 0
            handle?.let { open ->
                if (storageError == null) pauseAndRemove(manager, open, events)
            }
            manager.removeListener(events)
            DiagLog.t("torrent end attempt=$attempt elapsedMs=${monotonicMs() - startedAt} cancelled=${cancelled()} releaseError=$storageError")
            if (metadataDirCreated && storageError == null) metadataDir.delete() // Only our empty directory; never recursive.
            phase = null
        }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private fun parameters(magnet: String, saveDir: File): add_torrent_params {
        val error = error_code()
        val params = libtorrent.parse_magnet_uri(magnet, error)
        check(error.value() == 0) { "Invalid magnet: ${error.message()}" }
        params.setSave_path(saveDir.absolutePath)
        params.setFlags(
            params.flags.and_(TorrentFlags.AUTO_MANAGED.inv()).and_(TorrentFlags.PAUSED.inv())
                .or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                .or_(TorrentFlags.DUPLICATE_IS_ERROR),
        )
        return params
    }

    /** Blocking add runs only on the download worker; no unowned async add survives cancel. */
    private fun addTorrent(manager: SessionManager, params: add_torrent_params, context: String): TorrentHandle {
        val error = error_code()
        val handle = TorrentHandle(manager.swig().add_torrent(params, error))
        check(error.value() == 0 && handle.isValid) { "Could not add torrent: ${error.message()}" }
        alertContexts[handle.swig().id()] = context
        return handle
    }

    private fun pauseAndRemove(manager: SessionManager, handle: TorrentHandle, events: TransferEvents): Boolean {
        return runCatching {
            if (wasStopped(manager)) return true
            phase = "closing torrent storage…"
            val id = handle.swig().id()
            val startedAt = monotonicMs()
            val context = alertContexts[id]
            DiagLog.t("storage closing attempt=$context handle=$id")
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.pause()
            // torrent_paused_alert is the documented disk-closed barrier. Invalidating
            // a handle or observing find()==null alone does not prove writes have stopped.
            check(waitFor(DISK_RELEASE_TIMEOUT_MS) { wasStopped(manager) || events.paused.any { it == handle } }) {
                "Torrent storage did not close within 60 s; restart Local Cache before retrying or deleting"
            }
            if (wasStopped(manager)) return true
            DiagLog.t("storage paused attempt=$context elapsedMs=${monotonicMs() - startedAt}")
            manager.remove(handle)
            // Removed handles are invalid but still compare equal as weak pointers.
            check(waitFor(20_000L) { wasStopped(manager) || events.removed.any { it == handle } }) {
                "Torrent did not leave the session; restart Local Cache before retrying or deleting"
            }
            DiagLog.t("storage removed attempt=$context elapsedMs=${monotonicMs() - startedAt}")
            alertContexts.remove(id)
            true
        }.getOrElse {
            if (wasStopped(manager)) return true
            storageError = it.message ?: "Could not close torrent storage; restart Local Cache"
            Log.e(TAG, storageError.orEmpty())
            DiagLog.e("storage release failed error=$storageError ${DeviceDiagnostics.snapshot()}")
            false
        }
    }

    private fun wasStopped(manager: SessionManager): Boolean {
        if (manager.isRunning) return false
        // shutdown holds this lock through native destruction. isRunning=false by
        // itself is too early: SessionManager clears it before joining native threads.
        return synchronized(lock) { session !== manager && storageError == null }
    }

    private fun waitFor(timeoutMs: Long, done: () -> Boolean): Boolean {
        val deadline = monotonicMs() + timeoutMs
        while (!done()) {
            if (monotonicMs() >= deadline) return false
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                // Interruption is not proof that native disk IO has closed.
                return false
            }
        }
        return true
    }

    private fun checkNativeError(handle: TorrentHandle, events: TransferEvents, status: TorrentStatus = handle.status()) {
        events.errors.firstOrNull { it.first == handle }?.let { throw IllegalStateException(it.second) }
        val error = status.errorCode()
        check(error == null || error.value == 0) { "Torrent storage/network error: ${error?.message}" }
    }

    /** Waits for the swarm to send the file list a magnet does not carry. */
    private fun awaitMetadata(
        handle: TorrentHandle,
        entry: CacheEntry,
        cancelled: () -> Boolean,
        events: TransferEvents,
    ): TorrentInfo? {
        val deadline = monotonicMs() + METADATA_TIMEOUT_MS
        var waitTick = 0
        while (monotonicMs() < deadline) {
            if (cancelled() || !handle.isValid) return null
            checkNativeError(handle, events)

            val status = runCatching { handle.status() }.getOrNull()
            if (status?.hasMetadata() == true) {
                val info = runCatching { handle.torrentFile() }.getOrNull()
                if (info != null && info.isValid) return info
            }

            val peers = status?.numPeers() ?: 0
            report(entry, if (peers > 0) "asking $peers peers for the file list…" else "looking for peers…")
            waitTick++
            if (waitTick == 1 || waitTick % 4 == 0) DiagLog.t("metadata wait ${snapshot(handle)}")

            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return null
            }
        }
        DiagLog.w("metadata timeout ${snapshot(handle)}")
        return null
    }

    private fun report(entry: CacheEntry, message: String?) {
        phase = message
        entry.lastError = message
    }

    /** Polls the torrent, publishing the contiguous readable prefix as it grows. */
    private fun pump(
        entry: CacheEntry,
        handle: TorrentHandle,
        firstPiece: Int,
        lastPiece: Int,
        fileOffset: Long,
        fileSize: Long,
        pieceLength: Long,
        cancelled: () -> Boolean,
        events: TransferEvents,
        attempt: String,
    ): Result {
        var cursor = firstPiece
        var tick = 0
        val budget = TorrentProgress(monotonicMs(), FIRST_BYTES_TIMEOUT_MS, STALL_TIMEOUT_MS, CHECKING_STALL_TIMEOUT_MS)

        while (true) {
            if (cancelled()) return Result(false, null)
            if (!handle.isValid) return Result(false, "Torrent was removed from the session")
            val status = handle.status(status_flags_t.all())
            checkNativeError(handle, events, status)
            if (cancelled()) return Result(false, null)
            val checking = status.state().name.contains("CHECKING")
            // A recheck invalidates every previous piece snapshot, including after a seek.
            if (checking) cursor = firstPiece
            val current = active?.takeIf { it.handle === handle }
            val bits = if (checking) null else status.pieces()
            val pieceSnapshot = bits?.let {
                TorrentPieces.sample(
                    { index -> it.getBit(index) }, it.size(), cursor, firstPiece, lastPiece,
                    fileOffset, fileSize, pieceLength, current?.playhead ?: 0L,
                    status.isFinished && status.totalWantedDone() >= fileSize,
                )
            }
            if (pieceSnapshot != null) cursor = pieceSnapshot.nextPiece
            active?.let { current ->
                if (current.handle === handle) {
                    current.cursor = cursor
                    current.checking = checking
                    current.snapshot = pieceSnapshot
                    if (!checking) maybeApplyPriorities(current)
                }
            }
            val readableEnd = minOf(cursor.toLong() * pieceLength, fileOffset + fileSize)
            val contiguous = (readableEnd - fileOffset).coerceIn(0, fileSize)
            val verified = if (checking) 0L else status.totalWantedDone().coerceAtMost(fileSize)

            entry.downloadedBytes = contiguous
            entry.verifiedBytes = verified
            entry.bytesPerSec = if (checking) 0L else status.downloadPayloadRate().toLong()

            if (cursor > lastPiece) return Result(true)
            val peers = status.numPeers()
            val seeds = status.numSeeds()
            val state = status.state().name.lowercase()
            report(
                entry,
                when {
                    checking -> "checking storage · ${status.progressPpm() / 10_000}% checked"
                    peers == 0 -> "searching for peers · $seeds seeds"
                    contiguous < 4L * 1024 * 1024 ->
                        "$peers peers · ${verified / (1024 * 1024)} MB in · opening the file"
                    else -> "$peers peers · $seeds seeds · downloading"
                },
            )

            tick++
            val now = monotonicMs()
            if (DiagLog.enabled && (tick <= 3 || tick % 15 == 0)) {
                DiagLog.t(
                    "pump attempt=$attempt tick=$tick contig=$contiguous verified=$verified cursor=$cursor " +
                        "checking=$checking checkPpm=${status.progressPpm()} " +
                        "${snapshot(handle)}",
                )
            }
            if (DiagLog.enabled && tick % 60 == 0) {
                DiagLog.t("resources attempt=$attempt freeBytes=${File(entry.filePath.orEmpty()).parentFile?.usableSpace} ${DeviceDiagnostics.snapshot()}")
            }

            budget.sample(now, checking, status.progressPpm(), contiguous, verified)?.let {
                return Result(false, "$it ($state, $peers peers, $seeds seeds)")
            }

            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return Result(false, null)
            }
        }
    }

    /**
     * How far playback can read without stopping, starting at [offset]. Returns [offset] itself
     * when the piece under the playhead has not arrived, and -1 when this torrent is not the one
     * running (the caller should fall back to the contiguous count from the start of the file).
     *
     * Playback needs this rather than "bytes downloaded from byte 0": after a seek the engine
     * rushes the pieces under the new playhead, and those become readable long before the
     * sequential download has filled the gap behind them.
     */
    fun readableEndFrom(cacheKey: String, offset: Long): Long {
        val current = active ?: return -1
        if (current.cacheKey != cacheKey) return -1
        if (offset < 0 || offset >= current.fileSize) return -1
        if (current.checking) return offset

        return current.snapshot?.readableEnd(offset) ?: offset
    }

    /**
     * Notes where the player is reading. The pump applies piece priorities — this must not
     * call into libtorrent on the HTTP thread (deadlines on a busy USB stick blocked serving
     * for tens of seconds and froze the playable cursor).
     */
    fun requestOffset(cacheKey: String, offset: Long) {
        val current = active ?: return
        if (current.cacheKey != cacheKey) return
        if (offset < 0 || offset >= current.fileSize) return
        current.playhead = offset
    }

    /** True when the first piece of the video file is on disk — enough for a real container header. */
    fun hasOpeningPiece(cacheKey: String): Boolean {
        val current = active ?: return false
        if (current.cacheKey != cacheKey) return false
        return !current.checking && current.snapshot?.openingReady == true
    }

    /** True when the opening piece and the MKV cue piece are both on disk. */
    fun isReadyForPlayer(cacheKey: String): Boolean {
        val current = active ?: return false
        if (current.cacheKey != cacheKey) return false
        val snapshot = current.snapshot ?: return false
        return !current.checking && snapshot.openingReady && snapshot.tailReady
    }

    /**
     * Re-mark the opening window and the MKV cue piece when the cursor or playhead moves.
     * One `prioritizePieces` call; the old per-piece deadline loop stalled the pump on USB.
     */
    private fun maybeApplyPriorities(current: Active) {
        val nearEnd = current.playhead > current.fileSize - TAIL_PROBE_BYTES
        val startReady = current.cursor >= current.firstPiece + 8
        val seekPiece = ((current.fileOffset + current.playhead) / current.pieceLength).toInt()
        val seek = if (nearEnd && !startReady) current.cursor else seekPiece
        if (
            current.lastPrioCursor != Int.MIN_VALUE &&
            current.cursor - current.lastPrioCursor < HEAD_PIECES / 2 &&
            seek == current.lastPrioSeek
        ) {
            return
        }
        current.lastPrioCursor = current.cursor
        current.lastPrioSeek = seek
        applyPlaybackPriorities(current, seek)
    }

    private fun applyPlaybackPriorities(current: Active, seek: Int = current.cursor) {
        val top = buildSet {
            add(current.lastPiece)
            for (i in current.cursor..minOf(current.lastPiece, current.cursor + HEAD_PIECES - 1)) add(i)
            if (seek > current.cursor + 4) {
                for (i in seek..minOf(current.lastPiece, seek + SEEK_PIECE_WINDOW - 1)) add(i)
            }
        }
        val changes = int_byte_pair_vector()
        try {
            for (piece in current.topPieces + top) {
                if ((piece in current.topPieces) == (piece in top)) continue
                val priority = if (piece in top) Priority.TOP_PRIORITY else Priority.DEFAULT
                val pair = int_byte_pair(piece, priority.swig().toByte())
                try { changes.add(pair) } finally { pair.delete() }
            }
            if (changes.size > 0) current.handle.swig().prioritize_pieces_ex(changes)
            current.topPieces = top
        } finally {
            changes.delete()
        }
    }

    /** The file the user actually wants: the addon's index when sane, else the biggest video. */
    private fun pickVideoFile(info: TorrentInfo, preferred: Int?): Int {
        val files = info.files()
        val count = info.numFiles()
        if (count <= 0) return -1

        if (preferred != null && preferred in 0 until count && files.fileSize(preferred) > 0) {
            return preferred
        }

        var bestVideo = -1
        var bestVideoSize = -1L
        var bestAny = -1
        var bestAnySize = -1L

        for (i in 0 until count) {
            val size = files.fileSize(i)
            if (size > bestAnySize) {
                bestAnySize = size
                bestAny = i
            }
            val ext = files.fileName(i).substringAfterLast('.', "").lowercase()
            if (ext in VIDEO_EXTENSIONS && size > bestVideoSize) {
                bestVideoSize = size
                bestVideo = i
            }
        }
        return if (bestVideo >= 0) bestVideo else bestAny
    }

    private fun snapshot(handle: TorrentHandle): String {
        val status = runCatching { handle.status() }.getOrNull()
            ?: return "invalid=${!handle.isValid}"
        val err = runCatching { status.errorCode()?.message }.getOrNull().orEmpty()
        return buildString {
            append("state=").append(status.state())
            append(" flags=").append(flagNames(handle))
            append(" peers=").append(status.numPeers())
            append("/").append(status.numSeeds())
            append(" listed=").append(status.listPeers())
            append("/").append(status.listSeeds())
            append(" conn=").append(status.numConnections())
            append(" cand=").append(status.connectCandidates())
            append(" wanted=").append(status.totalWantedDone())
            append("/").append(status.totalWanted())
            append(" payload=").append(status.totalPayloadDownload())
            append(" rate=").append(status.downloadPayloadRate())
            append(" pieces=").append(status.numPieces())
            append(" incoming=").append(status.hasIncoming())
            append(" tracker=").append(status.currentTracker().ifBlank { "-" })
            if (err.isNotBlank() && !err.equals("Success", ignoreCase = true) && !err.equals("No error", ignoreCase = true)) {
                append(" err=").append(err)
            }
        }
    }

    private fun flagNames(handle: TorrentHandle): String {
        val flags = runCatching { handle.status().flags() }.getOrNull() ?: return "?"
        val names = mutableListOf<String>()
        fun bit(name: String, flag: org.libtorrent4j.swig.torrent_flags_t) {
            if (runCatching { flags.and_(flag).non_zero() }.getOrDefault(false)) names += name
        }
        bit("seq", TorrentFlags.SEQUENTIAL_DOWNLOAD)
        bit("paused", TorrentFlags.PAUSED)
        bit("auto", TorrentFlags.AUTO_MANAGED)
        bit("upload", TorrentFlags.UPLOAD_MODE)
        return names.joinToString("|").ifBlank { "none" }
    }

    private fun isNoisyAlert(type: AlertType): Boolean {
        val name = type.name
        return name.contains("STATS") ||
            name.contains("LOG") ||
            name.startsWith("DHT") ||
            name == "PIECE_FINISHED" ||
            name == "BLOCK_FINISHED" ||
            name == "BLOCK_DOWNLOADING" ||
            name == "BLOCK_TIMEOUT" ||
            name == "BLOCK_UPLOADED" ||
            name == "INCOMING_REQUEST" ||
            name == "INCOMING_CONNECTION" ||
            name == "REQUEST_DROPPED" ||
            name == "PEER_CONNECT" ||
            name == "PEER_SNUBBED" ||
            name == "PEER_UNSNUBBED" ||
            name == "PEER_BLOCKED" ||
            name == "UNWANTED_BLOCK"
    }
}
