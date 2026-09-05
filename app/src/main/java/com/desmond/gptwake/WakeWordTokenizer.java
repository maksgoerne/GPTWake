package com.desmond.gptwake;

import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts a human-typed wake phrase into the phone/pinyin token sequence the KWS model expects,
 * entirely on device. Chinese characters use the bundled pinyin table. Latin text first gets a
 * Polish-friendly pronunciation mapped onto the model's nearest available phones; if that mapper
 * cannot handle a word, the bundled CMU English dictionary is used as fallback.
 */
public final class WakeWordTokenizer {

    public enum Err {
        NONE, DICT_NOT_LOADED, EMPTY, UNSUPPORTED_HAN, UNKNOWN_ENGLISH,
        UNSUPPORTED_CHAR, UNPARSEABLE, UNSUPPORTED_PHONE, TOO_SHORT,
    }

    public static final class Result {
        public final boolean ok;
        public final String tokens;
        public final String readable;
        public final String keywordLine;
        public final Err err;
        public final String errArg;
        public final int errCount;

        Result(boolean ok, String tokens, String readable, String keywordLine,
               Err err, String errArg, int errCount) {
            this.ok = ok;
            this.tokens = tokens;
            this.readable = readable;
            this.keywordLine = keywordLine;
            this.err = err;
            this.errArg = errArg;
            this.errCount = errCount;
        }

        public boolean hasMessage() { return err != Err.NONE; }

        static Result fail(Err err, String arg) {
            return new Result(false, "", "", "", err, arg, 0);
        }
    }

    private static final class Pron {
        final String phones;
        final int syllables;
        Pron(String phones, int syllables) {
            this.phones = phones;
            this.syllables = syllables;
        }
    }

    private static final Map<String, Pron> POLISH_OVERRIDES = new HashMap<>();
    static {
        // The important one: natural Polish "dżarwis", not CMU's "dżar-vəs".
        POLISH_OVERRIDES.put("JARVIS", new Pron("JH AA1 R V IH0 S", 2));
        POLISH_OVERRIDES.put("JARWIS", new Pron("JH AA1 R V IH0 S", 2));
        POLISH_OVERRIDES.put("DŻARWIS", new Pron("JH AA1 R V IH0 S", 2));
        POLISH_OVERRIDES.put("DŻARVIS", new Pron("JH AA1 R V IH0 S", 2));
        POLISH_OVERRIDES.put("HEJ", new Pron("HH EY1", 1));
        POLISH_OVERRIDES.put("HEY", new Pron("HH EY1", 1));
        POLISH_OVERRIDES.put("OK", new Pron("OW1 K EY1", 2));
    }

    private final Map<Character, String[]> han = new HashMap<>(32768);
    private final Map<String, String> english = new HashMap<>(160000);
    private final Set<String> valid = new HashSet<>(512);
    private volatile boolean loaded;

    public boolean isLoaded() { return loaded; }

