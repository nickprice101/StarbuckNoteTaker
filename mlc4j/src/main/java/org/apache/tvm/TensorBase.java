package org.apache.tvm;

/** Base class for TVM tensor values returned through FFI. */
public class TensorBase extends TVMValue {
    protected long handle;
    public final boolean isView;
    protected final long dltensorHandle;

    TensorBase(long handle, boolean isView) {
        this.dltensorHandle = isView ? handle : handle + 8 * 2;
        this.handle = isView ? 0 : handle;
        this.isView = isView;
    }

    @Override
    public TensorBase asTensor() {
        return this;
    }

    @Override
    long asHandle() {
        return isView ? dltensorHandle : handle;
    }

    @Override
    public void release() {
        if (handle != 0) {
            Base.checkCall(Base._LIB.tvmFFIObjectFree(handle));
            handle = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    public TensorBase copyTo(TensorBase target) {
        Base.checkCall(Base._LIB.tvmFFIDLTensorCopyFromTo(dltensorHandle, target.dltensorHandle));
        return target;
    }
}
