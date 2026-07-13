package org.apache.tvm;

/** A {@link TVMValue} wrapping an opaque native handle. */
public final class TVMValueHandle extends TVMValue {
    private final long handle;

    public TVMValueHandle(long handle) {
        this.handle = handle;
    }

    @Override
    long asHandle() {
        return handle;
    }
}
