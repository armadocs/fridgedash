package app.fridgedash;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Read-only deep probe of why there's no microphone: what the Bluetooth service
 * is configured to support, what the audio system declares as inputs, and what
 * capture hardware the kernel exposes. Nothing here changes anything.
 */
final class Probe {
    private Probe() {}

    private static final String[] BT_PROFILES = {
            "profile_supported_hs_hfp", "profile_supported_hfpclient", "profile_supported_a2dp",
            "profile_supported_a2dp_sink", "profile_supported_avrcp_target", "profile_supported_avrcp_controller",
            "profile_supported_hid_host", "profile_supported_hid_device", "profile_supported_opp",
            "profile_supported_pan", "profile_supported_pbap", "profile_supported_map",
            "profile_supported_sap", "profile_supported_hearing_aid", "profile_supported_gatt"};

    static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("== DEEP PROBE: Bluetooth ==\n");
        bluetooth(ctx, sb);
        sb.append('\n');

        sb.append("== DEEP PROBE: audio system ==\n");
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            sb.append("SCO allowed outside phone calls: ").append(am.isBluetoothScoAvailableOffCall()).append('\n');
            sb.append("Audio mode now: ").append(am.getMode()).append('\n');
            sb.append("Mic muted (system): ").append(am.isMicrophoneMute()).append('\n');
            String[] keys = {"android.media.property.OUTPUT_SAMPLE_RATE", "android.media.property.OUTPUT_FRAMES_PER_BUFFER"};
            for (String k : keys) sb.append(k.substring(k.lastIndexOf('.') + 1)).append(": ").append(am.getProperty(k)).append('\n');
        } catch (Exception e) {
            sb.append("AudioManager: ").append(e).append('\n');
        }
        sb.append('\n');

        String[] policyFiles = {
                "/vendor/etc/audio_policy_configuration.xml", "/system/etc/audio_policy_configuration.xml",
                "/odm/etc/audio_policy_configuration.xml", "/vendor/etc/audio_policy.conf", "/system/etc/audio_policy.conf"};
        Pattern interesting = Pattern.compile(
                "(?i)(<module|attachedDevices|<item>|devicePort|AUDIO_DEVICE_IN|_IN_|inputs|BUILTIN_MIC|SCO|USB|mixPort.*source|role=\"sink\")");
        for (String f : policyFiles) dumpFile(sb, f, interesting, 140);
        // Mic-array (AISpeech) and include lines, unfiltered.
        dumpFile(sb, "/vendor/etc/aispeech_audio_policy_configuration.xml", null, 120);
        dumpFile(sb, "/vendor/etc/audio_policy_configuration.xml", Pattern.compile("(?i)include"), 20);
        dumpFile(sb, "/vendor/etc/audio_device.xml", Pattern.compile("(?i)(mic|record|capture|in_|path|device)"), 120);
        dumpFile(sb, "/vendor/etc/mixer_paths.xml", Pattern.compile("(?i)(<path|mic|adc|tdm|capture|record)"), 120);

        sb.append("== Audio/Bluetooth config files present ==\n");
        listDir(sb, "/vendor/etc", "(?i).*(audio|mixer|bluetooth|bt_).*");
        listDir(sb, "/system/etc", "(?i).*(audio|mixer|bluetooth|bt_).*");
        listDir(sb, "/vendor/etc/bluetooth", ".*");
        listDir(sb, "/system/etc/bluetooth", ".*");
        sb.append('\n');

        sb.append("== Kernel sound hardware ==\n");
        dumpFile(sb, "/proc/asound/cards", null, 40);
        dumpFile(sb, "/proc/asound/pcm", null, 60);
        dumpFile(sb, "/proc/asound/devices", null, 60);
        listDir(sb, "/dev/snd", ".*");
        sb.append('\n');

        sb.append("== USB ==\n");
        PackageManager pm = ctx.getPackageManager();
        sb.append("USB host supported: ").append(pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)).append('\n');
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null) {
                for (UsbDevice d : um.getDeviceList().values()) {
                    sb.append("USB device: ").append(d.getProductName()).append(" class ").append(d.getDeviceClass())
                            .append(" vid ").append(Integer.toHexString(d.getVendorId()))
                            .append(" pid ").append(Integer.toHexString(d.getProductId())).append('\n');
                }
                if (um.getDeviceList().isEmpty()) sb.append("No USB devices attached\n");
            }
        } catch (Exception e) {
            sb.append("UsbManager: ").append(e).append('\n');
        }
        sb.append('\n');

        sb.append("== System properties (audio / bluetooth / vendor) ==\n");
        sb.append("Mic-array related services/properties:\n");
        getprop(sb, "(?i)(aispeech|dui|duilite|mic_?array|tdm|i2s|asr|wakeup|ai\\.)");
        getprop(sb, "(?i)(bluetooth|\\.bt\\.|bt\\.|audio|\\.mic|sco|hfp|usb\\.config|hisense|hmct|voice|vendor\\.product|ro\\.product|ro\\.board|ro\\.hardware|ro\\.build\\.(version|type|tags))");
        return sb.toString();
    }

    private static void bluetooth(Context ctx, StringBuilder sb) {
        // 1. What the Bluetooth app itself is configured to support (its config.xml / vendor overlay).
        try {
            Resources r = ctx.getPackageManager().getResourcesForApplication("com.android.bluetooth");
            for (String name : BT_PROFILES) {
                int id = r.getIdentifier(name, "bool", "com.android.bluetooth");
                sb.append(name.replace("profile_supported_", "supports ")).append(": ")
                        .append(id == 0 ? "(not defined)" : String.valueOf(r.getBoolean(id))).append('\n');
            }
        } catch (Exception e) {
            sb.append("Bluetooth app config: ").append(e.getClass().getSimpleName()).append(' ').append(e.getMessage()).append('\n');
        }
        // 2. Profiles switched off through Android's hidden global setting (fixable with ADB if set).
        try {
            sb.append("Global bluetooth_disabled_profiles: ")
                    .append(Settings.Global.getString(ctx.getContentResolver(), "bluetooth_disabled_profiles")).append('\n');
            sb.append("Global bluetooth_disabled_profiles_bits: ")
                    .append(Settings.Global.getString(ctx.getContentResolver(), "bluetooth_disabled_profiles_bits")).append('\n');
        } catch (Exception e) {
            sb.append("Global settings: ").append(e.getClass().getSimpleName()).append('\n');
        }
        // 3. Profiles the running Bluetooth stack reports (hidden API, best effort).
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) {
                sb.append("Adapter enabled: ").append(a.isEnabled()).append('\n');
                Method m = BluetoothAdapter.class.getMethod("getSupportedProfiles");
                Object list = m.invoke(a);
                sb.append("Running stack profiles (ids, 1=HEADSET 2=A2DP 16=HFP client): ").append(list).append('\n');
            }
        } catch (Throwable t) {
            sb.append("Running stack profiles: unavailable (").append(t.getClass().getSimpleName()).append(")\n");
        }
    }

    private static void dumpFile(StringBuilder sb, String path, Pattern filter, int maxLines) {
        File f = new File(path);
        if (!f.exists()) {
            return;
        }
        sb.append("--- ").append(path).append(f.canRead() ? "" : " (not readable)").append('\n');
        if (!f.canRead()) return;
        int n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null && n < maxLines) {
                if (filter == null || filter.matcher(line).find()) {
                    sb.append(line.trim()).append('\n');
                    n++;
                }
            }
            if (n >= maxLines) sb.append("…(truncated)\n");
        } catch (Exception e) {
            sb.append("read error: ").append(e.getMessage()).append('\n');
        }
    }

    private static void listDir(StringBuilder sb, String path, String regex) {
        File d = new File(path);
        String[] names = d.list();
        if (names == null) {
            sb.append(path).append(": ").append(d.exists() ? "not listable" : "missing").append('\n');
            return;
        }
        java.util.Arrays.sort(names);
        StringBuilder hits = new StringBuilder();
        for (String n : names) if (n.matches(regex)) hits.append(n).append(' ');
        sb.append(path).append(": ").append(hits.length() == 0 ? "(no matches)" : hits.toString().trim()).append('\n');
    }

    private static void getprop(StringBuilder sb, String regex) {
        Pattern p = Pattern.compile(regex);
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"getprop"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                int n = 0;
                while ((line = r.readLine()) != null && n < 250) {
                    if (p.matcher(line).find()) { sb.append(line).append('\n'); n++; }
                }
            }
            proc.destroy();
        } catch (Exception e) {
            sb.append("getprop: ").append(e.getMessage()).append('\n');
        }
    }
}
