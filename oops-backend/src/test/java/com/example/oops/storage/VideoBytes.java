package com.example.oops.storage;

/** Minimal container headers for upload tests; not playable media. */
public final class VideoBytes {
    private VideoBytes() {}
    public static final byte[] MP4_HEADER = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    public static final byte[] AVI_HEADER = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', ' '};

    public static byte[] mp4(int... tail) {
        byte[] bytes = new byte[tail.length];
        for (int i = 0; i < tail.length; i++) bytes[i] = (byte) tail[i];
        return join(MP4_HEADER, bytes);
    }

    public static byte[] join(byte[] head, byte[] tail) {
        byte[] result = new byte[head.length + tail.length];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(tail, 0, result, head.length, tail.length);
        return result;
    }
}
