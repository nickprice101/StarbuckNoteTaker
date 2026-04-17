package org.apache.tvm;

/**
 * Lightweight NDArray wrapper (view or owned).
 *
 * Only the operations needed by the MLC LLM Java layer are implemented here.
 */
public class NDArrayBase extends TVMValue {
    public final long handle;
    /** {@code true} when this object is a view (does not own the underlying data). */
    public final boolean isView;

    NDArrayBase(long handle, boolean isView) {
        super(isView ? ArgTypeCode.ARRAY_HANDLE : ArgTypeCode.NDARRAY_CONTAINER);
        this.handle = handle;
        this.isView = isView;
    }

    @Override
    long asHandle() { return handle; }

    @Override
    public NDArrayBase asNDArray() { return this; }

    @Override
    public void release() {
        if (!isView && handle != 0) {
            Base.checkCall(Base._LIB.tvmArrayFree(handle));
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }
}
