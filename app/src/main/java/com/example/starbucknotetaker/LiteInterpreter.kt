package com.example.starbucknotetaker

import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.nio.MappedByteBuffer

/** Lightweight abstraction over TensorFlow Lite interpreter APIs used by [Summarizer]. */
interface LiteInterpreter {
    val inputTensorCount: Int
    fun getOutputTensor(index: Int): LiteTensor
    fun getInputTensor(index: Int): LiteTensor
    fun run(input: Any, output: Any)
    fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>)
    fun close()
}

/** Simplified view of a TensorFlow Lite tensor. */
interface LiteTensor {
    fun shape(): IntArray
    fun numElements(): Int
}

/** Default factory creating [LiteInterpreter] instances backed by TensorFlow Lite. */
class TfLiteInterpreter private constructor(private val delegate: Interpreter) : LiteInterpreter {
    override val inputTensorCount: Int
        get() = delegate.inputTensorCount

    override fun getOutputTensor(index: Int): LiteTensor = TfLiteTensor(delegate.getOutputTensor(index))

    override fun getInputTensor(index: Int): LiteTensor = TfLiteTensor(delegate.getInputTensor(index))

    override fun run(input: Any, output: Any) {
        delegate.run(input, output)
    }

    override fun runForMultipleInputsOutputs(inputs: Array<Any?>, outputs: Map<Int, Any>) {
        delegate.runForMultipleInputsOutputs(inputs, outputs)
    }

    override fun close() {
        delegate.close()
    }

    companion object {
        fun create(model: MappedByteBuffer): LiteInterpreter = TfLiteInterpreter(Interpreter(model))
    }
}

private class TfLiteTensor(private val delegate: Tensor) : LiteTensor {
    override fun shape(): IntArray = delegate.shape()
    override fun numElements(): Int = delegate.numElements()
}
