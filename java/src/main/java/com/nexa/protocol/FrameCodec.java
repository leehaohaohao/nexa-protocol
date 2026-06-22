package com.nexa.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * TCP 帧编解码器，使用 4 字节大端序长度前缀协议
 */
public class FrameCodec {

    /** 单帧最大 10MB */
    private static final int MAX_FRAME_SIZE = 10 * 1024 * 1024;

    private FrameCodec() {
    }

    /**
     * 写一帧：[4字节大端序长度][data]
     */
    public static void writeFrame(OutputStream out, byte[] data) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(data.length); // writeInt 本身即为大端序
        dos.write(data);
        dos.flush();
    }

    /**
     * 读一帧：先读取 4 字节长度头，再读取对应长度的数据
     */
    public static byte[] readFrame(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int length = dis.readInt();
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new IOException("frame too large: " + length + " bytes");
        }
        byte[] data = new byte[length];
        dis.readFully(data);
        return data;
    }
}
