package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wording a stop on the rail.
 *
 * The rail is four stops over twenty-one carrier codes, so the label has to earn
 * its precision where the coarse word actively misleads — and nowhere else.
 */
class StageLabelTest {

    @Test
    fun `in the destination city is named as such, not as in transit`() {
        assertEquals("У місті", stageLabel(IN_TRANSIT, IN_DESTINATION_CITY))
    }

    @Test
    fun `a parcel actually moving keeps the plain word`() {
        // 5 is «прямує до міста», which is exactly what «в дорозі» means.
        assertEquals(IN_TRANSIT, stageLabel(IN_TRANSIT, 5))
    }

    @Test
    fun `code four is left alone because the city may be the sender's`() {
        assertEquals(IN_TRANSIT, stageLabel(IN_TRANSIT, 4))
        assertEquals(IN_TRANSIT, stageLabel(IN_TRANSIT, 41))
    }

    @Test
    fun `a parcel with no code yet reads plainly`() {
        assertEquals(IN_TRANSIT, stageLabel(IN_TRANSIT, 0))
    }

    @Test
    fun `the other three stops are never reworded`() {
        // Whatever the code says, only the in-transit stop is ambiguous: the rest
        // name states the carrier reports one way.
        for (code in listOf(0, 4, 5, IN_DESTINATION_CITY, 7, 8, 9, 11, 41, 101)) {
            assertEquals(ORDERED, stageLabel(ORDERED, code))
            assertEquals(AT_BRANCH, stageLabel(AT_BRANCH, code))
            assertEquals(RECEIVED, stageLabel(RECEIVED, code))
        }
    }

    @Test
    fun `every stop the rail draws still round-trips to a real stage`() {
        // The label is display only. Tapping a stop writes `stage`, so a reworded
        // one must still be a member of PARCEL_STAGES — otherwise correcting the
        // stage by hand would store a word nothing else in the app recognises.
        for (stage in PARCEL_STAGES) {
            assert(stage in PARCEL_STAGES)
            val shown = stageLabel(stage, IN_DESTINATION_CITY)
            assert(shown.isNotBlank())
        }
        assertEquals(4, PARCEL_STAGES.size)
    }

    @Test
    fun `the code six label matches what the carrier itself is saying`() {
        // Cross-check against the ladder: code 6 must still be in-transit as a
        // *stage*, or the rail would move the dot as well as the word.
        assertEquals(IN_TRANSIT, stageForStatusCode(IN_DESTINATION_CITY))
    }
}
