package dev.discordbridge;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns emoji into readable text for Minecraft chat (the default font can't draw them, they show as boxes).
 *
 * <ul>
 *   <li>Unicode emoji   -> {@code :face_with_tears_of_joy:} (or {@code XD} in emoticon style)</li>
 *   <li>Custom emoji    -> {@code <:name:123456789012345678>} becomes {@code :name:}</li>
 *   <li>Flags           -> {@code :flag_us:}</li>
 *   <li>Keycaps         -> {@code [1]}</li>
 * </ul>
 *
 * Config (all optional):
 * <pre>
 * emoji-convert=true
 * emoji-style=name            # name | emoticon
 * emoji.override.1F602=LOL    # per-emoji replacement, key = code point in hex
 * </pre>
 */
public final class EmojiMapper {

    private static final Pattern CUSTOM = Pattern.compile("<a?:(\\w{2,32}):\\d{15,22}>");
    private static final Pattern KEYCAP = Pattern.compile("([0-9#*])\uFE0F?\u20E3");

    /** Short, friendlier names than the long Unicode names. */
    private static final Map<Integer, String> NAMES = new HashMap<>();
    /** Used when emoji-style=emoticon. */
    private static final Map<Integer, String> EMOTICONS = new HashMap<>();

    static {
        name(0x2764, "heart");
        name(0x1F44D, "thumbsup");
        name(0x1F44E, "thumbsdown");
        name(0x1F602, "joy");
        name(0x1F923, "rofl");
        name(0x1F62D, "sob");
        name(0x1F525, "fire");
        name(0x1F389, "tada");
        name(0x1F4AF, "100");
        name(0x2705, "white_check_mark");
        name(0x274C, "x");
        name(0x1F60D, "heart_eyes");
        name(0x1F914, "thinking");
        name(0x1F64F, "pray");
        name(0x1F44F, "clap");
        name(0x1F480, "skull");
        name(0x1F440, "eyes");
        name(0x1F60A, "blush");
        name(0x1F604, "smile");
        name(0x1F600, "grinning");
        name(0x1F642, "slight_smile");
        name(0x1F609, "wink");
        name(0x1F622, "cry");
        name(0x1F621, "rage");
        name(0x1F44B, "wave");
        name(0x1F4A9, "poop");
        name(0x2728, "sparkles");
        name(0x1F31F, "star2");
        name(0x2B50, "star");

        for (int cp : new int[]{0x1F600, 0x1F603, 0x1F604, 0x1F601}) emo(cp, ":D");
        for (int cp : new int[]{0x1F606, 0x1F602, 0x1F923}) emo(cp, "XD");
        emo(0x1F60A, ":)");
        emo(0x1F642, ":)");
        emo(0x263A, ":)");
        emo(0x1F643, "(:");
        emo(0x1F609, ";)");
        emo(0x1F61B, ":P");
        emo(0x1F61C, ";P");
        emo(0x1F60E, "B)");
        emo(0x1F610, ":|");
        emo(0x1F615, ":/");
        emo(0x1F62E, ":O");
        emo(0x1F622, ":'(");
        emo(0x1F62D, ":'(");
        emo(0x1F61E, ":(");
        emo(0x1F641, ":(");
        emo(0x2639, ":(");
        emo(0x1F621, ">:(");
        emo(0x2764, "<3");
        emo(0x1F494, "</3");
        emo(0x1F44D, "(y)");
        emo(0x1F44E, "(n)");
    }

    private static void name(int cp, String n) {
        NAMES.put(cp, n);
    }

    private static void emo(int cp, String s) {
        EMOTICONS.put(cp, s);
    }

    private final boolean enabled;
    private final boolean emoticons;
    private final Map<Integer, String> overrides = new HashMap<>();

    public EmojiMapper(Properties cfg) {
        this.enabled = Boolean.parseBoolean(cfg.getProperty("emoji-convert", "true").trim());
        this.emoticons = "emoticon".equalsIgnoreCase(cfg.getProperty("emoji-style", "name").trim());
        for (String key : cfg.stringPropertyNames()) {
            if (!key.startsWith("emoji.override.")) continue;
            try {
                int cp = Integer.parseInt(key.substring("emoji.override.".length()).trim(), 16);
                overrides.put(cp, cfg.getProperty(key));
            } catch (NumberFormatException ignored) {
                // bad key, skip it
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Converts every emoji in {@code in} to readable text. Safe to call on any string (null stays null). */
    public String convert(String in) {
        if (!enabled || in == null || in.isEmpty()) return in;

        // <:name:id> / <a:name:id>  ->  :name:
        String s = CUSTOM.matcher(in).replaceAll(m -> Matcher.quoteReplacement(":" + m.group(1) + ":"));
        // 1\uFE0F\u20E3 -> [1]
        s = KEYCAP.matcher(s).replaceAll("[$1]");

        StringBuilder out = new StringBuilder(s.length() + 16);
        int[] cps = s.codePoints().toArray();
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];

            // flags = two regional indicator letters
            if (isRegional(cp)) {
                if (i + 1 < cps.length && isRegional(cps[i + 1])) {
                    String code = "" + (char) ('a' + cp - 0x1F1E6) + (char) ('a' + cps[i + 1] - 0x1F1E6);
                    out.append(":flag_").append(code).append(':');
                    i++;
                } else {
                    out.append((char) ('A' + cp - 0x1F1E6));
                }
                continue;
            }

            if (isInvisible(cp)) continue; // variation selectors, ZWJ, skin tones, tag characters

            if (isEmoji(cp)) {
                out.append(render(cp));
            } else {
                out.appendCodePoint(cp);
            }
        }
        return out.toString();
    }

    private String render(int cp) {
        String custom = overrides.get(cp);
        if (custom != null) return custom;
        if (emoticons) {
            String e = EMOTICONS.get(cp);
            if (e != null) return e;
        }
        String n = NAMES.get(cp);
        if (n == null) {
            String uni = Character.getName(cp);
            if (uni == null) return ""; // unassigned code point, drop it
            n = uni.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        }
        return ":" + n + ":";
    }

    private static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isInvisible(int cp) {
        return cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D || cp == 0x20E3
                || (cp >= 0x1F3FB && cp <= 0x1F3FF)   // skin tones
                || (cp >= 0xE0020 && cp <= 0xE007F);  // tag characters (subdivision flags)
    }

    /** Symbols the Minecraft font can already draw, so they are left alone. */
    private static boolean isDrawnByMinecraft(int cp) {
        return cp == 0x2605 || cp == 0x2606                // star outline / filled
                || (cp >= 0x2660 && cp <= 0x2667)          // card suits
                || cp == 0x266A || cp == 0x266B            // music notes
                || cp == 0x2713 || cp == 0x2717;           // check / cross marks
    }

    private static boolean isEmoji(int cp) {
        if (isDrawnByMinecraft(cp)) return false;
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x1F000 && cp <= 0x1F1E5)        // mahjong, cards, enclosed alphanumerics
                || (cp >= 0x2600 && cp <= 0x27BF)          // misc symbols + dingbats
                || (cp >= 0x23E9 && cp <= 0x23F3)
                || (cp >= 0x23F8 && cp <= 0x23FA)
                || cp == 0x231A || cp == 0x231B
                || cp == 0x2B50 || cp == 0x2B55 || cp == 0x2B1B || cp == 0x2B1C;
    }
}
