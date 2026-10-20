package org.scrcpy.android;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Bounded setup fallback; established sessions never reconnect through this policy. */
final class DisplayFallback {
    interface Start { void run(String route) throws Exception; }
    interface Failed { void run(String route, Exception error); }
    static void run(String preferred, Start start, BooleanSupplier retryAllowed, Failed failed) throws Exception {
        String[] routes = "virtual".equals(preferred) ? new String[]{"virtual", "main", "native"} : new String[]{"main", "virtual", "native"};
        Exception last = null;
        for (String route : routes) {
            try { start.run(route); return; }
            catch (Exception error) {
                if (!retryAllowed.getAsBoolean()) throw error;
                last = error;
                if (!"native".equals(route)) failed.run(route, error);
            }
        }
        throw new IOException("所有投屏启动方式均失败", last);
    }
}
