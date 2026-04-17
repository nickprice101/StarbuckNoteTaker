package org.apache.tvm;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-local cache of frequently used TVM global functions.
 */
public final class API {
    private static final ThreadLocal<Map<String, Function>> cache =
            ThreadLocal.withInitial(HashMap::new);

    public static Function get(String name) {
        return cache.get().computeIfAbsent(name, Function::getFunction);
    }

    private API() {}
}
