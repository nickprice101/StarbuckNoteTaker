package org.apache.tvm;

import java.util.List;

/**
 * JNI bridge to {@code libtvm4j_runtime_packed.so}.
 *
 * Method names must exactly match the exported JNI symbols in the runtime library
 * (e.g. {@code Java_org_apache_tvm_LibInfo_tvmFuncCall}).
 */
class LibInfo {
    native int nativeLibInit(String soPath);
    native int shutdown();

    native String tvmGetLastError();

    native void tvmFuncPushArgLong(long v);
    native void tvmFuncPushArgDouble(double v);
    native void tvmFuncPushArgString(String v);
    native void tvmFuncPushArgHandle(long handle, int typeCode);
    native void tvmFuncPushArgBytes(byte[] v);

    native int tvmFuncListGlobalNames(List<String> out);
    native int tvmFuncGetGlobal(String name, Base.RefLong out);
    native int tvmFuncCall(long handle, Base.RefTVMValue out);
    native int tvmFuncCreateFromCFunc(Function.Callback callback, Base.RefLong out);
    native int tvmFuncRegisterGlobal(String name, long handle, int override);
    native int tvmFuncFree(long handle);

    native int tvmModGetFunction(long handle, String name, int queryImports, Base.RefLong out);
    native int tvmModImport(long mod, long dep);
    native int tvmModFree(long handle);

    native int tvmArrayAlloc(long[] shape, int ndim, int dtypeCode, int dtypeBits,
            int dtypeLanes, int deviceType, Base.RefLong out);
    native int tvmArrayFree(long handle);
    native int tvmArrayCopyFromTo(long src, long dst);
    native int tvmArrayCopyFromJArray(byte[] src, long dstHandle, long byteOffset);
    native int tvmArrayCopyToJArray(long srcHandle, byte[] dst);
    native int tvmArrayGetShape(long handle, List<Long> out);

    native int tvmSynchronize(int deviceType, int deviceId);
}
