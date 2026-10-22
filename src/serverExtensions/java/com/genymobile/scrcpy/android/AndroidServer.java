package com.genymobile.scrcpy.android;

import com.genymobile.scrcpy.Server;
import java.util.Arrays;

/** Host-only entry point; upstream arguments and wire protocol remain unchanged. */
public final class AndroidServer {
    private AndroidServer() { }
    public static void main(String... args) {
        if (args.length < 2) throw new IllegalArgumentException("Missing bitrate limit and server arguments");
        int limit = Integer.parseInt(args[0]);
        if (limit < 100000 || limit > 100000000) throw new IllegalArgumentException("Invalid bitrate limit");
        System.setProperty("org.scrcpy.android.videoBitRateLimit", Integer.toString(limit));
        Server.main(Arrays.copyOfRange(args, 1, args.length));
    }
}
