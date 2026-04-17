package org.apache.tvm;

/** Base class for values returned from TVM packed functions. */
public abstract class TVMValue {
    public final ArgTypeCode typeCode;

    protected TVMValue(ArgTypeCode typeCode) {
        this.typeCode = typeCode;
    }

    public long asLong() {
        throw new UnsupportedOperationException("Cannot convert " + typeCode + " to long");
    }

    public double asDouble() {
        throw new UnsupportedOperationException("Cannot convert " + typeCode + " to double");
    }

    public String asString() {
        throw new UnsupportedOperationException("Cannot convert " + typeCode + " to String");
    }

    public byte[] asBytes() {
        throw new UnsupportedOperationException("Cannot convert " + typeCode + " to bytes");
    }

    public Module asModule() {
        throw new UnsupportedOperationException("Cannot convert " + typeCode + " to Module");
    }

    public Function asFunction() {
        throw new UnsupportedOperationException("Cannot convert " + typeCode + " to Function");
    }

    public NDArrayBase asNDArray() {
        throw new UnsupportedOperationException("Cannot convert " + typeCode + " to NDArray");
    }

    /** Return the native handle value for handle-typed values. */
    long asHandle() {
        throw new UnsupportedOperationException("Cannot get handle from " + typeCode);
    }

    /** Release any native resources held by this value. Default is a no-op. */
    public void release() {
    }
}
