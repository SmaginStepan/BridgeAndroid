package com.an0obis.bridge

import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.BridgeStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class AppSettingsTest {

    @Before
    fun setUp() {
        BridgeStorage.init(Files.createTempDirectory("bridge-settings-test").toFile())
    }

    @Test
    fun defaultNameIsRecognisedAndCustomNameIsKept() {
        val s = AppSettings()
        assertTrue(s.isDefaultPlayerName)
        s.playerName = "Stepan"
        assertEquals("Stepan", AppSettings().playerName)
    }

    @Test
    fun playerIdIsStable() {
        val first = AppSettings().playerId
        assertEquals(first, AppSettings().playerId)
    }
}
