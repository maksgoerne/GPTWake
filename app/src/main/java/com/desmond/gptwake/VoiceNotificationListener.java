package com.desmond.gptwake;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Locale;

public class VoiceNotificationListener extends NotificationListenerService {

    private static volatile PendingIntent hangUp;
    private static volatile long lastVoiceActivityMs;

    public static PendingIntent hangUpIntent() {
        return hangUp;
    }

    /** Timestamp of the latest ChatGPT voice-notification update we observed. */
    public static long lastVoiceActivityMs() {
        return lastVoiceActivityMs;
    }

    /** True when ChatGPT exposed a usable hang-up action through its ongoing voice notification. */
    public static boolean canHangUp() {
        return hangUp != null;
    }

    /**
     * Triggers ChatGPT's own hang-up PendingIntent. This is the same action Android surfaces in the
     * ongoing voice notification, so it ends the actual voice session instead of merely stopping
     * GPTWake.
     */
    public static boolean tryHangUp() {
        PendingIntent pi = hangUp;
        if (pi == null) {
            L.i("NLS_HANGUP_UNAVAILABLE");
            return false;
        }
        try {
            pi.send();
            L.i("NLS_HANGUP_SENT");
            return true;
        } catch (PendingIntent.CanceledException e) {
            hangUp = null;
            L.e("NLS_HANGUP_CANCELED", e);
            return false;
        } catch (Throwable t) {
            L.e("NLS_HANGUP_FAIL", t);
            return false;
        }
    }

    private boolean isChatGpt(StatusBarNotification sbn) {
        return "com.openai.chatgpt".equals(sbn.getPackageName());
    }

    private boolean isChatGptVoice(StatusBarNotification sbn) {
        if (!isChatGpt(sbn)) return false;
        Notification n = sbn.getNotification();

        try {
            if (n.extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent.class) != null) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        String channel = n.getChannelId();
        if (channel != null && channel.toLowerCase(Locale.ROOT).contains("voice")) return true;
        if (Notification.CATEGORY_CALL.equals(n.category)) return true;

        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (looksLikeHangUp(a.title)) return true;
            }
        }
        return false;
    }

    private static boolean looksLikeHangUp(CharSequence title) {
        if (title == null) return false;
        String s = title.toString().toLowerCase(Locale.ROOT);
        return s.contains("hang up")
                || s.contains("hangup")
                || s.contains("end")
                || s.contains("stop")
                || s.contains("disconnect")
                || s.contains("leave")
                || s.contains("close")
                || s.contains("zakoń")
                || s.contains("rozłącz")
                || s.contains("przerwij")
                || s.contains("zamknij");
    }

    @Override
    public void onListenerConnected() {
        L.i("NLS_CONNECTED");
        restoreFromActive();
    }

    private void restoreFromActive() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (isChatGptVoice(sbn)) {
                        L.i("NLS_RESTORE_ACTIVE key=" + sbn.getKey());
                        capture(sbn);
                    }
                }
            }
        } catch (Throwable t) {
            L.e("NLS_CONNECTED_SCAN_FAIL", t);
        }
    }

    private PendingIntent findHangUp(Notification n) {
        try {
            PendingIntent pi = n.extras.getParcelable(
                    Notification.EXTRA_HANG_UP_INTENT, PendingIntent.class);
            if (pi != null) return pi;
        } catch (Throwable ignored) {
        }

        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (looksLikeHangUp(a.title) && a.actionIntent != null) return a.actionIntent;
            }

            // Some ChatGPT builds expose a single unlabeled/translated action on the dedicated
            // voice channel. A single action is safe to use there; with several unknown actions we
            // refuse to guess (the first one could be mute rather than hang-up).
            String channel = n.getChannelId();
            boolean voiceChannel = channel != null
                    && channel.toLowerCase(Locale.ROOT).contains("voice");
            if ((voiceChannel || Notification.CATEGORY_CALL.equals(n.category))
                    && n.actions.length == 1) {
                return n.actions[0].actionIntent;
            }
        }
        return null;
    }

    private void capture(StatusBarNotification sbn) {
        lastVoiceActivityMs = System.currentTimeMillis();
        Notification n = sbn.getNotification();
        PendingIntent pi = findHangUp(n);
        if (pi != null) hangUp = pi;

        StringBuilder titles = new StringBuilder();
        if (n.actions != null) {
            for (Notification.Action a : n.actions) titles.append('[').append(a.title).append(']');
        }
        L.i("NLS_POST channel=" + n.getChannelId()
                + " category=" + n.category
                + " id=" + sbn.getId()
                + " actions=" + titles
                + " hangup=" + (pi != null));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!isChatGpt(sbn)) return;

        Notification n = sbn.getNotification();
        StringBuilder titles = new StringBuilder();
        if (n.actions != null) {
            for (Notification.Action a : n.actions) titles.append('[').append(a.title).append(']');
        }
        L.i("NLS_CHATGPT_SEEN channel=" + n.getChannelId()
                + " category=" + n.category + " actions=" + titles);

        if (!isChatGptVoice(sbn)) return;
        capture(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!isChatGptVoice(sbn)) return;
        lastVoiceActivityMs = System.currentTimeMillis();
        hangUp = null;
        L.i("NLS_REMOVE id=" + sbn.getId());
        restoreFromActive();
    }
}
