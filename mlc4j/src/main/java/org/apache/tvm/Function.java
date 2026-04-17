package org.apache.tvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wraps a TVM packed function handle.
 *
 * Arguments are pushed onto a thread-local stack with {@code pushArg} and the
 * function is invoked with {@link #invoke()}.  The returned {@link TVMValue}
 * may hold a long, double, string, byte array, {@link Module}, or another
 * {@link Function}.
 */
public class Function extends TVMValue {
    final long handle;
    private boolean isReleased;
    public final boolean isResident;

    /** Callback interface used by {@link #convertFunc} and {@link #register}. */
    public interface Callback {
        Object invoke(TVMValue... args);
    }

    @Override
    public Function asFunction() { return this; }

    @Override
    long asHandle() { return handle; }

    /** Returns the global TVM function with the given name, or {@code null} if absent. */
    public static Function getFunction(String name) {
        for (String n : listGlobalFuncNames()) {
            if (n.equals(name)) return getGlobalFunc(n, true, false);
        }
        return null;
    }

    private static List<String> listGlobalFuncNames() {
        List<String> names = new ArrayList<>();
        Base.checkCall(Base._LIB.tvmFuncListGlobalNames(names));
        return Collections.unmodifiableList(names);
    }

    private static Function getGlobalFunc(String name, boolean isResident, boolean allowMissing) {
        Base.RefLong ref = new Base.RefLong();
        Base.checkCall(Base._LIB.tvmFuncGetGlobal(name, ref));
        if (ref.value != 0) return new Function(ref.value, isResident);
        if (allowMissing) return null;
        throw new IllegalArgumentException("Global function not found: " + name);
    }

    Function(long handle, boolean isResident) {
        super(ArgTypeCode.FUNC_HANDLE);
        this.isReleased = false;
        this.handle = handle;
        this.isResident = isResident;
    }

    Function(long handle) { this(handle, false); }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    @Override
    public void release() {
        if (isReleased || isResident) return;
        Base.checkCall(Base._LIB.tvmFuncFree(handle));
        isReleased = true;
    }

    /** Invokes the function with whatever arguments have been pushed onto the stack. */
    public TVMValue invoke() {
        Base.RefTVMValue ret = new Base.RefTVMValue();
        Base.checkCall(Base._LIB.tvmFuncCall(handle, ret));
        return ret.value != null ? ret.value : TVMValueNull.INSTANCE;
    }

    public Function pushArg(int v)      { Base._LIB.tvmFuncPushArgLong(v);    return this; }
    public Function pushArg(long v)     { Base._LIB.tvmFuncPushArgLong(v);    return this; }
    public Function pushArg(float v)    { Base._LIB.tvmFuncPushArgDouble(v);  return this; }
    public Function pushArg(double v)   { Base._LIB.tvmFuncPushArgDouble(v);  return this; }
    public Function pushArg(String v)   { Base._LIB.tvmFuncPushArgString(v);  return this; }
    public Function pushArg(byte[] v)   { Base._LIB.tvmFuncPushArgBytes(v);   return this; }

    public Function pushArg(NDArrayBase a) {
        Base._LIB.tvmFuncPushArgHandle(a.handle,
                (a.isView ? ArgTypeCode.ARRAY_HANDLE : ArgTypeCode.NDARRAY_CONTAINER).id);
        return this;
    }

    public Function pushArg(Module m) {
        Base._LIB.tvmFuncPushArgHandle(m.handle, ArgTypeCode.MODULE_HANDLE.id);
        return this;
    }

    public Function pushArg(Function f) {
        Base._LIB.tvmFuncPushArgHandle(f.handle, ArgTypeCode.FUNC_HANDLE.id);
        return this;
    }

    /** Calls the function with the given arguments (vararg form). */
    public TVMValue call(Object... args) {
        for (Object a : args) pushArgToStack(a);
        return invoke();
    }

    private static void pushArgToStack(Object obj) {
        if (obj instanceof Integer)    { Base._LIB.tvmFuncPushArgLong(((Integer) obj));    return; }
        if (obj instanceof Long)       { Base._LIB.tvmFuncPushArgLong(((Long) obj));       return; }
        if (obj instanceof Float)      { Base._LIB.tvmFuncPushArgDouble(((Float) obj));    return; }
        if (obj instanceof Double)     { Base._LIB.tvmFuncPushArgDouble(((Double) obj));   return; }
        if (obj instanceof String)     { Base._LIB.tvmFuncPushArgString((String) obj);     return; }
        if (obj instanceof byte[])     { Base._LIB.tvmFuncPushArgBytes((byte[]) obj);      return; }
        if (obj instanceof NDArrayBase){
            NDArrayBase a = (NDArrayBase) obj;
            Base._LIB.tvmFuncPushArgHandle(a.handle,
                    (a.isView ? ArgTypeCode.ARRAY_HANDLE : ArgTypeCode.NDARRAY_CONTAINER).id);
            return;
        }
        if (obj instanceof Module)     { Base._LIB.tvmFuncPushArgHandle(((Module) obj).handle, ArgTypeCode.MODULE_HANDLE.id); return; }
        if (obj instanceof Function)   { Base._LIB.tvmFuncPushArgHandle(((Function) obj).handle, ArgTypeCode.FUNC_HANDLE.id); return; }
        if (obj instanceof TVMValue) {
            TVMValue v = (TVMValue) obj;
            switch (v.typeCode) {
                case INT: case UINT:  Base._LIB.tvmFuncPushArgLong(v.asLong());   return;
                case FLOAT:           Base._LIB.tvmFuncPushArgDouble(v.asDouble()); return;
                case STR:             Base._LIB.tvmFuncPushArgString(v.asString()); return;
                case BYTES:           Base._LIB.tvmFuncPushArgBytes(v.asBytes());   return;
                default:              Base._LIB.tvmFuncPushArgHandle(v.asHandle(), v.typeCode.id); return;
            }
        }
        throw new IllegalArgumentException("Unsupported argument type: " + obj.getClass());
    }

    /** Wraps a Java callback as a TVM packed function. */
    public static Function convertFunc(Callback callback) {
        Base.RefLong ref = new Base.RefLong();
        Base.checkCall(Base._LIB.tvmFuncCreateFromCFunc(callback, ref));
        return new Function(ref.value);
    }

    /** Registers a Java callback under a global TVM function name. */
    public static void register(String name, Callback callback, boolean override) {
        Base.RefLong ref = new Base.RefLong();
        Base.checkCall(Base._LIB.tvmFuncCreateFromCFunc(callback, ref));
        Base.checkCall(Base._LIB.tvmFuncRegisterGlobal(name, ref.value, override ? 1 : 0));
    }

    public static void register(String name, Callback callback) {
        register(name, callback, false);
    }
}
