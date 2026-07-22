package com.example.starbucknotetaker

import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmContractTest {

    @Test
    fun appUsesPortableLiteRtLmModelBundle() {
        assertTrue(LlamaModelManager.MODEL_FILENAME.endsWith(".litertlm"))
        assertTrue(LlamaModelManager.MODEL_URL.contains("huggingface.co"))
    }

    @Test
    fun liteRtModelSupportsPhoneAndEmulatorAbis() {
        assertTrue("arm64-v8a" in LlamaModelManager.SUPPORTED_MODEL_ABIS)
        assertTrue("x86_64" in LlamaModelManager.SUPPORTED_MODEL_ABIS)
    }
}
