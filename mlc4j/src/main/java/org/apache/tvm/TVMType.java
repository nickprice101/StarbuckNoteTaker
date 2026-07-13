package org.apache.tvm;

/** Data type descriptor for TVM tensors. */
public class TVMType {
    public static final int INT = 0;
    public static final int UINT = 1;
    public static final int FLOAT = 2;
    public static final int HANDLE = 4;

    public final int typeCode;
    public final int bits;
    public final int numOfBytes;
    public final int lanes;

    public TVMType(String typeStr, int lanes) {
        this.lanes = lanes;
        int parsedBits;
        if (typeStr.startsWith("int")) {
            typeCode = INT;
            parsedBits = Integer.parseInt(typeStr.substring(3));
        } else if (typeStr.startsWith("uint")) {
            typeCode = UINT;
            parsedBits = Integer.parseInt(typeStr.substring(4));
        } else if (typeStr.startsWith("float")) {
            typeCode = FLOAT;
            parsedBits = Integer.parseInt(typeStr.substring(5));
        } else if (typeStr.startsWith("handle")) {
            typeCode = HANDLE;
            parsedBits = 64;
        } else {
            throw new IllegalArgumentException("Unknown TVM type " + typeStr);
        }
        bits = parsedBits == 0 ? 32 : parsedBits;
        if ((bits & (bits - 1)) != 0 || bits < 8) {
            throw new IllegalArgumentException("Unsupported TVM type " + typeStr);
        }
        numOfBytes = bits / 8;
    }

    public TVMType(String typeStr) {
        this(typeStr, 1);
    }

    @Override
    public int hashCode() {
        return (typeCode << 16) | (bits << 8) | lanes;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TVMType)) return false;
        TVMType that = (TVMType) other;
        return typeCode == that.typeCode && bits == that.bits && lanes == that.lanes;
    }

    @Override
    public String toString() {
        String prefix;
        switch (typeCode) {
            case INT:
                prefix = "int";
                break;
            case UINT:
                prefix = "uint";
                break;
            case FLOAT:
                prefix = "float";
                break;
            case HANDLE:
                prefix = "handle";
                break;
            default:
                prefix = "unknown";
                break;
        }
        return lanes == 1 ? prefix + bits : prefix + bits + lanes;
    }
}
