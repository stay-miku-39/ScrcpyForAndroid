package org.scrcpy.android;

import android.app.Activity;
import android.media.*;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Test APK only: inspect the MIC and VOICE_COMMUNICATION input of a different UID. */
public class MicrophoneProbeActivity extends Activity {
    private volatile boolean running;
    private volatile AudioRecord recorder;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); running = true;
        TextView label = new TextView(this); label.setText("Microphone input probe"); label.setTextSize(24); setContentView(label);
        String phase = getIntent().getStringExtra("phase");
        new Thread(() -> {
            try {
                for (int source : new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION}) {
                    // A newly launched app/rotating encoder may briefly underrun. Keep the
                    // full tone threshold, but allow routing to settle before asserting it.
                    for (int attempt = 0; attempt < (getIntent().getBooleanExtra("expectTone", false) ? 3 : 1); attempt++) {
                    if (!running) break;
                    AudioRecord record = new AudioRecord.Builder().setAudioSource(source)
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(9600).build();
                    recorder = record;
                    try {
                        record.startRecording(); short[] samples = new short[48000]; int total = 0;
                        while (running && total < samples.length) {
                            int n = record.read(samples, total, samples.length - total);
                            if (n <= 0) throw new IllegalStateException("read=" + n); total += n;
                        }
                        double energy = 0, sine = 0, cosine = 0;
                        for (int i = 12000; i < total; i++) {
                            energy += (double)samples[i] * samples[i];
                            sine += samples[i] * Math.sin(2 * Math.PI * 1000 * i / 48000);
                            cosine += samples[i] * Math.cos(2 * Math.PI * 1000 * i / 48000);
                        }
                        int count = Math.max(1, total - 12000);
                        double amplitude = 2 * Math.hypot(sine, cosine) / count;
                        AudioDeviceInfo device = record.getRoutedDevice(); int route = device == null ? -1 : device.getType();
                        String result = "MIC_INPUT phase=" + phase + " source=" + source + " route=" + route
                                + " rms=" + Math.sqrt(energy / count) + " amplitude=" + amplitude + " tone=" + (amplitude > 5000 ? "PASS" : "NO_TONE");
                        Log.i("MicInputProbe", result); runOnUiThread(() -> label.setText(result));
                        if (amplitude > 5000) break;
                    } finally { recorder = null; try { record.stop(); } catch (RuntimeException ignored) { } record.release(); }
                    }
                }
            } catch (Exception e) { if (running) Log.e("MicInputProbe", "MIC_INPUT phase=" + phase + " FAIL", e); }
        }, "mic-input-probe").start();
    }
    @Override protected void onDestroy() {
        running = false; AudioRecord current = recorder;
        if (current != null) try { current.stop(); } catch (RuntimeException ignored) { }
        super.onDestroy();
    }
}
