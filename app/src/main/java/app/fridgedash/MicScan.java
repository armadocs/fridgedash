package app.fridgedash;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Tries the microphone in every way the fridge's audio system declares,
 * including 4- and 8-channel recording (mic arrays often deliver sound only
 * that way), and measures each channel separately.
 */
final class MicScan {
    private MicScan() {}

    /** A way of opening the mic plus the one channel worth listening to. */
    static final class Config {
        final int source, rate, channels, channel;
        Config(int source, int rate, int channels, int channel) {
            this.source = source; this.rate = rate; this.channels = channels; this.channel = channel;
        }
        String encode() { return source + "," + rate + "," + channels + "," + channel; }
        static Config decode(String s) {
            try {
                String[] p = s.split(",");
                return new Config(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                        Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } catch (Exception e) {
                return null;
            }
        }
    }

    interface Progress { void onProgress(String line); }

    private static final int[] SOURCES = {
            MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.CAMCORDER};
    private static final String[] SOURCE_NAMES = {"MIC", "VOICE_RECOG", "UNPROCESSED", "CAMCORDER"};
    private static final int[] RATES = {16000, 48000};
    private static final int[] CHANNELS = {1, 2, 4, 8};

    /** Opens an AudioRecord for a config, or returns null. */
    static AudioRecord open(Config c) {
        try {
            AudioFormat.Builder f = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(c.rate);
            if (c.channels == 1) f.setChannelMask(AudioFormat.CHANNEL_IN_MONO);
            else if (c.channels == 2) f.setChannelMask(AudioFormat.CHANNEL_IN_STEREO);
            else f.setChannelIndexMask((1 << c.channels) - 1);
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioSource(c.source)
                    .setAudioFormat(f.build())
                    .setBufferSizeInBytes(c.rate * c.channels * 2 / 2) // 0.5 s
                    .build();
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Copies one channel out of interleaved frames. */
    static int extract(short[] interleaved, int samples, int channels, int channel, short[] out) {
        int frames = samples / channels;
        for (int i = 0; i < frames; i++) out[i] = interleaved[i * channels + channel];
        return frames;
    }

    /** ~35 s. Saves the loudest working config to Prefs and returns a readable report. */
    static String run(Context app, Progress progress) {
        StringBuilder sb = new StringBuilder();
        Config best = null;
        float bestLevel = 0;
        for (int s = 0; s < SOURCES.length; s++) {
            for (int rate : RATES) {
                for (int ch : CHANNELS) {
                    Config probe = new Config(SOURCES[s], rate, ch, 0);
                    String label = SOURCE_NAMES[s] + " " + (rate / 1000) + "k ×" + ch + ": ";
                    if (progress != null) progress.onProgress("Scanning " + label + " keep clapping/talking…");
                    AudioRecord r = open(probe);
                    if (r == null) {
                        sb.append(label).append("can't open\n");
                        continue;
                    }
                    float[] peak = new float[ch];
                    long frames = 0;
                    try {
                        r.startRecording();
                        short[] buf = new short[rate / 10 * ch];
                        long t0 = System.currentTimeMillis();
                        while (System.currentTimeMillis() - t0 < 1000) {
                            int n = r.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
                            if (n <= 0) {
                                try { Thread.sleep(20); } catch (InterruptedException e) { break; }
                                continue;
                            }
                            int fr = n / ch;
                            frames += fr;
                            for (int c = 0; c < ch; c++) {
                                double sum = 0;
                                for (int i = 0; i < fr; i++) {
                                    double v = buf[i * ch + c];
                                    sum += v * v;
                                }
                                float lvl = (float) Math.min(1.0, Math.sqrt(sum / Math.max(1, fr)) / 6000.0);
                                if (lvl > peak[c]) peak[c] = lvl;
                            }
                        }
                    } catch (Exception e) {
                        sb.append(label).append("error\n");
                        continue;
                    } finally {
                        try { r.stop(); } catch (Exception ignored) {}
                        r.release();
                    }
                    if (frames == 0) {
                        sb.append(label).append("no data\n");
                        continue;
                    }
                    sb.append(label);
                    for (int c = 0; c < ch; c++) {
                        int pct = Math.round(peak[c] * 100);
                        sb.append(ch > 1 ? "c" + c + "=" : "").append(pct).append("% ");
                        if (peak[c] > bestLevel) {
                            bestLevel = peak[c];
                            best = new Config(SOURCES[s], rate, ch, c);
                        }
                    }
                    sb.append('\n');
                }
            }
        }
        String head;
        if (best != null && bestLevel >= 0.03f) {
            Prefs.setMicScan(app, best.encode());
            head = "✓ FOUND SOUND: " + nameOf(best.source) + " " + (best.rate / 1000) + "k, " + best.channels
                    + " channels, channel " + best.channel + " (" + Math.round(bestLevel * 100)
                    + "%). Calls will use this.\n\n";
        } else {
            head = "✗ No channel picked up sound (best " + Math.round(bestLevel * 100) + "%).\n\n";
        }
        return head + sb;
    }

    private static String nameOf(int source) {
        for (int i = 0; i < SOURCES.length; i++) if (SOURCES[i] == source) return SOURCE_NAMES[i];
        return String.valueOf(source);
    }
}
