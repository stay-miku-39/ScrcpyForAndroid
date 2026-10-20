package org.scrcpy.android;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Wire protocol pinned to scrcpy 5.0.1; big endian. Pure Java for protocol tests. */
final class ControlProtocol {
    interface Writer { void write(DataOutputStream out) throws IOException; }
    static byte[] packet(Writer writer) {
        try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); writer.write(new DataOutputStream(bytes)); return bytes.toByteArray(); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    static byte[] touch(int action, long pointer, int x, int y, int width, int height, float pressure) {
        return packet(out -> {
            out.writeByte(2); out.writeByte(action); out.writeLong(pointer);
            out.writeInt(Math.max(0, Math.min(width - 1, x))); out.writeInt(Math.max(0, Math.min(height - 1, y)));
            out.writeShort(width); out.writeShort(height);
            out.writeShort(Math.round(Math.max(0, Math.min(1, pressure)) * 65535)); out.writeInt(0); out.writeInt(0);
        });
    }
    static byte[] key(int action, int code) {
        return key(action, code, 0, 0);
    }
    static byte[] key(int action, int code, int repeat, int metaState) {
        return packet(out -> { out.writeByte(0); out.writeByte(action); out.writeInt(code); out.writeInt(repeat); out.writeInt(metaState); });
    }
    static byte[] keyGroup(int action, int... codes) {
        return packet(out -> {
            for (int i = 0; i < codes.length; i++) {
                int code = codes[action == 1 ? codes.length - 1 - i : i];
                out.write(key(action, code));
            }
        });
    }
    static byte[] clipboard(long sequence, String text, boolean paste) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > (1 << 18) - 14) throw new IllegalArgumentException("剪贴板文本超过 256 KiB");
        return packet(out -> { out.writeByte(9); out.writeLong(sequence); out.writeBoolean(paste); out.writeInt(bytes.length); out.write(bytes); });
    }
    static byte[] startApp(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        return packet(out -> { out.writeByte(16); out.writeByte(bytes.length); out.write(bytes); });
    }
}
