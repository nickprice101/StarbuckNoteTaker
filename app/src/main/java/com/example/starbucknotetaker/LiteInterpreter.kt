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
    fun shapeSignature(): IntArray
    fun numElements(): Int
}

/** Default factory creating [LiteInterpreter] instances backed by TensorFlow Lite. */
class TfLiteInterpreter private constructor(
    private val delegate: Interpreter,
    private val flexDelegate: Any?,
) : LiteInterpreter {
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
        (flexDelegate as? AutoCloseable)?.close()
    }

    companion object {
        fun create(model: MappedByteBuffer): LiteInterpreter {
            val flexDelegate = createFlexDelegate()
            val options = Interpreter.Options()
            if (flexDelegate != null) {
                addDelegate(options, flexDelegate)
            }
            val interpreter = Interpreter(model, options)
            return TfLiteInterpreter(interpreter, flexDelegate)
        }

        private fun createFlexDelegate(): Any? {
            return try {
                val clazz = Class.forName("org.tensorflow.lite.flex.FlexDelegate")
                clazz.getDeclaredConstructor().newInstance()
            } catch (error: Throwable) {
                null
            }
        }

        private fun addDelegate(options: Interpreter.Options, delegate: Any) {
            try {
                val delegateClass = Class.forName("org.tensorflow.lite.Delegate")
                val method = Interpreter.Options::class.java.getMethod("addDelegate", delegateClass)
                method.invoke(options, delegate)
            } catch (_: Throwable) {
                // Ignore when delegate APIs are unavailable.
            }
        }
    }
}

private class TfLiteTensor(private val delegate: Tensor) : LiteTensor {
    override fun shape(): IntArray = delegate.shape()
    override fun shapeSignature(): IntArray = delegate.shapeSignature()
    override fun numElements(): Int = delegate.numElements()
}
