package org.apache.tvm;

/** Base class for values returned from TVM packed functions. */
public class TVMValue {
    protected TVMValue() {}

    public long asLong() {
        throw new UnsupportedOperationException();
    }

    public double asDouble() {
        throw new UnsupportedOperationException();
    }

    public String asString() {
        throw new UnsupportedOperationException();
    }

    public byte[] asBytes() {
        throw new UnsupportedOperationException();
    }

    public Module asModule() {
        throw new UnsupportedOperationException();
    }

    public Function asFunction() {
        throw new UnsupportedOperationException();
    }

    public NDArrayBase asNDArray() {
        throw new UnsupportedOperationException();
    }

    public TensorBase asTensor() {
        throw new UnsupportedOperationException();
    }

    /** Return the native handle value for handle-typed values. */
    long asHandle() {
        throw new UnsupportedOperationException();
    }

    /** Release any native resources held by this value. Default is a no-op. */
    public void release() {
    }
}
