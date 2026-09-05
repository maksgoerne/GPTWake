package com.desmond.gptwake;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Composite voice-session monitor: AudioManager mode, active recording configurations and playback
 * activity. Playback transitions let WakeController start its idle timer only after ChatGPT has
 * finished speaking, instead of timing from the beginning of a voice session.
 */
public final class AudioStateMonitor {

    public interface Listener {
        void onAudioStateChanged(String why);
    }

    private static volatile Listener extListener;

    public static void setListener(Listener l) {
        extListener = l;
    }

    private static void notifyListener(String why) {
        Listener l = extListener;
        if (l != null) {
            try {
                l.onAudioStateChanged(why);
            } catch (Throwable t) {
                L.e("AUDIO_LISTENER_FAIL", t);
            }
        }
    }

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicInteger LAST_MODE = new AtomicInteger(AudioManager.MODE_NORMAL);
    private static volatile AudioManager am;
    private static volatile boolean assistantPlaybackActive;
    private static volatile long lastVoiceAudioActivityMs;

    public static String modeName(int m) {
        switch (m) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            case AudioManager.MODE_CALL_SCREENING: return "CALL_SCREENING";
            default: return "MODE_" + m;
        }
    }

    public static String sourceName(int s) {
        switch (s) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_UPLINK: return "VOICE_UPLINK";
            case MediaRecorder.AudioSource.VOICE_DOWNLINK: return "VOICE_DOWNLINK";
            case MediaRecorder.AudioSource.VOICE_CALL: return "VOICE_CALL";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
            case MediaRecorder.AudioSource.VOICE_PERFORMANCE: return "VOICE_PERFORMANCE";
            default: return "SRC_" + s;
        }
    }

    public static void install(Context c) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            am = c.getApplicationContext().getSystemService(AudioManager.class);
            LAST_MODE.set(am.getMode());
            lastVoiceAudioActivityMs = System.currentTimeMillis();
            L.i("AUDIO_MODE_INITIAL=" + modeName(LAST_MODE.get()));

            am.addOnModeChangedListener(Executors.newSingleThreadExecutor(), mode -> {
                LAST_MODE.set(mode);
                lastVoiceAudioActivityMs = System.currentTimeMillis();
                L.i("AUDIO_MODE_CHANGED=" + modeName(mode)
                        + " voiceConfirmed=" + isVoiceConfirmed());
                dumpConfigs("modeChange");
                notifyListener("modeChange");
            });

            am.registerAudioRecordingCallback(
                    new AudioManager.AudioRecordingCallback() {
                        @Override
                        public void onRecordingConfigChanged(List<AudioRecordingConfiguration> cfgs) {
                            lastVoiceAudioActivityMs = System.currentTimeMillis();
                            logConfigs("callback", cfgs);
                            notifyListener("recordingConfig");
                        }
                    }, null);

            am.registerAudioPlaybackCallback(
                    new AudioManager.AudioPlaybackCallback() {
                        @Override
                        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> cfgs) {
                            boolean active = hasVoicePlayback(cfgs);
                            logPlaybackConfigs(cfgs, active);
                            if (active != assistantPlaybackActive) {
                                assistantPlaybackActive = active;
                                lastVoiceAudioActivityMs = System.currentTimeMillis();
                                L.i("ASSISTANT_PLAYBACK active=" + active);
                                notifyListener(active ? "assistantPlaybackStarted" : "assistantPlaybackEnded");
                            }
                        }
                    }, null);

            assistantPlaybackActive = hasVoicePlayback(am.getActivePlaybackConfigurations());
            L.i("AUDIO_STATE_MONITOR_OK assistantPlayback=" + assistantPlaybackActive);
            dumpConfigs("install");
            logPlaybackConfigs(am.getActivePlaybackConfigurations(), assistantPlaybackActive);
        } catch (Throwable t) {
            L.e("AUDIO_STATE_MONITOR_FAIL", t);
        }
    }

    /**
     * ChatGPT Voice may route spoken answers as ASSISTANT/VOICE_COMMUNICATION, but on some Samsung
     * builds the official app exposes them as ordinary MEDIA with CONTENT_TYPE_SPEECH. Count that
     * combination too. We deliberately do not count MEDIA+MUSIC, so Spotify/Android Auto music does
     * not look like the assistant speaking and keep a voice session alive forever.
     */
    private static boolean hasVoicePlayback(List<AudioPlaybackConfiguration> cfgs) {
        if (cfgs == null) return false;
        for (AudioPlaybackConfiguration c : cfgs) {
            try {
                AudioAttributes a = c.getAudioAttributes();
                if (a == null) continue;
                int usage = a.getUsage();
                int content = a.getContentType();
                if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                        || usage == AudioAttributes.USAGE_ASSISTANT
                        || ((usage == AudioAttributes.USAGE_MEDIA
                                || usage == AudioAttributes.USAGE_UNKNOWN)
                                && content == AudioAttributes.CONTENT_TYPE_SPEECH)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static boolean isAssistantPlaybackActive() {
        return assistantPlaybackActive;
    }

    /** Last mode/recording/assistant-playback transition seen while observing the voice session. */
    public static long lastVoiceAudioActivityMs() {
        return lastVoiceAudioActivityMs;
    }

    public static int mode() {
        try {
            return am != null ? am.getMode() : LAST_MODE.get();
        } catch (Throwable t) {
            return LAST_MODE.get();
        }
    }

    public static List<AudioRecordingConfiguration> configs() {
        try {
            return am != null ? am.getActiveRecordingConfigurations() : null;
        } catch (Throwable t) {
            L.e("GET_CONFIGS_FAIL", t);
            return null;
        }
    }

    /** True when a non-silenced communication-class capture exists. */
    public static boolean hasRealCommunicationCapture() {
        List<AudioRecordingConfiguration> cfgs = configs();
        if (cfgs == null) return false;
        for (AudioRecordingConfiguration c : cfgs) {
            int src = c.getClientAudioSource();
            boolean comm = src == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    || src == MediaRecorder.AudioSource.VOICE_CALL;
            if (comm && !c.isClientSilenced()) return true;
        }
        return false;
    }

    public static boolean isCommunicationMode() {
        int m = mode();
        return m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_IN_CALL;
    }

    public static boolean isVoiceConfirmed() {
        return isCommunicationMode() && hasRealCommunicationCapture();
    }

    /** True when our own VOICE_RECOGNITION capture is visible and not silenced. */
    public static boolean hasOwnLiveCapture() {
        List<AudioRecordingConfiguration> cfgs = configs();
        if (cfgs == null) return false;
        for (AudioRecordingConfiguration c : cfgs) {
            if (c.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_RECOGNITION
                    && !c.isClientSilenced()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOwnCaptureConfig() {
        List<AudioRecordingConfiguration> cfgs = configs();
        if (cfgs == null) return false;
        for (AudioRecordingConfiguration c : cfgs) {
            if (c.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_RECOGNITION) return true;
        }
        return false;
    }

    public static String ownCaptureState() {
        List<AudioRecordingConfiguration> cfgs = configs();
        if (cfgs == null) return "configs=null";
        for (AudioRecordingConfiguration c : cfgs) {
            if (c.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                return "silenced=" + c.isClientSilenced();
            }
        }
        return "ownConfigAbsent(n=" + cfgs.size() + ")";
    }

    public static void dumpConfigs(String why) {
        logConfigs(why, configs());
    }

    private static void logConfigs(String why, List<AudioRecordingConfiguration> cfgs) {
        if (cfgs == null) {
            L.i("CONFIGS why=" + why + " -> null");
            return;
        }
        StringBuilder sb = new StringBuilder("CONFIGS why=" + why + " n=" + cfgs.size());
        for (AudioRecordingConfiguration c : cfgs) {
            sb.append(" | src=").append(sourceName(c.getClientAudioSource()))
              .append(" silenced=").append(c.isClientSilenced())
              .append(" sess=").append(c.getClientAudioSessionId())
              .append(" fmtRate=").append(c.getFormat().getSampleRate());
            try {
                if (c.getAudioDevice() != null) sb.append(" devType=").append(c.getAudioDevice().getType());
            } catch (Throwable ignored) {
            }
        }
        L.i(sb.toString());
    }

    private static void logPlaybackConfigs(List<AudioPlaybackConfiguration> cfgs, boolean voice) {
        if (cfgs == null) {
            L.i("PLAYBACK_CONFIGS -> null voice=" + voice);
            return;
        }
        StringBuilder sb = new StringBuilder("PLAYBACK_CONFIGS n=" + cfgs.size()
                + " voice=" + voice);
        for (AudioPlaybackConfiguration c : cfgs) {
            try {
                AudioAttributes a = c.getAudioAttributes();
                sb.append(" | usage=").append(a == null ? -1 : a.getUsage())
                        .append(" content=").append(a == null ? -1 : a.getContentType());
            } catch (Throwable t) {
                sb.append(" | unreadable");
            }
        }
        L.i(sb.toString());
    }
}
