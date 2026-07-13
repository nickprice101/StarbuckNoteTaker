package org.apache.tvm;

/** Compatibility alias for the older TVM4J NDArray name. */
@Deprecated
public class NDArrayBase extends TensorBase {
    NDArrayBase(long handle, boolean isView) {
        super(handle, isView);
    }

    @Override
    public NDArrayBase asNDArray() {
        return this;
    }
}
