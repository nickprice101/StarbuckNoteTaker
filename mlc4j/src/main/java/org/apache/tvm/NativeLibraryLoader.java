package org.apache.tvm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Helper that extracts a native library bundled inside a JAR onto the local
 * filesystem so that it can be loaded with {@link System#load}.
 *
 * On Android this class is typically unused because the packed runtime
 * ({@code libtvm4j_runtime_packed.so}) is installed as a regular JNI library.
 */
class NativeLibraryLoader {
    private static final String LIB_PATH_IN_JAR = "/lib/native/";
    private static File tempDir;

    interface Action {
        void invoke(File file) throws IOException;
    }

    static {
        try {
            File tmp = File.createTempFile("tvm4j", "");
            tempDir = tmp;
            if (!tmp.delete() || !tempDir.mkdir()) {
                throw new IOException("Cannot create temp dir " + tempDir);
            }
            String osName = System.getProperty("os.name", "");
            if (osName.startsWith("Windows")) {
                throw new RuntimeException("Windows is not supported");
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                File[] files = tempDir.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
                tempDir.delete();
            }, "tvm4j-cleanup"));
        } catch (IOException e) {
            throw new RuntimeException("Cannot initialise NativeLibraryLoader", e);
        }
    }

    static void loadLibrary(String name) throws UnsatisfiedLinkError, IOException {
        String mappedName = System.mapLibraryName(name);
        if (mappedName.endsWith("dylib")) {
            mappedName = mappedName.replace(".dylib", ".jnilib");
        }
        extractResourceFileToTempDir(mappedName, file -> System.load(file.getPath()));
    }

    static void extractResourceFileToTempDir(String resourceName, Action action) throws IOException {
        InputStream is = NativeLibraryLoader.class.getResourceAsStream(LIB_PATH_IN_JAR + resourceName);
        if (is == null) {
            throw new UnsatisfiedLinkError("Resource not found: " + LIB_PATH_IN_JAR + resourceName);
        }
        try {
            File dest = new File(tempDir, resourceName);
            try (FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            }
            action.invoke(dest);
        } finally {
            is.close();
        }
    }
}
