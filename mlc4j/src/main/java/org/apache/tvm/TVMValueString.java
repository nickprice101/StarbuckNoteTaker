package org.apache.tvm;

public final class TVMValueString extends TVMValue {
    private final String value;

    public TVMValueString(String value) {
        super(ArgTypeCode.STR);
        this.value = value;
    }

    @Override
    public String asString() {
        return value;
    }
}
