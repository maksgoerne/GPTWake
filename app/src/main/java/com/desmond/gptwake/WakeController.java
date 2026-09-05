package com.desmond.gptwake;

import android.content.Context;
import android.content.res.AssetManager;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Wake-word state machine. Every transition runs on one serial scheduled executor so that the
 * capture thread, the AudioManager callbacks and the service lifecycle can never race on state.
 */
public final class WakeController implements AudioProbe.WakeListener, AudioStateMonitor.Listener {

    public enum State {
        STARTING, KWS_MODEL_LOADING, KWS_LISTENING, MIC_HANDOFF, CHATGPT_LAUNCHING,
        VOICE_ACTIVE, EXTERNAL_COMMUNICATION, KWS_REACQUIRING, ERROR, STOPPED
    }

    private static final long HANDOFF_CAPTURE_GONE_TIMEOUT_MS = 500;
    private static final long HANDOFF_DRAIN_MS = 250;
    private static final long VOICE_CONFIRM_MS = 5000;
    private static final long DEEPLINK_CONFIRM_MS = 7000;
    private static final long VOICE_END_DEBOUNCE_MS = 1500;
    private static final long REACQUIRE_CONFIRM_MS = 3000;

    /**
     * No maximum conversation length. This timer is armed only after ChatGPT finishes a spoken
     * response, and is cancelled/deferred as soon as a new turn produces observable activity.
     */
    private static final long VOICE_IDLE_AFTER_RESPONSE_MS = 30_000L;

