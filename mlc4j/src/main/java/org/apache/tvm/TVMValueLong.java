package org.apache.tvm;

public final class TVMValueLong extends TVMValue {
    private final long value;

    public TVMValueLong(long value) {
        super(ArgTypeCode.INT);
        this.value = value;
    }

    @Override
    public long asLong() {
        return value;
    }

    @Override
    public double asDouble() {
        return (double) value;
    }
}
