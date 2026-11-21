package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.*
import org.junit.Test

class AutoPlayEarlyDecisionTest {
    @Test fun firstAnswerDecidesAndLaterSourceWaitsForEarlierSource() {
        val first = stream("one")
        val second = stream("two")
        assertTrue(AutoPlayEarlyDecision.decide(listOf("one", "two"), emptySet(), first, 0, false))
        assertFalse(AutoPlayEarlyDecision.decide(listOf("one", "two"), emptySet(), second, 0, false))
        assertTrue(AutoPlayEarlyDecision.decide(listOf("one", "two"), setOf("one"), second, 0, false))
    }

    @Test fun fastStartNeedsCandidateAndThreeSeconds() {
        assertFalse(AutoPlayEarlyDecision.decide(listOf("one", "two"), emptySet(), stream("two"), 2_999, true))
        assertTrue(AutoPlayEarlyDecision.decide(listOf("one", "two"), emptySet(), stream("two"), 3_000, true))
        assertFalse(AutoPlayEarlyDecision.decide(listOf("one", "two"), emptySet(), null, 3_000, true))
    }

    private fun stream(source: String) = Stream(
        name = null, title = null, description = null, url = null, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = source, addonLogo = null
    )
}