    private final Context ctx;
    private final KwsEngine kws = new KwsEngine();
    private final ScheduledExecutorService exec =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "wake-state"));

    private volatile State state = State.STOPPED;
    private long launchStartedAt;
    private boolean deeplinkTried;
    private long voiceEndCandidateSince;
    private long lastAcceptedHitMs;
    private long rawHits;
    private long acceptedHits;
    private long suppressedHits;

    // Response-idle state. Generation invalidates already scheduled checks without cancelling tasks.
    private long idleGeneration;
    private boolean assistantWasSpeaking;
    private long responseEndedAt;
    private long idleActivityBaselineMs;

    public WakeController(Context c) {
        this.ctx = c.getApplicationContext();
    }

    public State state() {
        return state;
    }

    public KwsEngine engine() {
        return kws;
    }

    private void set(State s) {
        if (state != s) {
            L.i("STATE " + state + " -> " + s);
            Measure.event("STATE", Measure.jstr("from", state.name()) + "," + Measure.jstr("to", s.name()));
            state = s;
        }
    }

    public void start() {
        exec.execute(() -> {
            set(State.STARTING);
            AudioStateMonitor.install(ctx);
            KwsEngine.customKeywordLine = WakeWordStore.keywordLine(ctx);
            L.i("WAKEWORD phrase=" + WakeWordStore.phrase(ctx));
            AudioStateMonitor.setListener(this);
            AudioProbe.bind(kws, this);
            AudioProbe.setFeeding(false);
            AudioProbe.start("FGS");
            set(State.KWS_MODEL_LOADING);
            loadModel();
        });
    }

    private void loadModel() {
        try {
            AssetManager am = ctx.getAssets();
            kws.load(am);
            kws.newStream();
            AudioProbe.setFeeding(true);
            set(State.KWS_LISTENING);
            L.i("KWS_READY");
        } catch (Throwable t) {
            L.e("KWS_MODEL_LOAD_FAIL", t);
            set(State.ERROR);
            AudioProbe.stop();
        }
    }

    @Override
    public void onFirstFrame() {
        L.i("MIC_RECORDING confirmed");
    }

    @Override
    public void onKeyword(String keyword) {
        exec.execute(() -> {
            rawHits++;
            long now = System.currentTimeMillis();
            if (now - lastAcceptedHitMs < Cfg.refractoryMs) {
                suppressedHits++;
                Measure.event("KWS_HIT", Measure.jstr("keyword", keyword)
                        + "," + Measure.jstr("class", "DUPLICATE_SUPPRESSED")
                        + ",\"sinceLastMs\":" + (now - lastAcceptedHitMs)
                        + ",\"raw\":" + rawHits + ",\"accepted\":" + acceptedHits
                        + ",\"suppressed\":" + suppressedHits
                        + "," + Measure.jstr("trial", TrialLog.currentTrialId()));
                L.i("KWS_HIT_SUPPRESSED keyword=" + keyword
                        + " sinceLastMs=" + (now - lastAcceptedHitMs)
                        + " raw=" + rawHits + " accepted=" + acceptedHits
                        + " suppressed=" + suppressedHits);
                resumeListening(false);
                return;
            }
            if (state != State.KWS_LISTENING) {
                L.i("KWS_HIT_IGNORED state=" + state + " raw=" + rawHits);
                return;
            }
            lastAcceptedHitMs = now;
            acceptedHits++;
            Measure.event("KWS_HIT", Measure.jstr("keyword", keyword)
                    + "," + Measure.jstr("class", TrialLog.active() ? "TP_USER" : "UNATTRIBUTED")
                    + ",\"raw\":" + rawHits + ",\"accepted\":" + acceptedHits
                    + ",\"suppressed\":" + suppressedHits
                    + ",\"inTrial\":" + TrialLog.active()
                    + "," + Measure.jstr("trial", TrialLog.currentTrialId()));
            L.i("KWS_HIT_ACCEPTED keyword=" + keyword
                    + " raw=" + rawHits + " accepted=" + acceptedHits
                    + " suppressed=" + suppressedHits);

            if (Cfg.evalMode) {
                L.i("KWS_EVAL_HIT keyword=" + keyword + " timestamp=" + now
                        + " (evaluation mode, ChatGPT not launched)");
                resumeListening(true);
                return;
            }
            if (AudioStateMonitor.hasRealCommunicationCapture()) {
                L.i("EXTERNAL_COMMUNICATION_DETECTED at wake, not launching");
                set(State.EXTERNAL_COMMUNICATION);
                AudioProbe.setFeeding(false);
                AudioProbe.stop();
                return;
            }
            set(State.MIC_HANDOFF);
            handoff();
        });
    }

    private void resumeListening(boolean withRefractory) {
        AudioProbe.setFeeding(false);
        long delay = withRefractory ? Cfg.refractoryMs : 200;
        exec.schedule(() -> {
            if (!AudioProbe.isRunning()) {
                L.i("KWS_RESUME_SKIPPED captureNotRunning");
                return;
            }
            kws.newStream();
            AudioProbe.setFeeding(true);
            set(State.KWS_LISTENING);
            L.i("KWS_RESUMED afterMs=" + delay);
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void restartStream() {
        exec.execute(() -> {
            if (!kws.isLoaded()) {
                L.i("STREAM_RESTART_SKIPPED modelNotLoaded");
                return;
            }
            AudioProbe.setFeeding(false);
            kws.newStream();
            AudioProbe.setFeeding(true);
            set(State.KWS_LISTENING);
            L.i("STREAM_RESTARTED");
        });
    }

    public String counters() {
        return "raw=" + rawHits + " accepted=" + acceptedHits + " suppressed=" + suppressedHits;
    }

    public long rawHits() { return rawHits; }
    public long acceptedHits() { return acceptedHits; }
    public long suppressedHits() { return suppressedHits; }

    public void resetCounters() {
        rawHits = 0;
        acceptedHits = 0;
        suppressedHits = 0;
        lastAcceptedHitMs = 0;
        L.i("KWS_COUNTERS_RESET");
    }

    private void handoff() {
        long t0 = System.currentTimeMillis();
        L.i("MIC_RELEASE_BEGIN");
        AudioProbe.setFeeding(false);
        kws.releaseStream();
        AudioProbe.stop();

        long deadline = System.currentTimeMillis() + HANDOFF_CAPTURE_GONE_TIMEOUT_MS;
        while (AudioStateMonitor.hasOwnCaptureConfig() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        L.i("MIC_RELEASE_DONE elapsedMs=" + (System.currentTimeMillis() - t0)
                + " ownConfigStillPresent=" + AudioStateMonitor.hasOwnCaptureConfig());
        exec.schedule(this::launchChatGpt, HANDOFF_DRAIN_MS, TimeUnit.MILLISECONDS);
    }

    private void launchChatGpt() {
        set(State.CHATGPT_LAUNCHING);
        launchStartedAt = System.currentTimeMillis();
        deeplinkTried = false;
        L.i("CHATGPT_LAUNCH_ATTEMPT route=AssistantActivity");
        GptLauncher.launchDirect(ctx);
        exec.schedule(this::checkVoiceConfirm, VOICE_CONFIRM_MS, TimeUnit.MILLISECONDS);
    }

    private void checkVoiceConfirm() {
        if (state != State.CHATGPT_LAUNCHING) return;
        if (AudioStateMonitor.isVoiceConfirmed()) {
            confirmVoice();
            return;
        }
        if (!deeplinkTried) {
            deeplinkTried = true;
            L.i("VOICE_CONFIRM_TIMEOUT after=" + (System.currentTimeMillis() - launchStartedAt)
                    + "ms, falling back once");
            L.i("CHATGPT_LAUNCH_ATTEMPT route=deeplink");
            GptLauncher.launchDeeplink(ctx);
            exec.schedule(this::checkVoiceConfirm, DEEPLINK_CONFIRM_MS, TimeUnit.MILLISECONDS);
            return;
        }
        L.i("VOICE_CONFIRM_TIMEOUT final, giving up and restoring KWS");
        reacquire();
    }

    private void confirmVoice() {
        L.i("VOICE_CONFIRMED latencyMs=" + (System.currentTimeMillis() - launchStartedAt));
        set(State.VOICE_ACTIVE);
        voiceEndCandidateSince = 0;
        idleGeneration++;
        responseEndedAt = 0;
        idleActivityBaselineMs = 0;
        assistantWasSpeaking = AudioStateMonitor.isAssistantPlaybackActive();
        L.i("VOICE_IDLE_MONITOR armed=false assistantSpeaking=" + assistantWasSpeaking
                + " maxDuration=none idleAfterResponseMs=" + VOICE_IDLE_AFTER_RESPONSE_MS);
    }

    /**
     * Ends the actual ChatGPT voice session via the action exposed by ChatGPT's notification.
     * Returns false when Notification Listener access has not been granted or no action is present.
     */
    public boolean endVoice() {
        if (state != State.VOICE_ACTIVE && state != State.CHATGPT_LAUNCHING) return false;
        return endVoiceNow("user");
    }

    private boolean endVoiceNow(String reason) {
        cancelIdle("hangup-" + reason);
        boolean sent = VoiceNotificationListener.tryHangUp();
        L.i("VOICE_HANGUP_REQUEST reason=" + reason + " sent=" + sent);
        if (sent) {
            exec.schedule(() -> {
                if (state == State.VOICE_ACTIVE) evaluateVoiceEnd();
            }, VOICE_END_DEBOUNCE_MS + 250, TimeUnit.MILLISECONDS);
        }
        return sent;
    }

    @Override
    public void onAudioStateChanged(String why) {
        exec.execute(() -> {
            switch (state) {
                case CHATGPT_LAUNCHING:
                    if (AudioStateMonitor.isVoiceConfirmed()) confirmVoice();
                    break;
                case VOICE_ACTIVE:
                    evaluateVoiceEnd();
                    if (state == State.VOICE_ACTIVE) evaluateResponseIdle(why);
                    break;
                case EXTERNAL_COMMUNICATION:
                    if (!AudioStateMonitor.isCommunicationMode()
                            && !AudioStateMonitor.hasRealCommunicationCapture()) {
                        L.i("EXTERNAL_COMMUNICATION ended");
                        reacquire();
                    }
                    break;
                default:
                    break;
            }
        });
    }

    /** Watches the assistant-speaking -> listening transition. Only that transition arms timeout. */
    private void evaluateResponseIdle(String why) {
        boolean speaking = AudioStateMonitor.isAssistantPlaybackActive();
        if (speaking) {
            if (!assistantWasSpeaking) {
                L.i("VOICE_RESPONSE_STARTED why=" + why);
            }
            assistantWasSpeaking = true;
            cancelIdle("assistant-speaking");
            return;
        }

        if (assistantWasSpeaking) {
            assistantWasSpeaking = false;
            L.i("VOICE_RESPONSE_FINISHED why=" + why);
            armIdleAfterResponse();
        }
    }

    private void armIdleAfterResponse() {
        responseEndedAt = System.currentTimeMillis();
        idleActivityBaselineMs = latestConversationActivityMs();
        final long generation = ++idleGeneration;
        final long endedAt = responseEndedAt;
        final long baseline = idleActivityBaselineMs;
        L.i("VOICE_IDLE_ARM afterMs=" + VOICE_IDLE_AFTER_RESPONSE_MS
                + " responseEndedAt=" + endedAt + " baseline=" + baseline);

        exec.schedule(() -> checkResponseIdle(generation, endedAt, baseline),
                VOICE_IDLE_AFTER_RESPONSE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * The official ChatGPT app owns the microphone during Voice, so GPTWake cannot directly inspect
     * the user's speech level without stealing/silencing that mic. We therefore use conservative
     * Android-side evidence. If any voice recording/mode/notification activity appeared after the
     * answer ended, assume a new user turn may be in progress (including a long search/thinking
     * phase) and DO NOT hang up; wait until the next spoken ChatGPT response finishes instead.
     */
    private void checkResponseIdle(long generation, long endedAt, long baseline) {
        if (state != State.VOICE_ACTIVE || generation != idleGeneration) return;

        if (AudioStateMonitor.isAssistantPlaybackActive()) {
            L.i("VOICE_IDLE_CANCEL responseStartedBeforeTimeout");
            cancelIdle("assistant-speaking-at-timeout");
            return;
        }

        long latest = latestConversationActivityMs();
        if (latest > baseline + 250) {
            L.i("VOICE_IDLE_DEFER newTurnOrActivity=true latest=" + latest
                    + " baseline=" + baseline + " responseEndedAt=" + endedAt);
            // A turn may be thinking/searching. Do not start another blind timer; the next detected
            // assistant response will re-arm the 30-second window when it actually finishes.
            cancelIdle("new-turn-or-activity");
            return;
        }

        L.i("VOICE_IDLE_TIMEOUT noActivityAfterResponseMs="
                + (System.currentTimeMillis() - endedAt));
        if (!endVoiceNow("idle-after-response")) {
            L.i("VOICE_IDLE_TIMEOUT hang-up unavailable; keeping session open");
        }
    }

    private long latestConversationActivityMs() {
        return Math.max(AudioStateMonitor.lastVoiceAudioActivityMs(),
                VoiceNotificationListener.lastVoiceActivityMs());
    }

    private void cancelIdle(String reason) {
        if (responseEndedAt != 0) {
            L.i("VOICE_IDLE_CANCEL reason=" + reason);
        }
        idleGeneration++;
        responseEndedAt = 0;
        idleActivityBaselineMs = 0;
    }

    private void evaluateVoiceEnd() {
        boolean ended = !AudioStateMonitor.isCommunicationMode()
                && !AudioStateMonitor.hasRealCommunicationCapture();
        long now = System.currentTimeMillis();
        if (!ended) {
            voiceEndCandidateSince = 0;
            return;
        }
        if (voiceEndCandidateSince == 0) {
            voiceEndCandidateSince = now;
            exec.schedule(() -> {
                if (state == State.VOICE_ACTIVE) evaluateVoiceEnd();
            }, VOICE_END_DEBOUNCE_MS + 100, TimeUnit.MILLISECONDS);
            return;
        }
        if (now - voiceEndCandidateSince >= VOICE_END_DEBOUNCE_MS) {
            L.i("VOICE_ENDED");
            cancelIdle("voice-ended");
            reacquire();
        }
    }

    private void reacquire() {
        set(State.KWS_REACQUIRING);
        long t0 = System.currentTimeMillis();
        L.i("MIC_REACQUIRE_BEGIN");
        AudioProbe.setFeeding(false);
        AudioProbe.start("REACQUIRE");
        pollReacquire(t0, 0);
    }

    private void pollReacquire(long t0, int attempt) {
        if (state != State.KWS_REACQUIRING) return;
        boolean running = AudioProbe.isRunning();
        boolean live = AudioStateMonitor.hasOwnLiveCapture();
        if (running && live) {
            kws.newStream();
            AudioProbe.setFeeding(true);
            set(State.KWS_LISTENING);
            L.i("MIC_REACQUIRE_OK latencyMs=" + (System.currentTimeMillis() - t0)
                    + " attempts=" + attempt);
            L.i("KWS_READY");
            return;
        }
        if (System.currentTimeMillis() - t0 >= REACQUIRE_CONFIRM_MS) {
            L.i("MIC_REACQUIRE_FAIL running=" + running + " liveCapture=" + live
                    + " own=" + AudioStateMonitor.ownCaptureState());
            set(State.ERROR);
            return;
        }
        exec.schedule(() -> pollReacquire(t0, attempt + 1), 100, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        exec.execute(() -> {
            cancelIdle("controller-stop");
            AudioProbe.setFeeding(false);
            AudioProbe.stop();
            kws.release();
            set(State.STOPPED);
        });
    }
}
