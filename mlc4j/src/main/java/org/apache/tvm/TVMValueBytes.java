package org.apache.tvm;

public final class TVMValueBytes extends TVMValue {
    private final byte[] value;

    public TVMValueBytes(byte[] value) {
        super(ArgTypeCode.BYTES);
        this.value = value;
    }

    @Override
    public byte[] asBytes() {
        return value;
    }
}
