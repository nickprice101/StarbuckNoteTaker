package org.apache.tvm;

/** Delegates to {@link API}; retained for compatibility with the APK's TVM4J classes. */
public final class APIInternal {
    public static Function get(String name) { return API.get(name); }
    private APIInternal() {}
}
