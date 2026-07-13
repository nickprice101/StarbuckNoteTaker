package org.apache.tvm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Lightweight TVM tensor wrapper. */
public class Tensor extends TensorBase {
    private final TVMType dtype;
    private final Device device;

    Tensor(long handle, boolean isView, TVMType dtype, Device device) {
        super(handle, isView);
        this.dtype = dtype;
        this.device = device;
    }

    public void copyFrom(double[] sourceArray) {
        checkCopySize(sourceArray.length);
        if (dtype.typeCode != TVMType.FLOAT || dtype.bits != 64) {
            throw new IllegalArgumentException("Cannot set double[] for " + dtype + " tensor");
        }
        byte[] nativeArr = new byte[sourceArray.length * dtype.numOfBytes];
        for (int i = 0; i < sourceArray.length; ++i) {
            wrapBytes(nativeArr, i * dtype.numOfBytes, dtype.numOfBytes).putDouble(sourceArray[i]);
        }
        Base.checkCall(Base._LIB.tvmFFIDLTensorCopyFromJArray(nativeArr, dltensorHandle));
    }

    public void copyFrom(float[] sourceArray) {
        checkCopySize(sourceArray.length);
        if (dtype.typeCode != TVMType.FLOAT || dtype.bits != 32) {
            throw new IllegalArgumentException("Cannot set float[] for " + dtype + " tensor");
        }
        byte[] nativeArr = new byte[sourceArray.length * dtype.numOfBytes];
        for (int i = 0; i < sourceArray.length; ++i) {
            wrapBytes(nativeArr, i * dtype.numOfBytes, dtype.numOfBytes).putFloat(sourceArray[i]);
        }
        Base.checkCall(Base._LIB.tvmFFIDLTensorCopyFromJArray(nativeArr, dltensorHandle));
    }

    public void copyFrom(long[] sourceArray) {
        checkCopySize(sourceArray.length);
        if (dtype.typeCode != TVMType.INT || dtype.bits != 64) {
            throw new IllegalArgumentException("Cannot set long[] for " + dtype + " tensor");
        }
        byte[] nativeArr = new byte[sourceArray.length * dtype.numOfBytes];
        for (int i = 0; i < sourceArray.length; ++i) {
            wrapBytes(nativeArr, i * dtype.numOfBytes, dtype.numOfBytes).putLong(sourceArray[i]);
        }
        Base.checkCall(Base._LIB.tvmFFIDLTensorCopyFromJArray(nativeArr, dltensorHandle));
    }

    public void copyFrom(int[] sourceArray) {
        checkCopySize(sourceArray.length);
        if (dtype.typeCode != TVMType.INT || dtype.bits != 32) {
            throw new IllegalArgumentException("Cannot set int[] for " + dtype + " tensor");
        }
        byte[] nativeArr = new byte[sourceArray.length * dtype.numOfBytes];
        for (int i = 0; i < sourceArray.length; ++i) {
            wrapBytes(nativeArr, i * dtype.numOfBytes, dtype.numOfBytes).putInt(sourceArray[i]);
        }
        Base.checkCall(Base._LIB.tvmFFIDLTensorCopyFromJArray(nativeArr, dltensorHandle));
    }

    public void copyFrom(byte[] sourceArray) {
        checkCopySize(sourceArray.length);
        if (dtype.typeCode != TVMType.INT || dtype.bits != 8) {
            throw new IllegalArgumentException("Cannot set byte[] for " + dtype + " tensor");
        }
        copyFromRaw(sourceArray);
    }

    public void copyFromRaw(byte[] sourceArray) {
        Base.checkCall(Base._LIB.tvmFFIDLTensorCopyFromJArray(sourceArray, dltensorHandle));
    }

    public long[] shape() {
        List<Long> data = new ArrayList<>();
        Base.checkCall(Base._LIB.tvmFFIDLTensorGetShape(dltensorHandle, data));
        long[] shape = new long[data.size()];
        for (int i = 0; i < shape.length; ++i) {
            shape[i] = data.get(i);
        }
        return shape;
    }

    public long size() {
        long product = 1L;
        for (long dim : shape()) {
            product *= dim;
        }
        return product;
    }

    public byte[] internal() {
        int arrLength = dtype.numOfBytes * (int) size();
        byte[] arr = new byte[arrLength];
        Base.checkCall(Base._LIB.tvmFFIDLTensorCopyToJArray(dltensorHandle, arr));
        return arr;
    }

    public Device device() {
        return device;
    }

    public static Tensor empty(long[] shape, TVMType dtype, Device device) {
        Base.RefLong ref = new Base.RefLong();
        Base.checkCall(Base._LIB.tvmTensorEmpty(
                shape, dtype.typeCode, dtype.bits, dtype.lanes,
                device.deviceType, device.deviceId, ref));
        return new Tensor(ref.value, false, dtype, device);
    }

    public static Tensor empty(long[] shape, TVMType dtype) {
        return empty(shape, dtype, Device.cpu(0));
    }

    public static Tensor empty(long[] shape) {
        return empty(shape, new TVMType("float32"), Device.cpu(0));
    }

    public static Tensor empty(long[] shape, Device device) {
        return empty(shape, new TVMType("float32"), device);
    }

    private void checkCopySize(int sourceLength) {
        long tensorSize = size();
        if (tensorSize != sourceLength) {
            throw new IllegalArgumentException(
                    String.format("Tensor size mismatch: %d vs %d", sourceLength, tensorSize));
        }
    }

    private static ByteBuffer wrapBytes(byte[] bytes, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer;
    }
}
