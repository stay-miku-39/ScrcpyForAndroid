package org.scrcpy.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class ProtocolTest {
    @Test public void powerChordIsOnePacketAndReleasesInReverseOrder() throws Exception {
        DataInputStream down = new DataInputStream(new ByteArrayInputStream(ControlProtocol.keyGroup(0, 26, 25)));
        DataInputStream up = new DataInputStream(new ByteArrayInputStream(ControlProtocol.keyGroup(1, 26, 25)));
        for (int code : new int[]{26, 25}) { assertEquals(0, down.readUnsignedByte()); assertEquals(0, down.readUnsignedByte()); assertEquals(code, down.readInt()); down.skipBytes(8); }
        for (int code : new int[]{25, 26}) { assertEquals(0, up.readUnsignedByte()); assertEquals(1, up.readUnsignedByte()); assertEquals(code, up.readInt()); up.skipBytes(8); }
        assertEquals(-1, down.read()); assertEquals(-1, up.read());
    }
    @Test public void hardwareKeysPreserveHoldRepeatAndMetaState() throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(ControlProtocol.key(0, 24, 3, 1)));
        assertEquals(0, in.readUnsignedByte()); assertEquals(0, in.readUnsignedByte());
        assertEquals(24, in.readInt()); assertEquals(3, in.readInt()); assertEquals(1, in.readInt()); assertEquals(-1, in.read());
    }
    @Test public void multiTouchCarriesStablePointerAndVideoCoordinates() throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(ControlProtocol.touch(0, 42, 123, 456, 1080, 1920, 1)));
        assertEquals(2, in.readUnsignedByte()); assertEquals(0, in.readUnsignedByte()); assertEquals(42, in.readLong());
        assertEquals(123, in.readInt()); assertEquals(456, in.readInt()); assertEquals(1080, in.readUnsignedShort()); assertEquals(1920, in.readUnsignedShort());
        assertEquals(65535, in.readUnsignedShort()); assertEquals(0, in.readInt()); assertEquals(0, in.readInt()); assertEquals(-1, in.read());
    }
    @Test public void clipboardUsesUtf8ByteLengthAndSequence() throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(ControlProtocol.clipboard(7, "安卓🎉", true)));
        assertEquals(9, in.readUnsignedByte()); assertEquals(7, in.readLong()); assertTrue(in.readBoolean());
        byte[] bytes = "安卓🎉".getBytes(StandardCharsets.UTF_8); assertEquals(bytes.length, in.readInt());
        byte[] actual = new byte[bytes.length]; in.readFully(actual); assertArrayEquals(bytes, actual);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsOversizedClipboard() { ControlProtocol.clipboard(1, new String(new char[262144]), false); }
    @Test public void readsV5SessionHeaderAndMediaFlags() throws Exception {
        byte[] data = ControlProtocol.packet(out -> {
            out.writeInt(0x80000001); out.writeInt(1600); out.writeInt(900);
            out.writeLong((1L << 62)); out.writeInt(3); out.write(new byte[]{0,1,2});
            out.writeLong((1L << 61) | 12345L); out.writeInt(1); out.writeByte(9);
        });
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        MediaPacket session = MediaPacket.read(in); assertTrue(session.session); assertEquals(1600, session.width); assertEquals(900, session.height);
        assertTrue(MediaPacket.read(in).config); MediaPacket frame = MediaPacket.read(in); assertEquals(12345, frame.pts); assertFalse(frame.config);
    }
    @Test(expected=IOException.class) public void rejectsUnboundedFrameAllocation() throws Exception {
        MediaPacket.read(new DataInputStream(new ByteArrayInputStream(ControlProtocol.packet(out -> { out.writeLong(0); out.writeInt(Integer.MAX_VALUE); }))));
    }
    @Test(expected=EOFException.class) public void rejectsTruncatedFrame() throws Exception {
        MediaPacket.read(new DataInputStream(new ByteArrayInputStream(ControlProtocol.packet(out -> { out.writeLong(0); out.writeInt(10); out.writeByte(1); }))));
    }
    @Test public void scannerRequiresOneNumericIp() { assertEquals("192.168.1.10", PortScanner.address("192.168.1.10").getHostAddress()); }
    @Test(expected=IllegalArgumentException.class) public void rejectsScanHostname() { PortScanner.address("example.com"); }
    @Test(expected=IllegalArgumentException.class) public void rejectsScanSubnet() { PortScanner.address("192.168.1.0/24"); }
}
