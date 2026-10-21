package org.scrcpy.android;

import java.util.*;

/** Local decoder capability selects defaults; local encoders cannot certify a remote encoder. */
final class CodecPolicy {
    static final class Component {
        final String name, format;
        final boolean encoder, hardware;
        Component(String name, String format, boolean encoder, boolean hardware) { this.name=name; this.format=format; this.encoder=encoder; this.hardware=hardware; }
        boolean automaticSafe() { return !name.startsWith("c2.goldfish."); }
    }
    static Map<String, String> recommend(List<Component> components) {
        Map<String, String> values = new LinkedHashMap<>(StreamSettings.DEFAULTS);
        for (String video : new String[]{"h264", "h265", "vp9", "av1", "vp8"}) {
            Component match = hardwareDecoder(components, video);
            // Keep automatic component selection: a later format change must
            // not remain pinned to the old format's named decoder.
            if (match != null) { values.put("videoCodec",video); break; }
        }
        for (String audio : new String[]{"aac", "opus"}) {
            Component match = hardwareDecoder(components, audio);
            if (match != null) { values.put("audioCodec",audio); return values; }
        }
        for (String audio : new String[]{"opus", "aac"}) if (components.stream().anyMatch(c -> !c.encoder && c.format.equals(audio) && c.automaticSafe())) { values.put("audioCodec",audio); break; }
        return values;
    }
    private static Component hardwareDecoder(List<Component> components, String format) {
        for (Component c : components) if (!c.encoder && c.hardware && c.format.equals(format) && c.automaticSafe()) return c;
        return null;
    }
}
