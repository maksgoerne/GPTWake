package com.desmond.gptwake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ResultReceiver;
import androidx.core.app.NotificationCompat;
import java.util.concurrent.atomic.AtomicBoolean;

public class WakeService extends Service {

    private static final String CH = "listening";
    private static final int ID = 7311;
    static final String ACTION_STOP = "com.desmond.gptwake.STOP";

    public static final String EXTRA_ACK = "ack";
    public static final String EXTRA_CYCLE = "cycle";
    public static final String EXTRA_STOP_AFTER = "stop_after";
    public static final String EXTRA_RESUME_AFTER = "resume_after";

    private static final AtomicBoolean FOREGROUND = new AtomicBoolean(false);
    private HandlerThread ht;
    private Handler bg;
    private static volatile WakeController controller;

    public static WakeController controller() { return controller; }
    public static boolean isForegroundNow() { return FOREGROUND.get(); }

    @Override
    public void onCreate() {
        super.onCreate();
        ht = new HandlerThread("mic-probe-sched");
        ht.start();
        bg = new Handler(ht.getLooper());
        AudioStateMonitor.install(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver ack = intent == null ? null
                : intent.getParcelableExtra(EXTRA_ACK, ResultReceiver.class);

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            WakeController c = controller;
            if (c != null && (c.state() == WakeController.State.VOICE_ACTIVE
                    || c.state() == WakeController.State.CHATGPT_LAUNCHING)) {
                boolean sent = c.endVoice();
                L.i("VOICE_STOP_FROM_NOTIFICATION sent=" + sent);
                // Do not kill GPTWake here: after ChatGPT ends, the controller re-acquires the mic.
                return START_STICKY;
            }
            L.i("MIC_FGS_STOP_FROM_NOTIFICATION");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!FOREGROUND.get()) {
            try {
                ensureChannel();
                startForeground(ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                FOREGROUND.set(true);
                L.i("MIC_FGS_START_OK");
            } catch (Throwable t) {
                L.e("MIC_FGS_START_FAIL", t);
                if (ack != null) ack.send(1, null);
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            refreshNotification();
            L.i("MIC_FGS_ALREADY_FOREGROUND");
        }

        if (intent != null && intent.getBooleanExtra(EXTRA_CYCLE, false)) {
            scheduleCycle(intent.getIntExtra(EXTRA_STOP_AFTER, 45),
                    intent.getIntExtra(EXTRA_RESUME_AFTER, 75));
        } else if (controller == null) {
            controller = new WakeController(this);
            controller.start();
        } else {
            L.i("WAKE_CONTROLLER_ALREADY state=" + controller.state());
        }

        if (ack != null) {
            bg.postDelayed(() -> {
                boolean ok = AudioProbe.isRunning();
                L.i("FGS_ACK running=" + ok + " last=" + AudioProbe.lastResult());
                ack.send(ok ? 0 : 2, null);
            }, 1200);
        }
        return START_STICKY;
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(
                CH, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notification_channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, WakeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        WakeController c = controller;
        boolean voice = c != null && (c.state() == WakeController.State.VOICE_ACTIVE
                || c.state() == WakeController.State.CHATGPT_LAUNCHING);

        return new NotificationCompat.Builder(this, CH)
                .setContentTitle(voice ? "ChatGPT voice is active"
                        : getString(R.string.notification_listening))
                .setContentText(voice ? "Tap Stop to end voice and return to Jarvis"
                        : WakeWordStore.phrase(this))
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(open)
                .addAction(0, voice ? "End voice" : getString(R.string.action_stop_listening), stop)
                .setOngoing(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    static void refresh(android.content.Context context) {
        if (!FOREGROUND.get()) return;
        context.startService(new Intent(context, WakeService.class));
    }

    private void refreshNotification() {
        if (!FOREGROUND.get()) return;
        try {
            getSystemService(NotificationManager.class).notify(ID, buildNotification());
        } catch (Throwable t) {
            L.e("NOTIFICATION_REFRESH_FAIL", t);
        }
    }

    private void scheduleCycle(int stopAfterSec, int resumeAfterSec) {
        L.i("CYCLE_SCHEDULED stopAfter=" + stopAfterSec + "s resumeAfter=" + resumeAfterSec + "s");
        AudioProbe.start("CYCLE_PHASE1");

        bg.postDelayed(() -> {
            L.i("CYCLE_STOP_BEGIN fgs=" + FOREGROUND.get());
            AudioProbe.stop();
            AudioStateMonitor.dumpConfigs("afterCycleStop");
            L.i("CYCLE_STOP_DONE micReleased fgsStillForeground=" + FOREGROUND.get()
                    + " mode=" + AudioStateMonitor.modeName(AudioStateMonitor.mode()));
        }, stopAfterSec * 1000L);

        bg.postDelayed(() -> {
            L.i("REACQUIRE_BEGIN noShim fgsStillForeground=" + FOREGROUND.get());
            AudioStateMonitor.dumpConfigs("beforeReacquire");
            AudioProbe.start("REACQUIRE");
            bg.postDelayed(() -> {
                L.i("REACQUIRE_RESULT running=" + AudioProbe.isRunning()
                        + " last=" + AudioProbe.lastResult()
                        + " own=" + AudioStateMonitor.ownCaptureState());
                L.i(AudioProbe.isRunning() ? "REACQUIRE_OK" : "REACQUIRE_FAIL");
            }, 10_000L);
        }, resumeAfterSec * 1000L);
    }

    @Override
    public void onDestroy() {
        L.i("MIC_FGS_DESTROY");
        FOREGROUND.set(false);
        WakeController c = controller;
        controller = null;
        if (c != null) c.stop();
        AudioProbe.stop();
        if (ht != null) ht.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
