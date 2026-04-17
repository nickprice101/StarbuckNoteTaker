package org.apache.tvm;

/** Type codes for TVM packed function arguments. */
public enum ArgTypeCode {
    INT(0), UINT(1), FLOAT(2), NULL(4), STR(11), BYTES(13),
    HANDLE(3), ARRAY_HANDLE(7), MODULE_HANDLE(9), FUNC_HANDLE(10),
    NDARRAY_CONTAINER(14);

    public final int id;

    ArgTypeCode(int id) {
        this.id = id;
    }
}
