package com.an0obis.bridge

import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.BridgeStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class SettingsTest {

    @Before
    fun setUp() {
        BridgeStorage.init(Files.createTempDirectory("bridge-settings-test").toFile())
    }

    @Test
    fun defaults() {
        assertEquals("sayc", AppSettings().biddingSystem)
        assertFalse(AppSettings().tutorialSeen)
    }

    @Test
    fun choicesSurviveSeparateInstancesAndKeepOtherSettings() {
        AppSettings().leaderboardChoice = AppSettings.POST
        AppSettings().tutorialSeen = true
        AppSettings().biddingSystem = "sayc"
        assertTrue(AppSettings().tutorialSeen)
        assertEquals(AppSettings.POST, AppSettings().leaderboardChoice)
    }

    @Test
    fun tourProgressIsSavedAndCanBeReset() {
        assertEquals(0, AppSettings().boardTourSeen)
        AppSettings().boardTourSeen = 1 or 2
        assertEquals(3, AppSettings().boardTourSeen)
        AppSettings().boardTourSeen = 0
        assertEquals(0, AppSettings().boardTourSeen)
    }

    @Test
    fun everyListedSystemIsKnown() {
        assertTrue("sayc" in com.an0obis.bridge.model.sayc.SystemConfig.PRESET_IDS)
    }
}
