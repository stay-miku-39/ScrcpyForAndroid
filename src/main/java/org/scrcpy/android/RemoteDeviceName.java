package org.scrcpy.android;

/** Display-only name, independent of saved aliases, ADB identity and protocol metadata. */
final class RemoteDeviceName {
    static String parse(String output) {
        // settings global device_name first, product model second. Never display shell errors.
        for (String line : output.split("\\r?\\n")) {
            String name = line.trim();
            if (name.isEmpty() || "null".equalsIgnoreCase(name) || "unknown".equalsIgnoreCase(name)
                    || name.startsWith("Error") || name.startsWith("Exception") || name.contains("Permission denial")
                    || name.startsWith("/system/bin/sh:")) continue;
            return name.length() > 256 ? name.substring(0, 256) : name;
        }
        return "Android";
    }
}
