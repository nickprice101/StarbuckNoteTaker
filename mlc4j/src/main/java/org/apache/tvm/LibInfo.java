package org.apache.tvm;

import java.util.List;

/**
 * JNI bridge to {@code libtvm4j_runtime_packed.so}.
 */
class LibInfo {
    native int nativeLibInit(String soPath);
    native int shutdown();

    native String tvmFFIGetLastError();

    native int tvmFFIObjectFree(long handle);

    native void tvmFFIFunctionPushArgLong(long v);
    native void tvmFFIFunctionPushArgDouble(double v);
    native void tvmFFIFunctionPushArgString(String v);
    native void tvmFFIFunctionPushArgBytes(byte[] v);
    native void tvmFFIFunctionPushArgHandle(long handle, int typeIndex);
    native void tvmFFIFunctionPushArgDevice(Device device);

    native int tvmFFIFunctionListGlobalNames(List<String> out);
    native int tvmFFIFunctionGetGlobal(String name, Base.RefLong out);
    native int tvmFFIFunctionSetGlobal(String name, long handle, int override);
    native int tvmFFIFunctionCall(long handle, Base.RefTVMValue out);
    native int tvmFFIFunctionCreateFromCallback(Function.Callback callback, Base.RefLong out);

    native int tvmFFIDLTensorGetShape(long handle, List<Long> out);
    native int tvmFFIDLTensorCopyFromTo(long src, long dst);
    native int tvmFFIDLTensorCopyFromJArray(byte[] src, long dstHandle);
    native int tvmFFIDLTensorCopyToJArray(long srcHandle, byte[] dst);

    native int tvmSynchronize(int deviceType, int deviceId);
    native int tvmTensorEmpty(long[] shape, int dtypeCode, int dtypeBits, int dtypeLanes,
            int deviceType, int deviceId, Base.RefLong out);
}
