package com.arick.androidlocalllmlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun samplingPreset_configForAdaptsToSmallContext() {
        // When nCtx is 512, maxTokens must not exceed available space (512 - 64 = 448)
        val nCtx512 = 512
        val config512 = SamplingPreset.Stable.configFor(nCtx512)
        assertTrue("maxTokens must be <= nCtx - 64", config512.maxTokens <= nCtx512 - 64)
        assertTrue("maxTokens must be positive", config512.maxTokens >= 32)
    }

    @Test
    fun samplingPreset_configForAdaptsToTinyContext() {
        val nCtx256 = 256
        val config256 = SamplingPreset.Creative.configFor(nCtx256)
        assertTrue("maxTokens must be <= 192", config256.maxTokens <= 192)
        assertTrue("maxTokens must be >= 32", config256.maxTokens >= 32)
    }

    @Test
    fun samplingPreset_configForPreservesLargeContextCeiling() {
        val nCtx4096 = 4096
        val config4096 = SamplingPreset.Balanced.configFor(nCtx4096)
        assertEquals(512, config4096.maxTokens)
    }

    @Test
    fun selectedModelStore_defaultSummaryLinesIsFourForBalancedMemory() {
        assertEquals(4, SelectedModelStore.DEFAULT_SUMMARY_LINES)
    }
}
