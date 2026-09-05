package com.desmond.gptwake;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/** Persisted wake phrase, kept in device-protected storage so it survives Direct Boot. */
public final class WakeWordStore {

    public static final String DEFAULT_PHRASE = "芝麻开门";
    /** Matches assets/kws/keywords.txt, used when nothing custom is stored. */
    public static final String DEFAULT_LINE = "zh ī m á k āi m én @芝麻开门";

    /**
     * The bundled acoustic model is English/Chinese and has no Polish rolled-r phone. Arm several
     * nearby model pronunciations at once so the user does not have to fake one exact British
     * accent. sherpa-onnx supports several runtime keywords separated by '/'.
     *
     *  1) dżar-wis with the model's English R (current best match)
     *  2) clearer Polish final "i"
     *  3) no model-R, which often matches a short Polish/trilled r better acoustically
     *  4) same no-R variant with a clearer final "i"
     */
    private static final String JARVIS_MULTI_LINE =
            "JH AA1 R V IH0 S @JARVIS_1/"
            + "JH AA1 R V IY0 S @JARVIS_2/"
            + "JH AA1 V IH0 S @JARVIS_3/"
            + "JH AA1 V IY0 S @JARVIS_4";

    private static SharedPreferences sp(Context c) {
        return c.createDeviceProtectedStorageContext()
                .getSharedPreferences("wakeword", Context.MODE_PRIVATE);
    }

    public static String phrase(Context c) {
        return sp(c).getString("phrase", DEFAULT_PHRASE);
    }

    public static String keywordLine(Context c) {
        String phrase = phrase(c);
        if (isJarvisAlias(phrase)) return JARVIS_MULTI_LINE;
        return sp(c).getString("line", DEFAULT_LINE);
    }

    private static boolean isJarvisAlias(String phrase) {
        if (phrase == null) return false;
        String p = phrase.trim().toUpperCase(new Locale("pl", "PL"));
        return p.equals("JARVIS") || p.equals("JARWIS")
                || p.equals("DŻARWIS") || p.equals("DŻARVIS");
    }

    public static void save(Context c, String phrase, String line) {
        sp(c).edit().putString("phrase", phrase).putString("line", line).commit();
        L.i("WAKEWORD_SAVED phrase=" + phrase + " line=" + line);
    }

    public static void reset(Context c) {
        sp(c).edit().remove("phrase").remove("line").commit();
        L.i("WAKEWORD_RESET");
    }

    private WakeWordStore() {
    }
}
