package org.apache.tvm;

/** A {@link TVMValue} wrapping a native handle (Module, Function, or NDArray). */
public final class TVMValueHandle extends TVMValue {
    private final long handle;

    public TVMValueHandle(ArgTypeCode typeCode, long handle) {
        super(typeCode);
        this.handle = handle;
    }

    @Override
    long asHandle() {
        return handle;
    }

    @Override
    public Module asModule() {
        if (typeCode == ArgTypeCode.MODULE_HANDLE) {
            return new Module(handle);
        }
        return super.asModule();
    }

    @Override
    public Function asFunction() {
        if (typeCode == ArgTypeCode.FUNC_HANDLE) {
            return new Function(handle, false);
        }
        return super.asFunction();
    }

    @Override
    public NDArrayBase asNDArray() {
        if (typeCode == ArgTypeCode.ARRAY_HANDLE || typeCode == ArgTypeCode.NDARRAY_CONTAINER) {
            return new NDArrayBase(handle, typeCode == ArgTypeCode.ARRAY_HANDLE);
        }
        return super.asNDArray();
    }
}
