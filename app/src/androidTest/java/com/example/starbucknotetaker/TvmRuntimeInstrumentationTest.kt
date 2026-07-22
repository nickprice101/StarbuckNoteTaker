package com.example.starbucknotetaker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Packaging checks for the supported LiteRT-LM Android runtime. */
@RunWith(AndroidJUnit4::class)
class LiteRtLmRuntimeInstrumentationTest {

    @Test
    fun liteRtLmJniRuntime_isPackagedForInstalledAbi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = File(context.applicationInfo.nativeLibraryDir, "liblitertlm_jni.so")

        assertTrue(
            "Missing packaged LiteRT-LM runtime at ${runtime.absolutePath}",
            runtime.isFile,
        )
    }

    @Test
    fun liteRtLmEngineApi_acceptsPinnedModelPath() {
        val config = EngineConfig(
            modelPath = "/data/local/tmp/${LlamaModelManager.MODEL_FILENAME}",
        )

        assertEquals(LlamaModelManager.MODEL_FILENAME, File(config.modelPath).name)
        assertTrue(Engine::class.java.name.contains("litertlm"))
    }
}
