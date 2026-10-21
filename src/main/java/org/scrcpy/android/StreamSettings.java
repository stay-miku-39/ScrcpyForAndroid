package org.scrcpy.android;

import java.util.*;

/** Immutable per-session snapshot. Display pixels and encoded video pixels are independent. */
final class StreamSettings {
    static final String[] RESOLUTIONS = {"original", "local", "local720", "local1080", "local1440", "720x1280", "1080x1920", "1440x2560", "custom"};
    static final String[] VIDEOS = {"h264", "h265", "av1", "vp8", "vp9"};
    static final String[] AUDIOS = {"raw", "aac", "opus", "flac"};
    static final Map<String, String> DEFAULTS;
    static {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("resolution", "original"); values.put("backend", "main");
        values.put("customWidth", "1080"); values.put("customHeight", "1920");
        values.put("maxSize", "1600"); values.put("fps", "60"); values.put("videoBitRate", "6000000"); values.put("audioBitRate", "128000");
        values.put("videoCodec", "h264"); values.put("audioCodec", "raw");
        values.put("videoBitRateMode", "auto");
        for (String key : new String[]{"videoEncoder", "videoDecoder", "audioEncoder", "audioDecoder", "virtualApp"}) values.put(key, "");
        DEFAULTS = Collections.unmodifiableMap(values);
    }
    final Map<String, String> values;
    final String resolution, backend, videoCodec, audioCodec, videoEncoder, videoDecoder, audioEncoder, audioDecoder, virtualApp, videoBitRateMode;
    final int maxSize, fps, videoBitRate, audioBitRate, localWidth, localHeight;
    StreamSettings(Map<String, String> input, int localWidth, int localHeight) {
        Map<String, String> copy = new LinkedHashMap<>(DEFAULTS); copy.putAll(input); validate(copy);
        values = Collections.unmodifiableMap(copy); this.localWidth = localWidth; this.localHeight = localHeight;
        resolution = copy.get("resolution"); backend = copy.get("backend"); videoCodec = copy.get("videoCodec"); audioCodec = copy.get("audioCodec");
        videoEncoder = copy.get("videoEncoder"); videoDecoder = copy.get("videoDecoder"); audioEncoder = copy.get("audioEncoder"); audioDecoder = copy.get("audioDecoder"); virtualApp = copy.get("virtualApp");
        videoBitRateMode = copy.get("videoBitRateMode");
        maxSize = Integer.parseInt(copy.get("maxSize")); fps = Integer.parseInt(copy.get("fps"));
        videoBitRate = Integer.parseInt(copy.get("videoBitRate")); audioBitRate = Integer.parseInt(copy.get("audioBitRate"));
    }
    static void validate(Map<String, String> v) {
        choice(v, "resolution", RESOLUTIONS); choice(v, "backend", new String[]{"main", "virtual"}); choice(v, "videoCodec", VIDEOS); choice(v, "audioCodec", AUDIOS);
        choice(v, "videoBitRateMode", new String[]{"auto", "cbr"});
        number(v, "customWidth", 128, 8192); number(v, "customHeight", 128, 8192);
        int max = number(v, "maxSize", 0, 8192); if (max != 0 && max < 128) throw new IllegalArgumentException("视频最大边需为 0（不限）或 128–8192");
        number(v, "fps", 1, 240); number(v, "videoBitRate", 100000, 100000000); number(v, "audioBitRate", 16000, 512000);
        for (String key : new String[]{"videoEncoder", "videoDecoder", "audioEncoder", "audioDecoder", "virtualApp"})
            if (!v.get(key).isEmpty() && !v.get(key).matches("[A-Za-z0-9_.-]{1,200}")) throw new IllegalArgumentException("组件名/包名仅支持字母、数字、点、下划线及短横线");
    }
    private static void choice(Map<String, String> v, String key, String[] allowed) { if (!Arrays.asList(allowed).contains(v.get(key))) throw new IllegalArgumentException("无效选项：" + key); }
    private static int number(Map<String, String> v, String key, int min, int max) {
        try { int n = Integer.parseInt(v.get(key)); if (n >= min && n <= max) return n; } catch (RuntimeException ignored) { }
        throw new IllegalArgumentException(key + " 范围为 " + min + "–" + max);
    }
    int[] displaySize(int originalWidth, int originalHeight) {
        if ("original".equals(resolution)) return new int[]{originalWidth, originalHeight};
        if ("custom".equals(resolution)) return new int[]{Integer.parseInt(values.get("customWidth")), Integer.parseInt(values.get("customHeight"))};
        if ("local".equals(resolution)) return new int[]{localWidth, localHeight};
        if (resolution.startsWith("local")) {
            int edge = Integer.parseInt(resolution.substring(5));
            double scale = edge / (double)Math.min(localWidth, localHeight);
            return new int[]{align(localWidth * scale), align(localHeight * scale)};
        }
        String[] size = resolution.split("x"); return new int[]{Integer.parseInt(size[0]), Integer.parseInt(size[1])};
    }
    private static int align(double n) { return Math.max(128, Math.min(8192, (int)Math.round(n / 8) * 8)); }
    String videoRateOptions(boolean safeVideo) { return !safeVideo && "cbr".equals(videoBitRateMode) ? " video_codec_options=bitrate-mode=2" : ""; }
    static String videoMime(String codec) {
        switch (codec) { case "h265": return "video/hevc"; case "av1": return "video/av01"; case "vp8": return "video/x-vnd.on2.vp8"; case "vp9": return "video/x-vnd.on2.vp9"; default: return "video/avc"; }
    }
    static String audioMime(String codec) {
        switch (codec) { case "aac": return "audio/mp4a-latm"; case "opus": return "audio/opus"; case "flac": return "audio/flac"; default: return "audio/raw"; }
    }
    static int videoId(String codec) {
        switch (codec) { case "h265": return 0x68323635; case "av1": return 0x00617631; case "vp8": return 0x00767038; case "vp9": return 0x00767039; default: return 0x68323634; }
    }
    static int audioId(String codec) {
        switch (codec) { case "aac": return 0x00616163; case "opus": return 0x6f707573; case "flac": return 0x666c6163; default: return 0x00726177; }
    }
}
