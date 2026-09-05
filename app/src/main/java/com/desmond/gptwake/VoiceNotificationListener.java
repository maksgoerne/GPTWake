package com.desmond.gptwake;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

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

    private boolean isChatGptVoice(StatusBarNotification sbn) {
        if (!"com.openai.chatgpt".equals(sbn.getPackageName())) return false;
        Notification n = sbn.getNotification();
        return "voice_mode_ongoing".equals(n.getChannelId())
                || Notification.CATEGORY_CALL.equals(n.category);
    }

    @Override
    public void onListenerConnected() {
        L.i("NLS_CONNECTED");
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

    private void capture(StatusBarNotification sbn) {
        lastVoiceActivityMs = System.currentTimeMillis();
        Notification n = sbn.getNotification();
        PendingIntent pi = n.extras.getParcelable(
                Notification.EXTRA_HANG_UP_INTENT, PendingIntent.class);
        if (pi == null && n.actions != null && n.actions.length > 0) {
            pi = n.actions[0].actionIntent;
        }
        hangUp = pi;
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
        if (!isChatGptVoice(sbn)) return;
        capture(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!isChatGptVoice(sbn)) return;
        lastVoiceActivityMs = System.currentTimeMillis();
        hangUp = null;
        L.i("NLS_REMOVE id=" + sbn.getId());
    }
}
