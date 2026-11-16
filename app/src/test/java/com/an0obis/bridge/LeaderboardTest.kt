package com.an0obis.bridge

import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.BridgeStorage
import com.an0obis.bridge.model.GlobalScores
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

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
        AppSettings().leaderboardChoice = AppSettings.POST
        GlobalScores.enqueue("Ann", 61.0)
        GlobalScores.clearPending()
        assertTrue(!BridgeStorage.readText("pending_scores.json")!!.contains("610"))
    }
}
