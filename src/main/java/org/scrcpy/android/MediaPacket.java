package org.scrcpy.android;

import java.io.*;

/** scrcpy v5 adds 12-byte video session headers on start and rotation. */
final class MediaPacket {
    final boolean session, config;
    final int width, height;
    final long pts;
    final byte[] data;
    private MediaPacket(boolean session, boolean config, int width, int height, long pts, byte[] data) {
        this.session = session; this.config = config; this.width = width; this.height = height; this.pts = pts; this.data = data;
    }
    static MediaPacket read(DataInputStream in) throws IOException {
        long flags = in.readLong(); int size = in.readInt();
        if (flags < 0) {
            int width = (int)flags, height = size;
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384) throw new IOException("Invalid video session dimensions");
            return new MediaPacket(true, false, width, height, 0, null);
        }
        if (size <= 0 || size > 16 * 1024 * 1024) throw new IOException("Invalid media packet length: " + size);
        byte[] data = new byte[size]; in.readFully(data);
        return new MediaPacket(false, (flags & (1L << 62)) != 0, 0, 0, flags & ((1L << 61) - 1), data);
    }
}
