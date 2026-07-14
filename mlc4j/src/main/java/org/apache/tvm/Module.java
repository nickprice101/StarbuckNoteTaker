package org.apache.tvm;

import java.util.HashMap;
import java.util.Map;

/** Wraps a TVM module handle. */
public class Module extends TVMObject {
    private static final ThreadLocal<Map<String, Function>> apiFuncs =
            ThreadLocal.withInitial(HashMap::new);

    private Function entry;
    private final String entryName = "main";

    Module(long handle) {
        super(handle, TypeIndex.kTVMFFIModule);
    }

    @Override
    public long asHandle() {
        return handle;
    }

    @Override
    public Module asModule() {
        return this;
    }

    private static Function getApi(String name) {
        return apiFuncs.get().computeIfAbsent(name, Function::getFunction);
    }

    public Function entryFunc() {
        if (entry == null) {
            entry = getFunction(entryName);
        }
        return entry;
    }

    /** Returns the named function from this module (does not search imports). */
    public Function getFunction(String name) {
        return getFunction(name, false);
    }

    public Function getFunction(String name, boolean queryImports) {
        TVMValue ret = getApi("ffi.ModuleGetFunction")
                .pushArg(this)
                .pushArg(name)
                .pushArg(queryImports ? 1 : 0)
                .invoke();
        return ret.asFunction();
    }

    /** Imports another module into this one. */
    public void importModule(Module dep) {
        getApi("ffi.ModuleImportModule").pushArg(this).pushArg(dep).invoke();
    }

    public String typeKey() {
        return getApi("ffi.ModuleGetTypeKind").pushArg(this).invoke().asString();
    }

    /** Loads a module from a file (format inferred from extension). */
    public static Module load(String path) {
        return load(path, "");
    }

    /** Loads a module from a file with an explicit format string. */
    public static Module load(String path, String format) {
        Function loadFromFile = getApi("ffi.ModuleLoadFromFile").pushArg(path);
        if (format != null && !format.isEmpty()) {
            loadFromFile.pushArg(format);
        }
        return loadFromFile.invoke().asModule();
    }

    public static boolean enabled(String target) {
        return getApi("runtime.RuntimeEnabled").pushArg(target).invoke().asLong() != 0;
    }
}
