package org.apache.tvm;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a TVM device (platform + index).
 *
 * Device type constants mirror {@code DLDeviceType} in {@code dlpack.h}.
 */
public class Device {
    public static final int kDLCPU    = 1;
    public static final int kDLCUDA   = 2;
    public static final int kDLOpenCL = 4;
    public static final int kDLVulkan = 7;
    public static final int kDLMetal  = 8;

    private static final Map<Integer, String> MASK2STR = new HashMap<>();
    private static final Map<String, Integer> STR2MASK  = new HashMap<>();

    static {
        MASK2STR.put(kDLCPU,    "cpu");
        MASK2STR.put(kDLCUDA,   "cuda");
        MASK2STR.put(kDLOpenCL, "opencl");
        MASK2STR.put(kDLVulkan, "vulkan");
        MASK2STR.put(kDLMetal,  "metal");
        STR2MASK.put("cpu",    kDLCPU);
        STR2MASK.put("cuda",   kDLCUDA);
        STR2MASK.put("cl",     kDLOpenCL);
        STR2MASK.put("opencl", kDLOpenCL);
        STR2MASK.put("vulkan", kDLVulkan);
        STR2MASK.put("metal",  kDLMetal);
    }

    public final int deviceType;
    public final int deviceId;

    public Device(int deviceType, int deviceId) {
        this.deviceType = deviceType;
        this.deviceId   = deviceId;
    }

    public Device(String deviceType, int deviceId) {
        this(STR2MASK.get(deviceType), deviceId);
    }

    public static Device cpu()          { return new Device(kDLCPU,    0); }
    public static Device cpu(int id)    { return new Device(kDLCPU,    id); }
    public static Device cuda()         { return new Device(kDLCUDA,   0); }
    public static Device cuda(int id)   { return new Device(kDLCUDA,   id); }
    public static Device opencl()       { return new Device(kDLOpenCL, 0); }
    public static Device opencl(int id) { return new Device(kDLOpenCL, id); }
    public static Device vulkan()       { return new Device(kDLVulkan, 0); }
    public static Device metal()        { return new Device(kDLMetal,  0); }

    public void sync() {
        Base.checkCall(Base._LIB.tvmSynchronize(deviceType, deviceId));
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Device)) return false;
        Device d = (Device) obj;
        return deviceType == d.deviceType && deviceId == d.deviceId;
    }

    @Override
    public int hashCode() { return (deviceType << 16) | deviceId; }

    @Override
    public String toString() {
        String name = MASK2STR.getOrDefault(deviceType, "device(" + deviceType + ")");
        return name + "(" + deviceId + ")";
    }
}
