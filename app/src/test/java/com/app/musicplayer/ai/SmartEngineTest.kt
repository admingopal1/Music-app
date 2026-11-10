package com.app.musicplayer.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartEngineTest {
    @Test fun tokensFoldCaseAndAccents() {
        assertEquals(listOf("beyonce", "halo"), SmartEngine.tokens("Beyoncé – HALO!"))
    }

    @Test fun similarityToleratesTypos() {
        assertTrue(SmartEngine.similarity("arjit", "arijit") > 0.6f)
        assertTrue(SmartEngine.similarity("kesriya", "kesariya") > 0.6f)
        assertTrue(SmartEngine.similarity("arjit", "arijitsingh") > 0.6f)
    }

    @Test fun shortOrUnrelatedWordsDoNotMatch() {
        assertEquals(0f, SmartEngine.similarity("tum", "tim"), 0f)
        assertEquals(0f, SmartEngine.similarity("believer", "halo"), 0f)
    }
}
