package com.an0obis.bridge

import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.BridgeStorage
import com.an0obis.bridge.model.GlobalScores
import com.an0obis.bridge.net.ScoreClient
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LeaderboardTest {

    @Before
    fun setUp() {
        BridgeStorage.init(Files.createTempDirectory("bridge-top-test").toFile())
    }

    @Test
    fun percentBecomesTenths() {
        assertEquals(634L, GlobalScores.toScore10(63.4))
        assertEquals(0L, GlobalScores.toScore10(-3.0))
        assertEquals(1000L, GlobalScores.toScore10(120.0))
    }

    @Test
    fun nothingIsQueuedUntilThePlayerAgrees() {
        assertEquals(AppSettings.ASK, AppSettings().leaderboardChoice)
        GlobalScores.enqueue("Ann", 55.0)
        // the queue file does not even exist
        assertTrue(BridgeStorage.readText("pending_scores.json") == null)

        AppSettings().leaderboardChoice = AppSettings.DECLINED
        GlobalScores.enqueue("Ann", 55.0)
        assertTrue(BridgeStorage.readText("pending_scores.json") == null)

        AppSettings().leaderboardChoice = AppSettings.POST
        GlobalScores.enqueue("Ann", 55.0)
        assertTrue(BridgeStorage.readText("pending_scores.json")!!.contains("550"))
    }

    @Test
    fun choiceAndNameSurviveSeparateInstances() {
        val a = AppSettings()
        val b = AppSettings()
        a.leaderboardChoice = AppSettings.POST
        b.playerName = "Bea"
        assertEquals(AppSettings.POST, AppSettings().leaderboardChoice)
        assertEquals("Bea", AppSettings().playerName)
    }

    @Test
    fun turningTheLeaderboardOffDropsWaitingResults() {
        GlobalScores.setLeaderboardChoice(AppSettings.POST)
        GlobalScores.enqueue("Ann", 61.0)
        GlobalScores.setLeaderboardChoice(AppSettings.DECLINED)
        assertEquals(AppSettings.DECLINED, AppSettings().leaderboardChoice)
        assertTrue(!BridgeStorage.readText("pending_scores.json")!!.contains("610"))
    }

    private fun pendingNames(): List<String> =
        BridgeStorage.json.parseToJsonElement(BridgeStorage.readText("pending_scores.json")!!)
            .jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

    private class PausedSubmission(
        private val outcome: ScoreClient.SubmitOutcome = ScoreClient.SubmitOutcome.RETRY
    ) : ScoreClient.Submission {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val cancellations = AtomicInteger()

        override fun execute(): ScoreClient.SubmitOutcome {
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS)) { "test did not release the submission" }
            return outcome
        }

        // Deliberately allow a late response after cancellation to exercise stale-result handling.
        override fun cancel() { cancellations.incrementAndGet() }
    }

    private fun startFlush(prepare: (String, String, Long, Long) -> ScoreClient.Submission): FutureTask<Unit> =
        FutureTask<Unit> { GlobalScores.flushPending(prepare) }.also { task -> Thread(task).start() }

    @Test
    fun optingOutCancelsTheActiveRequestAndStopsTheRestOfTheBatch() {
        GlobalScores.setLeaderboardChoice(AppSettings.POST)
        GlobalScores.enqueue("First", 50.0)
        GlobalScores.enqueue("Second", 60.0)
        val request = PausedSubmission()
        val prepared = AtomicInteger()
        val flush = startFlush { _, _, _, _ -> prepared.incrementAndGet(); request }
        try {
            assertTrue(request.started.await(5, TimeUnit.SECONDS))
            GlobalScores.setLeaderboardChoice(AppSettings.DECLINED)
            assertEquals(1, request.cancellations.get())
            assertTrue(pendingNames().isEmpty())
        } finally {
            request.finish.countDown()
            flush.get(5, TimeUnit.SECONDS)
        }
        assertEquals(1, prepared.get())
        assertTrue("a late retry must not restore the cleared queue", pendingNames().isEmpty())
    }

    @Test
    fun optingBackInDoesNotResumeTheOldBatchOrOverwriteNewScores() {
        GlobalScores.setLeaderboardChoice(AppSettings.POST)
        GlobalScores.enqueue("Old first", 50.0)
        GlobalScores.enqueue("Old second", 60.0)
        val request = PausedSubmission()
        val prepared = AtomicInteger()
        val flush = startFlush { _, _, _, _ -> prepared.incrementAndGet(); request }
        try {
            assertTrue(request.started.await(5, TimeUnit.SECONDS))
            GlobalScores.setLeaderboardChoice(AppSettings.DECLINED)
            GlobalScores.setLeaderboardChoice(AppSettings.POST)
            GlobalScores.enqueue("New", 70.0)
        } finally {
            request.finish.countDown()
            flush.get(5, TimeUnit.SECONDS)
        }
        assertEquals(1, prepared.get())
        assertEquals(listOf("New"), pendingNames())
    }

    @Test
    fun aScoreEnqueuedDuringARequestSurvivesTheResponse() {
        GlobalScores.setLeaderboardChoice(AppSettings.POST)
        GlobalScores.enqueue("Sent", 50.0)
        val request = PausedSubmission(ScoreClient.SubmitOutcome.ACCEPTED)
        val flush = startFlush { _, _, _, _ -> request }
        try {
            assertTrue(request.started.await(5, TimeUnit.SECONDS))
            GlobalScores.enqueue("Queued meanwhile", 60.0)
        } finally {
            request.finish.countDown()
            flush.get(5, TimeUnit.SECONDS)
        }
        assertEquals(listOf("Queued meanwhile"), pendingNames())
    }

    @Test
    fun overlappingFlushesDoNotSubmitTheSameScoreTwice() {
        GlobalScores.setLeaderboardChoice(AppSettings.POST)
        GlobalScores.enqueue("Once", 50.0)
        val request = PausedSubmission(ScoreClient.SubmitOutcome.ACCEPTED)
        val flush = startFlush { _, _, _, _ -> request }
        try {
            assertTrue(request.started.await(5, TimeUnit.SECONDS))
            val second = startFlush { _, _, _, _ -> error("duplicate submission") }
            second.get(5, TimeUnit.SECONDS)
            assertFalse(flush.isDone)
        } finally {
            request.finish.countDown()
            flush.get(5, TimeUnit.SECONDS)
        }
        assertTrue(pendingNames().isEmpty())
    }

    @Test
    fun onlyRetryableResultsRemainInTheQueue() {
        GlobalScores.setLeaderboardChoice(AppSettings.POST)
        GlobalScores.enqueue("Accepted", 50.0)
        GlobalScores.enqueue("Dropped", 60.0)
        GlobalScores.enqueue("Retry", 70.0)
        val attempted = mutableListOf<String>()
        GlobalScores.flushPending { _, name, _, _ ->
            attempted += name
            object : ScoreClient.Submission {
                override fun execute() = when (name) {
                    "Accepted" -> ScoreClient.SubmitOutcome.ACCEPTED
                    "Dropped" -> ScoreClient.SubmitOutcome.DROP
                    else -> ScoreClient.SubmitOutcome.RETRY
                }
                override fun cancel() = Unit
            }
        }
        assertEquals(listOf("Accepted", "Dropped", "Retry"), attempted)
        assertEquals(listOf("Retry"), pendingNames())
    }
}
