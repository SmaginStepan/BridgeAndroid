package com.an0obis.bridge

import com.an0obis.bridge.model.advice.BidAdvisor
import com.an0obis.bridge.model.advice.BiddingGuide
import com.an0obis.bridge.model.advice.Reason
import com.an0obis.bridge.model.sayc.SaycRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BiddingGuideTest {

    @Test
    fun everyRuleOfTheSystemLeadsToASectionOfTheGuide() {
        val unmapped = SaycRules.all.map { it.id }.distinct()
            .filter { BiddingGuide.sectionFor(it, emptyList()) == "auction" }
        // a new kind of rule needs a section (or a line in sectionFor); "auction" is only the fallback
        assertTrue("rules without a guide section: $unmapped", unmapped.isEmpty())
        for (rule in SaycRules.all) {
            assertTrue(BiddingGuide.sectionFor(rule.id, emptyList()) in BiddingGuide.sectionIds)
        }
    }

    @Test
    fun theOpeningsAndTheFallbackHaveTheirOwnSections() {
        assertEquals("opening", BiddingGuide.sectionFor("open_1nt", emptyList()))
        assertEquals("opening", BiddingGuide.sectionFor("open_pass", emptyList()))
        assertEquals("strong2c", BiddingGuide.sectionFor("open_2c", emptyList()))
        assertEquals("strong2c", BiddingGuide.sectionFor("s2c_wait", emptyList()))
        assertEquals("notrump", BiddingGuide.sectionFor("n_stayman", emptyList()))
        assertEquals("takeout", BiddingGuide.sectionFor("ov_takeout", emptyList()))
        assertEquals("takeout", BiddingGuide.sectionFor("td_min", emptyList()))
        assertEquals("compete", BiddingGuide.sectionFor("ov_suit", emptyList()))
        assertEquals("fit", BiddingGuide.sectionFor(null, listOf(Reason("hand"), Reason("fb_game_nt"))))
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
    fun everySectionHasATitleAndATextInEveryLanguage() {
        val base = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        for (dir in listOf("values", "values-es", "values-ru")) {
            val xml = File(base, "$dir/strings.xml").readText()
            for (name in listOf("guide_btn", "guide_title", "guide_intro", "guide_close")) {
                assertTrue("$dir has no $name", xml.contains("name=\"$name\""))
            }
            for (id in BiddingGuide.sectionIds) {
                assertTrue("$dir has no guide_${id}_t", xml.contains("name=\"guide_${id}_t\""))
                assertTrue("$dir has no guide_${id}_b", xml.contains("name=\"guide_${id}_b\""))
            }
        }
    }
}
