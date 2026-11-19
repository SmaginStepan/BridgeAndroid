package com.an0obis.bridge

import com.an0obis.bridge.model.AppSettings
import com.an0obis.bridge.model.BridgeStorage
import com.an0obis.bridge.model.sayc.ClubStyle
import com.an0obis.bridge.model.sayc.SystemCatalog
import com.an0obis.bridge.model.sayc.SystemConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class SystemCatalogTest {

    @Before
    fun setUp() {
        BridgeStorage.init(Files.createTempDirectory("bridge-systems-test").toFile())
    }

    @Test
    fun normalPlayUsesTheSystemsChosenInTheSettings() {
        val settings = AppSettings()
        settings.biddingSystem = SystemConfig.ACOL_ID
        settings.opponentSystem = SystemConfig.PRECISION_ID
        val seating = SystemCatalog.seating(settings)
        assertEquals(SystemConfig.ACOL, seating.ns.config)
        assertEquals(SystemConfig.PRECISION, seating.ew.config)
    }

    @Test
    fun aSeasonKeepsThePlayersOwnSystemAndDrawsTheOpponentsSystem() {
        val settings = AppSettings()
        val mine = SystemCatalog.save(SystemConfig(id = "", name = "Mine", club = ClubStyle.STRONG))
        settings.biddingSystem = mine.id
        settings.opponentSystem = SystemConfig.ACOL_ID // the normal-play choice does not count in a season
        val seeds = (1..60).map { "season-seed-%04x".format(it) }
        val drawn = seeds.map { SystemCatalog.seasonSeating(it, settings) }
        for (seating in drawn) assertEquals(mine, seating.ns.config)
        // the same season gives every player the same opponents; different seasons vary
        for (seed in seeds) assertEquals(SystemCatalog.seasonOpponents(seed), SystemCatalog.seasonOpponents(seed))
        val systems = drawn.map { it.ew.config.id }.toSet()
        assertEquals(SystemCatalog.SEASON_OPPONENT_SYSTEMS.toSet(), systems)
    }

    @Test
    fun aSavedSystemCanBeFoundEditedAndDeleted() {
        val settings = AppSettings()
        val saved = SystemCatalog.save(SystemConfig(id = "", name = "Weak NT", ntMin = 12, ntMax = 14))
        assertTrue(saved.id.startsWith(SystemCatalog.CUSTOM_PREFIX))
        assertEquals(saved, SystemCatalog.find(saved.id))
        val edited = SystemCatalog.save(saved.copy(majorLength = 4))
        assertEquals(saved.id, edited.id)
        assertEquals(1, SystemCatalog.custom().size)
        settings.opponentSystem = saved.id
        SystemCatalog.delete(saved.id)
        assertTrue(SystemCatalog.custom().isEmpty())
        assertEquals(SystemConfig.SAYC_ID, settings.opponentSystem)
        assertEquals(SystemConfig.SAYC, SystemCatalog.find("custom-gone"))
    }
}
