package org.apache.tvm;

public final class TVMValueDouble extends TVMValue {
    private final double value;

    public TVMValueDouble(double value) {
        this.value = value;
    }

    @Override
    public double asDouble() {
        return value;
    }
}
