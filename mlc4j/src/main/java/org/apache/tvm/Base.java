package org.apache.tvm;

import java.io.File;
import java.io.IOException;

/**
 * Bootstraps the TVM native library.
 *
 * On Android the library is shipped as {@code libtvm4j_runtime_packed.so} in
 * {@code jniLibs/arm64-v8a/}.  The static initialiser falls back to that
 * packed variant automatically when the standalone {@code libtvm4j.so} is not
 * present on the library search path.
 */
public final class Base {
    public static final LibInfo _LIB = new LibInfo();

    /** Mutable reference to a long value used as an output parameter in JNI calls. */
    public static class RefLong {
        public long value;

        public RefLong(long v) { this.value = v; }
        public RefLong()       { this(0L); }
    }

    /** Mutable reference to a {@link TVMValue} used as an output parameter in JNI calls. */
    public static class RefTVMValue {
        public TVMValue value;

        public RefTVMValue(TVMValue v) { this.value = v; }
        public RefTVMValue()           { this(null); }
    }

    static {
        boolean standalone;
        try {
            try {
                System.loadLibrary("tvm4j");
                standalone = true;
            } catch (UnsatisfiedLinkError e1) {
                try {
                    NativeLibraryLoader.loadLibrary("tvm4j");
                    standalone = true;
                } catch (Throwable ignored) {
                    // Fall through to packed variant
                    throw e1;
                }
            }
        } catch (Throwable t) {
            // Standalone library not available; try the packed runtime (Android).
            try {
                System.loadLibrary("tvm4j_runtime_packed");
                standalone = false;
            } catch (UnsatisfiedLinkError e) {
                throw new RuntimeException("Could not load TVM native library", e);
            }
        }

        if (standalone) {
            // Desktop JVM: call nativeLibInit with the path to the TVM shared library.
            String soPath = System.getProperty("libtvm.so.path");
            if (soPath != null && new File(soPath).isFile()) {
                checkCall(_LIB.nativeLibInit(soPath));
            } else {
                try {
                    String osName = System.getProperty("os.name", "");
                    String libName = osName.startsWith("Mac") ? "libtvm_runtime.dylib" : "libtvm_runtime.so";
                    NativeLibraryLoader.extractResourceFileToTempDir(libName,
                            file -> checkCall(_LIB.nativeLibInit(file.getPath())));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        } else {
            // Android packed runtime: initialise with null (self-contained).
            _LIB.nativeLibInit(null);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(_LIB::shutdown, "tvm4j-shutdown"));
    }

    /**
     * Checks a TVM return code and throws {@link TVMError} on failure.
     *
     * @param code Return code from a JNI call (0 = success, non-zero = error).
     */
    public static void checkCall(int code) throws TVMError {
        if (code != 0) {
            throw new TVMError(_LIB.tvmGetLastError());
        }
    }

    /** Runtime exception carrying a TVM error message. */
    public static class TVMError extends RuntimeException {
        public TVMError(String message) { super(message); }
    }

    private Base() {}
}
