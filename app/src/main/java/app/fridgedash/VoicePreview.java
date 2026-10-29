package app.fridgedash;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Plays a short sample of a voice, using the same Realtime model, voice, language
 * and speed as a real call, so what you hear is what you get. Blocking: run off the UI thread.
 */
final class VoicePreview {
    private VoicePreview() {}

    static String sample(String lang) {
        if ("en".equals(lang)) return "Hi! I'm your fridge assistant. Ask me about the weather, a recipe, or anything else.";
        return "Zdravo! Ja sam tvoj asistent na frižideru. Pitaj me za vremensku prognozu, recept ili bilo šta drugo.";
    }

    /** Returns a status line for the screen. */
    static String play(Context c) {
        String key = Prefs.apiKey(c);
        if (key.isEmpty()) return "Add your OpenAI API key first.";
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        final String[] err = {null};
        final CountDownLatch done = new CountDownLatch(1);
        OkHttpClient http = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(15, TimeUnit.SECONDS).build();
        String model = Prefs.model(c);
        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + model)
                .header("Authorization", "Bearer " + key)
                .build();
        String lang = Prefs.language(c);
        WebSocket ws = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                try {
                    JSONObject out = new JSONObject()
                            .put("format", new JSONObject().put("type", "audio/pcm").put("rate", VoiceSession.RATE))
                            .put("voice", Prefs.voice(c))
                            .put("speed", Prefs.speed(c));
                    JSONObject session = new JSONObject()
                            .put("type", "realtime")
                            .put("instructions", VoiceSession.languageRule(lang) + " " + Prefs.extraInstructions(c))
                            .put("output_modalities", new org.json.JSONArray().put("audio"))
                            .put("audio", new JSONObject().put("output", out));
                    w.send(new JSONObject().put("type", "session.update").put("session", session).toString());
                    JSONObject resp = new JSONObject()
                            .put("instructions", "Say exactly this, nothing else: " + sample(lang));
                    w.send(new JSONObject().put("type", "response.create").put("response", resp).toString());
                } catch (Exception e) {
                    err[0] = e.toString();
                    done.countDown();
                }
            }

            @Override public void onMessage(WebSocket w, String text) {
                try {
                    JSONObject ev = new JSONObject(text);
                    String t = ev.optString("type");
                    if ("response.output_audio.delta".equals(t)) {
                        byte[] b = Base64.decode(ev.optString("delta"), Base64.DEFAULT);
                        pcm.write(b, 0, b.length);
                    } else if ("response.done".equals(t)) {
                        done.countDown();
                    } else if ("error".equals(t)) {
                        JSONObject e = ev.optJSONObject("error");
                        err[0] = e == null ? "error" : e.optString("message");
                        done.countDown();
                    }
                } catch (Exception ignored) {
                }
            }

            @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                err[0] = r != null ? "HTTP " + r.code() + (r.code() == 404 ? " (model not found)" : "") : String.valueOf(t.getMessage());
                done.countDown();
            }
        });
        try {
            done.await(25, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        ws.close(1000, null);
        http.dispatcher().executorService().shutdown();
        if (pcm.size() == 0) return "✗ Couldn't play a sample: " + (err[0] == null ? "no audio came back" : err[0]);
        playPcm(pcm.toByteArray());
        return "▶ " + Prefs.voice(c) + " · " + model;
    }

    private static void playPcm(byte[] data) {
        int rate = VoiceSession.RATE;
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(rate)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        try {
            t.play();
            byte[] lead = new byte[rate * 6 / 10 * 2]; // wake the amplifier
            t.write(lead, 0, lead.length);
            int chunk = rate / 10 * 2;
            for (int off = 0; off < data.length; off += chunk) t.write(data, off, Math.min(chunk, data.length - off));
            long frames = (lead.length + data.length) / 2;
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 20000 && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) < frames) {
                Thread.sleep(50);
            }
        } catch (Exception ignored) {
        } finally {
            try { t.stop(); } catch (Exception ignored) {}
            t.release();
        }
    }
}
