package org.apache.tvm;

/**
 * Wraps a TVM module handle.
 *
 * Modules hold a collection of TVM packed functions (e.g. the compiled model
 * kernels).  Get individual functions with {@link #getFunction}.
 */
public class Module extends TVMValue {
    public final long handle;
    private boolean isReleased;

    @Override
    public long asHandle() { return handle; }

    @Override
    public Module asModule() { return this; }

    Module(long handle) {
        super(ArgTypeCode.MODULE_HANDLE);
        this.isReleased = false;
        this.handle = handle;
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    @Override
    public void release() {
        if (isReleased) return;
        Base.checkCall(Base._LIB.tvmModFree(handle));
        isReleased = true;
    }

    /** Returns the named function from this module (does not search imports). */
    public Function getFunction(String name) {
        return getFunction(name, false);
    }

    /**
     * Returns the named function from this module.
     *
     * @param name         Function name.
     * @param queryImports Whether to search imported modules as well.
     */
    public Function getFunction(String name, boolean queryImports) {
        Base.RefLong ref = new Base.RefLong();
        Base.checkCall(Base._LIB.tvmModGetFunction(handle, name, queryImports ? 1 : 0, ref));
        if (ref.value == 0) {
            throw new IllegalArgumentException("Module has no function: " + name);
        }
        return new Function(ref.value, false);
    }

    /** Imports another module into this one. */
    public void importModule(Module dep) {
        Base.checkCall(Base._LIB.tvmModImport(handle, dep.handle));
    }

    /** Loads a module from a file (format inferred from extension). */
    public static Module load(String path) {
        return load(path, "");
    }

    /** Loads a module from a file with an explicit format string. */
    public static Module load(String path, String format) {
        Function loader = Function.getFunction("runtime.ModuleLoadFromFile");
        if (loader == null) {
            throw new RuntimeException("TVM function 'runtime.ModuleLoadFromFile' not found");
        }
        return loader.pushArg(path).pushArg(format).invoke().asModule();
    }
}
