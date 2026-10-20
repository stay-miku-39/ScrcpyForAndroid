package org.scrcpy.android;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writer-owned state: only successfully transmitted DOWNs can acquire a remote input. */
final class ControlInputState {
    private final Map<Long, byte[]> pointers = new LinkedHashMap<>();
    private final Map<Integer, byte[]> keys = new LinkedHashMap<>();

    byte[] prepare(byte[] packet) {
        if (ControlQueue.isTouch(packet)) {
            if (packet[1] != 0 && !pointers.containsKey(ControlQueue.pointer(packet))) return ControlQueue.RESET;
        } else if (isKeys(packet)) {
            // Chords remain one write, including when only some keys survived a reset.
            Map<Integer, byte[]> pending = new LinkedHashMap<>(keys);
            return ControlProtocol.packet(out -> {
                for (int offset = 0; offset < packet.length; offset += 14) {
                    int code = keyCode(packet, offset), action = packet[offset + 1];
                    int repeat = ByteBuffer.wrap(packet, offset + 6, 4).getInt();
                    if ((action == 1 || repeat > 0) && !pending.containsKey(code)) continue;
                    out.write(packet, offset, 14);
                    if (action == 1) pending.remove(code); else pending.put(code, packet);
                }
            });
        }
        return packet;
    }
    void sent(byte[] packet) {
        if (ControlQueue.isTouch(packet)) {
            long pointer = ControlQueue.pointer(packet);
            if (packet[1] == 1) pointers.remove(pointer);
            else {
                byte[] up = packet.clone(); up[1] = 1; up[22] = 0; up[23] = 0;
                pointers.put(pointer, up);
            }
        } else if (isKeys(packet)) {
            for (int offset = 0; offset < packet.length; offset += 14) {
                int code = keyCode(packet, offset);
                if (packet[offset + 1] == 1) keys.remove(code);
                else keys.put(code, ControlProtocol.key(1, code));
            }
        }
    }
    byte[] releases() {
        return ControlProtocol.packet(out -> {
            for (byte[] up : pointers.values()) out.write(up);
            ArrayList<byte[]> held = new ArrayList<>(keys.values());
            for (int i = held.size() - 1; i >= 0; i--) out.write(held.get(i));
        });
    }
    void released() { pointers.clear(); keys.clear(); }
    private static boolean isKeys(byte[] packet) { return packet.length > 0 && packet[0] == 0 && packet.length % 14 == 0; }
    private static int keyCode(byte[] packet, int offset) { return ByteBuffer.wrap(packet, offset + 2, 4).getInt(); }
}
