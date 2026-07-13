package org.apache.tvm;

/** Type indexes used by the TVM FFI runtime. */
public final class TypeIndex {
    public static final int kTVMFFINone = 0;
    public static final int kTVMFFIInt = 1;
    public static final int kTVMFFIBool = 2;
    public static final int kTVMFFIFloat = 3;
    public static final int kTVMFFIOpaquePtr = 4;
    public static final int kTVMFFIDataType = 5;
    public static final int kTVMFFIDevice = 6;
    public static final int kTVMFFIDLTensorPtr = 7;
    public static final int kTVMFFIRawStr = 8;
    public static final int kTVMFFIByteArrayPtr = 9;
    public static final int kTVMFFIObjectRValueRef = 10;
    public static final int kTVMFFIStaticObjectBegin = 64;
    public static final int kTVMFFIObject = 64;
    public static final int kTVMFFIStr = 65;
    public static final int kTVMFFIBytes = 66;
    public static final int kTVMFFIError = 67;
    public static final int kTVMFFIFunction = 68;
    public static final int kTVMFFIShape = 70;
    public static final int kTVMFFITensor = 71;
    public static final int kTVMFFIArray = 72;
    public static final int kTVMFFIMap = 73;
    public static final int kTVMFFIModule = 73;

    private TypeIndex() {}
}
