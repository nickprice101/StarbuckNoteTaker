package com.example.starbucknotetaker

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apache.tvm.Base
import org.apache.tvm.Function
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TvmRuntimeInstrumentationTest {

    @Test
    fun packedTvmRuntime_loadsAndExposesMlcJsonEngineFactory() {
        assertTvmRuntimePackaged()
        Base.ensureInitialized()

        val createEngine = Function.getFunction("mlc.json_ffi.CreateJSONFFIEngine")

        assertNotNull(createEngine)
    }

    @Test
    fun mlcJsonEngineModule_exposesReloadFunction() {
        assertTvmRuntimePackaged()
        Base.ensureInitialized()

        val createEngine = Function.getFunction("mlc.json_ffi.CreateJSONFFIEngine")
        val engineModule = createEngine.invoke().asModule()

        assertNotNull(engineModule.getFunction("reload"))
    }

    @Test
    fun compiledMlcModelLibrary_loadsAsSystemLibAndExposesVmLoader() {
        val modelLibrary = assertCompiledModelLibraryPackaged()
        Base.ensureInitialized()

        System.load(modelLibrary.absolutePath)
        val systemLibPrefixUsedByMlc = "${LlamaModelManager.MODEL_LIB_SYSTEM_NAME}_"
        val systemLib = Function.getFunction("ffi.SystemLib")
            .call(systemLibPrefixUsedByMlc)
            .asModule()

        assertNotNull(systemLib.getFunction("vm_load_executable", true))
    }

    private fun assertTvmRuntimePackaged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = File(context.applicationInfo.nativeLibraryDir, "libtvm4j_runtime_packed.so")
        assertTrue(
            "Missing packaged TVM runtime at ${runtime.absolutePath}. " +
                "The debug APK must include libtvm4j_runtime_packed.so for the installed ABI.",
            runtime.isFile,
        )
    }

    private fun assertCompiledModelLibraryPackaged(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelLibrary = File(
            context.applicationInfo.nativeLibraryDir,
            LlamaModelManager.MODEL_SO_FILENAME,
        )
        assertTrue(
            "Missing packaged MLC model library at ${modelLibrary.absolutePath}. " +
                "The debug APK must include ${LlamaModelManager.MODEL_SO_FILENAME} " +
                "for the installed ABI.",
            modelLibrary.isFile,
        )
        return modelLibrary
    }
}
