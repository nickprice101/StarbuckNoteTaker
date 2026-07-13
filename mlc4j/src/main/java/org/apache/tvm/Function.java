package org.apache.tvm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Wraps a TVM FFI packed function handle. */
public class Function extends TVMObject {
    /** Callback interface used by {@link #convertFunc} and {@link #register}. */
    public interface Callback {
        Object invoke(TVMValue... args);
    }

    Function(long handle) {
        super(handle, TypeIndex.kTVMFFIFunction);
    }

    @Override
    public Function asFunction() {
        return this;
    }

    @Override
    long asHandle() {
        return handle;
    }

    /** Returns the global TVM function with the given name, or {@code null} if absent. */
    public static Function getFunction(String name) {
        return getGlobalFunc(name, true);
    }

    private static List<String> listGlobalFuncNames() {
        List<String> names = new ArrayList<>();
        Base.checkCall(Base._LIB.tvmFFIFunctionListGlobalNames(names));
        return Collections.unmodifiableList(names);
    }

    private static Function getGlobalFunc(String name, boolean allowMissing) {
        Base.RefLong ref = new Base.RefLong();
        Base.checkCall(Base._LIB.tvmFFIFunctionGetGlobal(name, ref));
        if (ref.value != 0) return new Function(ref.value);
        if (allowMissing) return null;
        throw new IllegalArgumentException("Global function not found: " + name);
    }

    /** Invokes the function with whatever arguments have been pushed onto the stack. */
    public TVMValue invoke() {
        Base.RefTVMValue ret = new Base.RefTVMValue();
        Base.checkCall(Base._LIB.tvmFFIFunctionCall(handle, ret));
        return ret.value != null ? ret.value : new TVMValueNull();
    }

    public Function pushArg(int v)      { Base._LIB.tvmFFIFunctionPushArgLong(v);    return this; }
    public Function pushArg(long v)     { Base._LIB.tvmFFIFunctionPushArgLong(v);    return this; }
    public Function pushArg(float v)    { Base._LIB.tvmFFIFunctionPushArgDouble(v);  return this; }
    public Function pushArg(double v)   { Base._LIB.tvmFFIFunctionPushArgDouble(v);  return this; }
    public Function pushArg(String v)   { Base._LIB.tvmFFIFunctionPushArgString(v);  return this; }
    public Function pushArg(byte[] v)   { Base._LIB.tvmFFIFunctionPushArgBytes(v);   return this; }

    public Function pushArg(Device d) {
        Base._LIB.tvmFFIFunctionPushArgDevice(d);
        return this;
    }

    public Function pushArg(TensorBase a) {
        if (a instanceof Tensor) {
            Base._LIB.tvmFFIFunctionPushArgHandle(((Tensor) a).handle, TypeIndex.kTVMFFITensor);
        } else {
            Base._LIB.tvmFFIFunctionPushArgHandle(a.dltensorHandle, TypeIndex.kTVMFFIDLTensorPtr);
        }
        return this;
    }

    public Function pushArg(Module m) {
        Base._LIB.tvmFFIFunctionPushArgHandle(m.handle, TypeIndex.kTVMFFIModule);
        return this;
    }

    public Function pushArg(Function f) {
        Base._LIB.tvmFFIFunctionPushArgHandle(f.handle, TypeIndex.kTVMFFIFunction);
        return this;
    }

    /** Calls the function with the given arguments (vararg form). */
    public TVMValue call(Object... args) {
        for (Object a : args) pushArgToStack(a);
        return invoke();
    }

    private static void pushArgToStack(Object obj) {
        if (obj instanceof TensorBase) {
            TensorBase a = (TensorBase) obj;
            if (a instanceof Tensor) {
                Base._LIB.tvmFFIFunctionPushArgHandle(((Tensor) a).handle, TypeIndex.kTVMFFITensor);
            } else {
                Base._LIB.tvmFFIFunctionPushArgHandle(a.dltensorHandle, TypeIndex.kTVMFFIDLTensorPtr);
            }
            return;
        }
        if (obj instanceof TVMObject)  { TVMObject v = (TVMObject) obj; Base._LIB.tvmFFIFunctionPushArgHandle(v.handle, v.typeIndex); return; }
        if (obj instanceof Integer)    { Base._LIB.tvmFFIFunctionPushArgLong((Integer) obj); return; }
        if (obj instanceof Long)       { Base._LIB.tvmFFIFunctionPushArgLong((Long) obj); return; }
        if (obj instanceof Float)      { Base._LIB.tvmFFIFunctionPushArgDouble((Float) obj); return; }
        if (obj instanceof Double)     { Base._LIB.tvmFFIFunctionPushArgDouble((Double) obj); return; }
        if (obj instanceof String)     { Base._LIB.tvmFFIFunctionPushArgString((String) obj); return; }
        if (obj instanceof byte[])     { Base._LIB.tvmFFIFunctionPushArgBytes((byte[]) obj); return; }
        if (obj instanceof Device)     { Base._LIB.tvmFFIFunctionPushArgDevice((Device) obj); return; }
        if (obj instanceof TVMValueBytes)  { Base._LIB.tvmFFIFunctionPushArgBytes(((TVMValueBytes) obj).asBytes()); return; }
        if (obj instanceof TVMValueString) { Base._LIB.tvmFFIFunctionPushArgString(((TVMValueString) obj).asString()); return; }
        if (obj instanceof TVMValueDouble) { Base._LIB.tvmFFIFunctionPushArgDouble(((TVMValueDouble) obj).asDouble()); return; }
        if (obj instanceof TVMValueLong)   { Base._LIB.tvmFFIFunctionPushArgLong(((TVMValueLong) obj).asLong()); return; }
        if (obj instanceof TVMValueNull)   { Base._LIB.tvmFFIFunctionPushArgHandle(0, TypeIndex.kTVMFFINone); return; }
        if (obj instanceof TVMValueHandle) { Base._LIB.tvmFFIFunctionPushArgHandle(((TVMValueHandle) obj).asHandle(), TypeIndex.kTVMFFIOpaquePtr); return; }
        throw new IllegalArgumentException("Unsupported argument type: " + obj.getClass());
    }

    /** Wraps a Java callback as a TVM packed function. */
    public static Function convertFunc(Callback callback) {
        Base.RefLong ref = new Base.RefLong();
        Base.checkCall(Base._LIB.tvmFFIFunctionCreateFromCallback(callback, ref));
        return new Function(ref.value);
    }

    /** Registers a Java callback under a global TVM function name. */
    public static void register(String name, Callback callback, boolean override) {
        Base.RefLong ref = new Base.RefLong();
        Base.checkCall(Base._LIB.tvmFFIFunctionCreateFromCallback(callback, ref));
        Base.checkCall(Base._LIB.tvmFFIFunctionSetGlobal(name, ref.value, override ? 1 : 0));
    }

    public static void register(String name, Callback callback) {
        register(name, callback, false);
    }

    private static Object invokeRegisteredCbFunc(Callback callback, TVMValue[] args) {
        if (callback == null) {
            System.err.println("[ERROR] Failed to get registered function");
            return null;
        }
        return callback.invoke(args);
    }
}
