package org.apache.tvm;

/** Base class for handle-backed TVM FFI objects. */
public class TVMObject extends TVMValue {
    protected long handle;
    public final int typeIndex;

    public TVMObject(long handle, int typeIndex) {
        this.handle = handle;
        this.typeIndex = typeIndex;
    }

    @Override
    long asHandle() {
        return handle;
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
}
