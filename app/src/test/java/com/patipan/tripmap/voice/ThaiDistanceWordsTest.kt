package com.patipan.tripmap.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ThaiDistanceWordsTest {
    @Test fun readsRequiredDistancesInThai() {
        assertEquals("หนึ่งร้อย", ThaiDistanceWords.number(100))
        assertEquals("หนึ่งพัน", ThaiDistanceWords.number(1_000))
        assertEquals("หนึ่งพันสองร้อย", ThaiDistanceWords.number(1_200))
        assertEquals("สองพัน", ThaiDistanceWords.number(2_000))
    }
}
