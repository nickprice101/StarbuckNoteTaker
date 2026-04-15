package com.example.starbucknotetaker

import java.nio.MappedByteBuffer

/**
 * Retained as a compatibility stub so that any existing test code referencing
 * [LiteInterpreter] continues to compile.  The TFLite inference path has been
 * replaced by [LlamaEngine] (MLC LLM + Llama 3.1 8B); this interface is no
 * longer used at runtime.
 */
interface LiteInterpreter {
    val inputTensorCount: Int
    fun getOutputTensor(index: Int): LiteTensor
    fun getInputTensor(index: Int): LiteTensor
    fun run(input: Any, output: Any)
    fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>)
    fun close()
}

/** Simplified view of a (formerly TFLite) tensor — retained for compile compatibility. */
interface LiteTensor {
    fun shape(): IntArray
    fun shapeSignature(): IntArray
    fun numElements(): Int
}
