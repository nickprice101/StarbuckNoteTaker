package com.example.starbucknotetaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class TvmJniContractTest {

    @Test
    fun libInfoNativeMethods_matchPackagedTvmRuntimeSymbolsForEveryAiAbi() {
        val nativeMethodNames = Class.forName("org.apache.tvm.LibInfo")
            .declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .map { it.name }
            .sorted()

        assertFalse(
            "LibInfo must use the TVM FFI JNI surface, not the legacy tvmFunc API",
            nativeMethodNames.any { it.startsWith("tvmFunc") || it.startsWith("tvmMod") || it.startsWith("tvmArray") },
        )

        aiRuntimeAbis.forEach { abi ->
            val runtime = repoFile("mlc4j/src/main/jniLibs/$abi/libtvm4j_runtime_packed.so")
            assertTrue(
                "Missing TVM runtime fixture for $abi at ${runtime.absolutePath}. " +
                    "Run TARGET_ABI=$abi bash scripts/fetch_mlc_native.sh.",
                runtime.isFile,
            )

            val runtimeBytes = Files.readAllBytes(runtime.toPath())
            val missingSymbols = nativeMethodNames
                .map { "Java_org_apache_tvm_LibInfo_$it" }
                .filterNot { runtimeBytes.containsAscii(it) }

            assertTrue(
                "$abi TVM runtime does not export JNI symbols required by LibInfo: $missingSymbols",
                missingSymbols.isEmpty(),
            )
        }
    }

    private fun repoFile(relativePath: String): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (current != null) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        return File(relativePath)
    }

    private fun ByteArray.containsAscii(value: String): Boolean {
        val needle = value.toByteArray(StandardCharsets.US_ASCII)
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..(size - needle.size)) {
            var offset = 0
            while (offset < needle.size && this[start + offset] == needle[offset]) {
                offset++
            }
            if (offset == needle.size) return true
        }
        return false
    }

    private companion object {
        val aiRuntimeAbis = listOf("arm64-v8a", "x86_64")
    }
}
