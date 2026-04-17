package org.apache.tvm;

public final class TVMValueNull extends TVMValue {
    public static final TVMValueNull INSTANCE = new TVMValueNull();

    private TVMValueNull() {
        super(ArgTypeCode.NULL);
    }
}