    public synchronized void load(AssetManager am) throws Exception {
        if (loaded) return;
        long t0 = System.currentTimeMillis();

        try (BufferedReader r = reader(am, "kws/tokens.txt")) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.lastIndexOf(' ');
                if (sp > 0) valid.add(line.substring(0, sp));
            }
        }

        try (BufferedReader r = reader(am, "kws/pinyin_tokens.txt")) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length >= 3 && f[0].length() == 1) {
                    han.put(f[0].charAt(0), new String[]{f[1], f[2]});
                }
            }
        }

        try (BufferedReader r = reader(am, "kws/en.phone")) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp > 0) english.put(line.substring(0, sp).toUpperCase(Locale.US),
                        line.substring(sp + 1).trim());
            }
        }

        loaded = true;
        L.i("TOKENIZER_READY han=" + han.size() + " english=" + english.size()
                + " modelTokens=" + valid.size() + " loadMs=" + (System.currentTimeMillis() - t0));
    }

    private static BufferedReader reader(AssetManager am, String path) throws Exception {
        return new BufferedReader(new InputStreamReader(am.open(path), StandardCharsets.UTF_8), 1 << 16);
    }

    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    public Result convert(String phrase) {
        if (!loaded) return Result.fail(Err.DICT_NOT_LOADED, null);
        if (phrase == null) return Result.fail(Err.EMPTY, null);

        String p = phrase.trim().replaceAll("\\s+", " ");
        if (p.isEmpty()) return Result.fail(Err.EMPTY, null);

        List<String> tokens = new ArrayList<>();
        List<String> readable = new ArrayList<>();
        int syllables = 0;

        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == ' ') { i++; continue; }

            if (isHan(c)) {
                String[] e = han.get(c);
                if (e == null) return Result.fail(Err.UNSUPPORTED_HAN, String.valueOf(c));
                for (String t : e[0].split(" ")) tokens.add(t);
                readable.add(e[1]);
                syllables++;
                i++;
                continue;
            }

            if (Character.isLetter(c) || c == '\'') {
                int j = i;
                while (j < p.length()
                        && (Character.isLetter(p.charAt(j)) || p.charAt(j) == '\'')) j++;
                String raw = p.substring(i, j);
                String word = raw.toUpperCase(new Locale("pl", "PL"));

                Pron pl = polishPhones(word);
                String phones;
                if (pl != null) {
                    phones = pl.phones;
                    syllables += pl.syllables;
                    readable.add(phones + " [PL]");
                } else {
                    phones = english.get(word.toUpperCase(Locale.US));
                    if (phones == null) return Result.fail(Err.UNKNOWN_ENGLISH, word);
                    syllables += Math.max(1, word.length() / 3);
                    readable.add(phones);
                }
                for (String t : phones.split("\\s+")) tokens.add(t);
                i = j;
                continue;
            }
            return Result.fail(Err.UNSUPPORTED_CHAR, String.valueOf(c));
        }

        if (tokens.isEmpty()) return Result.fail(Err.UNPARSEABLE, null);
        for (String t : tokens) {
            if (!valid.contains(t)) return Result.fail(Err.UNSUPPORTED_PHONE, t);
        }

        String tok = String.join(" ", tokens);
        String display = String.join(" ", readable);
        String line = tok + " @" + p.replace(' ', '_');

        if (syllables < 3) {
            return new Result(true, tok, display, line, Err.TOO_SHORT, null, syllables);
        }
        return new Result(true, tok, display, line, Err.NONE, null, syllables);
    }

    /**
     * Approximate Polish grapheme-to-phone conversion using only phones already supported by the
     * bundled model. It is intentionally pragmatic rather than linguistically exact: the goal is
     * to recognise how a Polish user naturally says a wake phrase.
     */
    private static Pron polishPhones(String word) {
        Pron override = POLISH_OVERRIDES.get(word);
        if (override != null) return override;

        // If a word only contains plain ASCII letters and is present in CMU, keep English by
        // default unless it contains a strongly Polish spelling pattern. This preserves existing
        // English wake words while Polish words/diacritics use the Polish mapper.
        boolean polishLooking = false;
        for (int k = 0; k < word.length(); k++) {
            if ("ĄĆĘŁŃÓŚŹŻ".indexOf(word.charAt(k)) >= 0) polishLooking = true;
        }
        String[] markers = {"CZ", "SZ", "RZ", "DŻ", "DŹ", "DZ", "CH"};
        for (String m : markers) if (word.contains(m)) polishLooking = true;
        if (!polishLooking && isAsciiLetters(word)) return null;

        StringBuilder out = new StringBuilder();
        int syllables = 0;
        int i = 0;
        while (i < word.length()) {
            if (starts(word, i, "DZI")) { add(out, "JH IY1"); syllables++; i += 3; continue; }
            if (starts(word, i, "DŹ") || starts(word, i, "DŻ")) { add(out, "JH"); i += 2; continue; }
            if (starts(word, i, "CZ")) { add(out, "CH"); i += 2; continue; }
            if (starts(word, i, "SZ")) { add(out, "SH"); i += 2; continue; }
            if (starts(word, i, "RZ")) { add(out, "ZH"); i += 2; continue; }
            if (starts(word, i, "CH")) { add(out, "HH"); i += 2; continue; }
            if (starts(word, i, "DZ")) { add(out, "D Z"); i += 2; continue; }
            if (starts(word, i, "EJ")) { add(out, "EY1"); syllables++; i += 2; continue; }
            if (starts(word, i, "AJ")) { add(out, "AY1"); syllables++; i += 2; continue; }
            if (starts(word, i, "OJ")) { add(out, "OY1"); syllables++; i += 2; continue; }

            char c = word.charAt(i++);
            switch (c) {
                case '\'': break;
                case 'A': add(out, "AA1"); syllables++; break;
                case 'Ą': add(out, "AO1 N"); syllables++; break;
                case 'B': add(out, "B"); break;
                case 'C': add(out, "T S"); break;
                case 'Ć': add(out, "CH"); break;
                case 'D': add(out, "D"); break;
                case 'E': add(out, "EH1"); syllables++; break;
                case 'Ę': add(out, "EH1 N"); syllables++; break;
                case 'F': add(out, "F"); break;
                case 'G': add(out, "G"); break;
                case 'H': add(out, "HH"); break;
                case 'I': add(out, "IY1"); syllables++; break;
                case 'J': add(out, "Y"); break;
                case 'K': add(out, "K"); break;
                case 'L': add(out, "L"); break;
                case 'Ł': add(out, "W"); break;
                case 'M': add(out, "M"); break;
                case 'N': add(out, "N"); break;
                case 'Ń': add(out, "N Y"); break;
                case 'O': add(out, "AO1"); syllables++; break;
                case 'Ó': add(out, "UW1"); syllables++; break;
                case 'P': add(out, "P"); break;
                case 'Q': add(out, "K"); break;
                case 'R': add(out, "R"); break;
                case 'S': add(out, "S"); break;
                case 'Ś': add(out, "SH"); break;
                case 'T': add(out, "T"); break;
                case 'U': add(out, "UW1"); syllables++; break;
                case 'V': add(out, "V"); break;
                case 'W': add(out, "V"); break;
                case 'X': add(out, "K S"); break;
                case 'Y': add(out, "IH1"); syllables++; break;
                case 'Z': add(out, "Z"); break;
                case 'Ź':
                case 'Ż': add(out, "ZH"); break;
                default: return null;
            }
        }
        if (out.length() == 0) return null;
        return new Pron(out.toString(), Math.max(1, syllables));
    }

    private static boolean isAsciiLetters(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') continue;
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    private static boolean starts(String s, int at, String part) {
        return at + part.length() <= s.length() && s.regionMatches(at, part, 0, part.length());
    }

    private static void add(StringBuilder out, String phones) {
        if (out.length() > 0) out.append(' ');
        out.append(phones);
    }

    public static int estimateSyllables(String phrase) {
        int n = 0;
        for (char c : phrase.toCharArray()) if (isHan(c)) n++;
        return n;
    }
}
