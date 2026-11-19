package com.an0obis.bridge.model

import com.an0obis.bridge.net.ScoreClient
import kotlinx.serialization.Serializable
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.roundToLong

/**
 * The global "Top players" list, offline-friendly. A finished 16-board session is
 * ranked by its average matchpoint percentage (best session per player). Results
 * wait in a local file until the network allows; the last fetched list is cached.
 *
 * Nothing is ever sent unless the player agreed ([AppSettings.POST]).
 */
object GlobalScores {

    @Serializable
    data class Row(
        val playerId: String,
        val name: String,
        /** average percentage in tenths (63.4 % is 634); the server stores integers */
        val score10: Long
    ) {
        val percent: Double get() = score10 / 10.0
    }

    @Serializable
    private data class Cache(var rows: List<Row> = emptyList())

    @Serializable
    private data class PendingScore(var name: String, var score10: Long, var deviceTs: Long)

    @Serializable
    private data class PendingList(var items: MutableList<PendingScore> = mutableListOf())

    private const val CACHE_FILE = "global_scores.json"
    private const val PENDING_FILE = "pending_scores.json"

    private val queueLock = Any()
    private val flushLock = ReentrantLock()
    /** Invalidates any batch that was read before the queue was cleared, even after opting back in. */
    private var queueVersion = 0L
    private var activeSubmission: ScoreClient.Submission? = null

    /** Percentage to the integer scale of the server. */
    fun toScore10(percent: Double): Long = (percent * 10).roundToLong().coerceIn(0L, 1000L)

    fun cached(): List<Row> = try {
        BridgeStorage.readText(CACHE_FILE)
            ?.let { BridgeStorage.json.decodeFromString(Cache.serializer(), it).rows }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveCache(rows: List<Row>) = try {
        BridgeStorage.writeText(CACHE_FILE, BridgeStorage.json.encodeToString(Cache.serializer(), Cache(rows)))
    } catch (e: Exception) {
        android.util.Log.w("Bridge", "score cache save failed", e)
    }

    private fun loadPending(): PendingList = try {
        BridgeStorage.readText(PENDING_FILE)
            ?.let { BridgeStorage.json.decodeFromString(PendingList.serializer(), it) }
            ?: PendingList()
    } catch (e: Exception) {
        PendingList()
    }

    private fun savePending(p: PendingList) = try {
        BridgeStorage.writeText(PENDING_FILE, BridgeStorage.json.encodeToString(PendingList.serializer(), p))
    } catch (e: Exception) {
        android.util.Log.w("Bridge", "pending score save failed", e)
    }

    /** Queues the result of a finished session, but only for a player who agreed. */
    fun enqueue(name: String, percent: Double) {
        synchronized(queueLock) {
            if (AppSettings().leaderboardChoice != AppSettings.POST) return
            val p = loadPending()
            p.items.add(PendingScore(name, toScore10(percent), System.currentTimeMillis() / 1000))
            savePending(p)
        }
    }

    /** Saves consent and clears/cancels pending work together, without waiting for the network. */
    fun setLeaderboardChoice(choice: Int) = synchronized(queueLock) {
        AppSettings().leaderboardChoice = choice
        if (choice != AppSettings.POST) clearPendingLocked()
    }

    /** Drops everything waiting to be sent (the player turned the leaderboard off). */
    fun clearPending() = synchronized(queueLock) { clearPendingLocked() }

    private fun clearPendingLocked() {
        queueVersion++
        activeSubmission?.cancel()
        savePending(PendingList())
    }

    /** Sends every queued result; keeps the ones worth retrying. Blocking. */
    fun flushPending() {
        flushPending { playerId, name, score10, deviceTs ->
            ScoreClient.prepareSubmission(playerId, name, score10, deviceTs)
        }
    }

    /** The request factory lets tests pause real queue processing without contacting the server. */
    internal fun flushPending(prepare: (String, String, Long, Long) -> ScoreClient.Submission) {
        if (!flushLock.tryLock()) return // another sync is already sending this queue
        try {
            val (version, items, playerId) = synchronized(queueLock) {
                if (AppSettings().leaderboardChoice != AppSettings.POST) {
                    clearPendingLocked()
                    return
                }
                val pending = loadPending()
                if (pending.items.isEmpty()) return
                Triple(queueVersion, pending.items.toList(), AppSettings().playerId)
            }
            for (item in items) {
                val submission = synchronized(queueLock) {
                    if (version != queueVersion || AppSettings().leaderboardChoice != AppSettings.POST) return
                    prepare(playerId, item.name, item.score10, item.deviceTs).also { activeSubmission = it }
                }
                val outcome = try {
                    submission.execute() // never hold queueLock across a network request
                } finally {
                    synchronized(queueLock) {
                        if (activeSubmission === submission) activeSubmission = null
                    }
                }
                synchronized(queueLock) {
                    if (version != queueVersion) return
                    if (AppSettings().leaderboardChoice != AppSettings.POST) {
                        clearPendingLocked()
                        return
                    }
                    if (outcome != ScoreClient.SubmitOutcome.RETRY) {
                        // Edit the current file, preserving anything enqueued while the request ran.
                        val pending = loadPending()
                        if (pending.items.remove(item)) savePending(pending)
                    }
                }
            }
        } finally {
            flushLock.unlock()
        }
    }

    /**
     * Flushes the queue, then fetches the fresh top list and caches it.
     * Returns null when the server is unreachable (show [cached]). Blocking.
     */
    fun sync(limit: Int = 20): List<Row>? {
        flushPending()
        val top = ScoreClient.fetchTop(limit) ?: return null
        val rows = top.map { Row(it.player_id, it.name, it.score) }
        saveCache(rows)
        return rows
    }
}
