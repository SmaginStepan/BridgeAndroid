package com.an0obis.bridge

import com.an0obis.bridge.model.advice.BidAdvisor
import com.an0obis.bridge.model.advice.BiddingGuide
import com.an0obis.bridge.model.advice.Reason
import com.an0obis.bridge.model.sayc.BiddingSystem
import com.an0obis.bridge.model.sayc.ClubStyle
import com.an0obis.bridge.model.sayc.NtResponses
import com.an0obis.bridge.model.sayc.SystemConfig
import com.an0obis.bridge.model.sayc.TwoDiamonds
import com.an0obis.bridge.model.sayc.TwoMajors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BiddingGuideTest {

    /** The presets and a spread of convention cards the builder can make. */
    private val systems: List<BiddingSystem> = run {
        val cards = SystemConfig.PRESETS.toMutableList()
        for (club in ClubStyle.entries) for (d in TwoDiamonds.entries) for (m in TwoMajors.entries) {
            cards += SystemConfig(
                "custom-$club-$d-$m", club = club, twoDiamonds = d, twoMajors = m,
                majorLength = if (m == TwoMajors.WEAK) 4 else 5, twoOverOne = true, jacoby2nt = true,
                ntResponses = if (d == TwoDiamonds.WEAK) NtResponses.LIMIT else NtResponses.STANDARD,
            ).normalized()
        }
        cards.map { BiddingSystem.of(it) }
    }

    private val allIds: Set<String> = systems.flatMap { s -> s.rules.map { it.id } }.toSet()

    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }

    @Test
    fun everyRuleOfEverySystemLeadsToASectionThatTheSystemShows() {
        val unmapped = allIds.filter { BiddingGuide.sectionFor(it, emptyList()) == "auction" }
        // a new kind of rule needs a section (or a line in sectionFor); "auction" is only the fallback
        assertTrue("rules without a guide section: $unmapped", unmapped.isEmpty())
        for (system in systems) {
            val shown = BiddingGuide.sectionsFor(system.config)
            for (rule in system.rules) {
                val section = BiddingGuide.sectionFor(rule.id, emptyList())
                assertTrue("${system.config.id}: ${rule.id} is in $section, which the guide hides", section in shown)
            }
        }
    }

    @Test
    fun theOpeningsAndTheFallbackHaveTheirOwnSections() {
        assertEquals("opening", BiddingGuide.sectionFor("open_1nt", emptyList()))
        assertEquals("opening", BiddingGuide.sectionFor("open_pass", emptyList()))
        assertEquals("strong2c", BiddingGuide.sectionFor("open_2c", emptyList()))
        assertEquals("strong2c", BiddingGuide.sectionFor("s2c_wait", emptyList()))
        assertEquals("strongclub", BiddingGuide.sectionFor("open_1c_strong", emptyList()))
        assertEquals("polishclub", BiddingGuide.sectionFor("open_1c_polish", emptyList()))
        assertEquals("conventions", BiddingGuide.sectionFor("open_2d_multi", emptyList()))
        assertEquals("twooverone", BiddingGuide.sectionFor("resp_2over1", emptyList()))
        assertEquals("fourcard", BiddingGuide.sectionFor("open_1h4", emptyList()))
        assertEquals("notrump", BiddingGuide.sectionFor("n_stayman", emptyList()))
        assertEquals("takeout", BiddingGuide.sectionFor("ov_takeout", emptyList()))
        assertEquals("takeout", BiddingGuide.sectionFor("td_min", emptyList()))
        assertEquals("compete", BiddingGuide.sectionFor("ov_suit", emptyList()))
        assertEquals("fit", BiddingGuide.sectionFor(null, listOf(Reason("hand"), Reason("fb_game_nt"))))
    }

    @Test
    fun eachSystemShowsOnlyItsOwnSections() {
        val sayc = BiddingGuide.sectionsFor(SystemConfig.SAYC)
        assertTrue("strong2c" in sayc && "strongclub" !in sayc && "twooverone" !in sayc && "fourcard" !in sayc)
        val precision = BiddingGuide.sectionsFor(SystemConfig.PRECISION)
        assertTrue("strongclub" in precision && "strong2c" !in precision && "conventions" in precision)
        assertTrue("fourcard" in BiddingGuide.sectionsFor(SystemConfig.ACOL))
        assertTrue("twooverone" in BiddingGuide.sectionsFor(SystemConfig.TWO_OVER_ONE))
        assertTrue("polishclub" in BiddingGuide.sectionsFor(SystemConfig.POLISH))
        // the table of a section holds the system's own numbers
        val acolNt = BiddingGuide.table(BiddingSystem.of(SystemConfig.ACOL), "opening").first { it.ruleId == "open_1nt" }
        assertEquals(12..14, acolNt.hcp)
    }

    @Test
    fun theAdviceOpensTheSectionOfItsOwnRule() {
        val advice = BidAdvisor.advise(
            2, emptyList(),
            com.an0obis.bridge.model.Hand(
                listOf(
                    com.an0obis.bridge.model.Card(14, 3), com.an0obis.bridge.model.Card(13, 3), com.an0obis.bridge.model.Card(5, 3),
                    com.an0obis.bridge.model.Card(13, 2), com.an0obis.bridge.model.Card(12, 2), com.an0obis.bridge.model.Card(4, 2),
                    com.an0obis.bridge.model.Card(12, 1), com.an0obis.bridge.model.Card(11, 1), com.an0obis.bridge.model.Card(5, 1), com.an0obis.bridge.model.Card(2, 1),
                    com.an0obis.bridge.model.Card(12, 0), com.an0obis.bridge.model.Card(10, 0), com.an0obis.bridge.model.Card(3, 0)
                )
            )
        )
        assertEquals("opening", BiddingGuide.sectionFor(advice.meaning?.ruleId, advice.reasons))
    }

    @Test
    fun everyCallHasAMeaningAndEverySectionATextInEveryLanguage() {
        for (dir in listOf("values", "values-es", "values-ru")) {
            val xml = File(res, "$dir/strings.xml").readText()
            fun has(name: String) = assertTrue("$dir has no $name", xml.contains("name=\"$name\""))
            for (name in listOf("guide_btn", "guide_title", "guide_intro", "guide_close", "guide_system", "guide_table")) has(name)
            for (id in BiddingGuide.sectionIds) {
                has("guide_${id}_t")
                has("guide_${id}_b")
            }
            for (id in allIds) has("sayc_$id")
            for (preset in SystemConfig.PRESETS) {
                has("sys_${preset.id}")
                has("sys_${preset.id}_d")
            }
        }
    }

    @Test
    fun meaningTextsLeaveTheNumbersToTheSystem() {
        // the popup shows each system's own range under the text, so a text must not name a range
        val xml = File(res, "values/strings.xml").readText()
        val withNumbers = Regex("<string name=\"(sayc_[a-z0-9_]+)\">([^<]*)</string>").findAll(xml)
            .filter {
                val text = it.groupValues[2].replace(Regex("\\d-\\d-\\d-\\d"), "") // a shape such as 4-4-1-4 is not a range
                it.groupValues[1] != "sayc_pts" && Regex("\\d+[–-]\\d+|\\d+ (or more )?points").containsMatchIn(text)
            }
            .map { it.groupValues[1] }.toList()
        assertTrue("meanings with fixed numbers: $withNumbers", withNumbers.isEmpty())
    }
}
