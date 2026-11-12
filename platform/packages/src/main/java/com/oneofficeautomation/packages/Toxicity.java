package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Toxicity guardrail for AI steps: rates a prompt or a model's answer as none, low, medium or high, offline and for any
 * model provider. Threats, self-harm and dehumanising language rate high; insults aimed at someone and explicit sexual
 * content medium; swearing low. Common disguises (l33t spelling, s.p.a.c.e.d letters, repeated letters) are undone
 * first, and teams can add their own blocked terms. With an OpenAI-style provider the answer can also go through the
 * provider's moderation service ({@link LlmClient#moderate}).
 */
public final class Toxicity {
    private Toxicity() {}

    public static final List<String> LEVELS = List.of("none", "low", "medium", "high");

    /** The rating: the highest level found, the categories and the matched words (for the log). */
    public record Rating(String level, List<String> categories, List<String> terms) {
        public boolean atLeast(String threshold) { return LEVELS.indexOf(level) >= LEVELS.indexOf(threshold) && !level.equals("none"); }
    }

    private record Rule(String category, String level, Pattern pattern) {}

    private static Rule rule(String category, String level, String regex) {
        return new Rule(category, level, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    private static final String YOU = "(?:you|u|ya|your\\s+(?:family|kids|children))";
    private static final List<Rule> RULES = List.of(
            rule("threat", "high", "\\b(?:i|we)(?:'ll|\\s+will|\\s+am\\s+going\\s+to|\\s+are\\s+going\\s+to|\\s+gonna|'m\\s+going\\s+to)\\s+(?:kill|murder|hurt|shoot|stab|beat|strangle|bury|destroy)\\s+" + YOU + "\\b"),
            rule("threat", "high", "\\b(?:kill|murder|shoot|stab|strangle)\\s+" + YOU + "\\b"),
            rule("threat", "high", "\\b(?:will|'ll|gonna|going\\s+to|want\\s+to|wanna)\\s+(?:kill|murder|shoot|stab|strangle|hurt|beat\\s+up)\\s+(?:him|her|them|everyone|everybody|all\\s+of\\s+(?:you|them))\\b"),
            rule("threat", "high", "\\b" + YOU + "\\s+(?:will|are\\s+going\\s+to|gonna)\\s+(?:die|pay\\s+for\\s+this|regret\\s+this)\\b"),
            rule("threat", "high", "\\b(?:watch\\s+your\\s+back|i\\s+know\\s+where\\s+you\\s+live|bomb\\s+(?:the|your)\\s+\\w+)\\b"),
            rule("self_harm", "high", "\\b(?:kill\\s+(?:myself|yourself|urself)|kys|end\\s+(?:my|your)\\s+life|want\\s+to\\s+die|hang\\s+(?:myself|yourself)|cut\\s+(?:myself|yourself))\\b"),
            rule("hate", "high", "\\b(?:subhuman|sub-human|vermin|untermensch|inferior\\s+races?|should\\s+be\\s+(?:exterminated|wiped\\s+out|gassed)|go\\s+back\\s+to\\s+(?:your\\s+)?(?:own\\s+)?country)\\b"),
            rule("harassment", "medium", "\\b" + YOU + "(?:\\s+are|'re|\\s+r)?\\s+(?:an?\\s+|such\\s+an?\\s+|so\\s+)?(?:fucking\\s+)?(?:idiot|moron|imbecile|stupid|dumb|loser|worthless|pathetic|useless|clown|retard(?:ed)?|piece\\s+of\\s+(?:shit|crap|garbage))\\b"),
            rule("harassment", "medium", "\\b(?:shut\\s+(?:the\\s+fuck\\s+)?up|stfu|go\\s+to\\s+hell|screw\\s+you|fuck\\s+(?:you|off|u)|f\\s*off|get\\s+lost)\\b"),
            rule("harassment", "medium", "\\b(?:retard(?:ed)?)\\b"),
            rule("sexual", "medium", "\\b(?:porn(?:o|ography)?|nudes?|nsfw|sexting|blowjob|handjob|cumshot|xxx)\\b"),
            rule("insult", "low", "\\b(?:idiot(?:ic)?|moron(?:ic)?|imbecile|stupid|dumbass|loser|jerk|clown|scumbag|trash\\s+human)\\b"),
            rule("profanity", "low", "\\b(?:fuck(?:ing|ed|er|s)?|motherfuck(?:er|ing)?|shit(?:ty|s)?|bullshit|bitch(?:es|y)?|bastards?|assholes?|arse(?:hole)?s?|dickhead|piss(?:ed)?\\s+off|wtf|crap(?:py)?)\\b"));

    /** Undoes common disguises: accents, l33t letters, s.p.a.c.e.d letters and long letter repeats. */
    static String normalise(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            b.append(switch (c) {
                case '0' -> 'o'; case '1', '!' -> 'i'; case '3' -> 'e'; case '4', '@' -> 'a'; case '5', '$' -> 's'; case '7' -> 't';
                case '*' -> 'u';
                default -> c;
            });
        }
        // letters joined by dots, dashes, underscores or single spaces: f.u.c.k, k y s
        Matcher m = Pattern.compile("\\b(?:\\p{L}[._\\- ]){2,}\\p{L}\\b").matcher(b);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(m.group().replaceAll("[._\\- ]", "")));
        m.appendTail(out);
        return out.toString().replaceAll("(\\p{L})\\1{2,}", "$1$1");
    }

    /** Rates a text; extra terms (whole words or phrases, any case) rate high. Numbers keep their meaning in the original text. */
    public static Rating rate(String text, Collection<String> extraTerms) {
        if (text == null || text.isBlank()) return new Rating("none", List.of(), List.of());
        String plain = text, norm = normalise(text);
        String level = "none";
        Set<String> cats = new LinkedHashSet<>();
        Set<String> terms = new LinkedHashSet<>();
        for (Rule r : RULES) {
            for (String candidate : new String[]{plain, norm}) {
                Matcher m = r.pattern().matcher(candidate);
                if (m.find()) {
                    cats.add(r.category());
                    terms.add(m.group().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "));
                    if (LEVELS.indexOf(r.level()) > LEVELS.indexOf(level)) level = r.level();
                    break;
                }
            }
        }
        if (extraTerms != null) for (String term : extraTerms) {
            if (term == null || term.isBlank()) continue;
            Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term.strip()) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            if (p.matcher(plain).find() || p.matcher(norm).find()) { cats.add("blocked_term"); terms.add(term.strip().toLowerCase(Locale.ROOT)); level = "high"; }
        }
        return new Rating(level, List.copyOf(cats), List.copyOf(terms));
    }

    /** A toxicity setting: what to do (log or block) and from which level. */
    public record Setting(String action, String level, List<String> terms) {
        public boolean on() { return action != null && !action.equals("off"); }
    }

    /** Reads and checks the step's toxicity inputs. */
    public static Setting setting(String action, String level, List<Object> terms, String prefix) {
        String a = action == null || action.isBlank() ? "off" : action.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("off", "log", "block").contains(a)) throw new ActionException(prefix + ".BAD_OPTION", "The toxicity guardrail is off, log or block, not '" + action + "'", "Set the toxicity guardrail to off, log or block.");
        String l = level == null || level.isBlank() ? "medium" : level.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("low", "medium", "high").contains(l)) throw new ActionException(prefix + ".BAD_OPTION", "The toxicity level is low, medium or high, not '" + level + "'", "low catches swearing too; high only threats, self-harm and hate.");
        List<String> t = new ArrayList<>();
        if (terms != null) for (Object o : terms) if (o != null && !String.valueOf(o).isBlank()) t.add(String.valueOf(o));
        return new Setting(a, l, t);
    }

    /** The setting of the run's AI guardrails policy (One CommandCenter), or null when it sets none. */
    @SuppressWarnings("unchecked")
    static Setting policy(ActionContext ctx) {
        Object g = ModelConnections.guardrails(ctx).get("toxicity");
        if (!(g instanceof Map<?, ?> m)) return null;
        String action = m.get("action") == null ? "off" : String.valueOf(m.get("action"));
        if (action.equals("off")) return null;
        List<Object> terms = m.get("terms") instanceof List<?> l ? (List<Object>) l : List.of();
        return setting(action, m.get("level") == null ? null : String.valueOf(m.get("level")), terms, "AI");
    }

    /**
     * Checks a text against a setting. Logs what was found; with block and a rating at or above the level, throws
     * {code} (e.g. AI.TOXIC_PROMPT or AI.TOXIC_ANSWER) unless {@code soft} is set, in which case the rating is returned.
     */
    public static Rating check(ActionContext ctx, Setting s, String text, String what, String code, boolean soft) {
        if (s == null || !s.on()) return null;
        Rating r = rate(text, s.terms());
        if (!r.atLeast(s.level())) return r;
        ctx.warn("Toxicity guardrail: the " + what + " rates " + r.level() + " (" + String.join(", ", r.categories()) + ")");
        if (s.action().equals("block") && !soft)
            throw new ActionException(code, "The " + what + " was stopped by the toxicity guardrail: " + r.level() + " (" + String.join(", ", r.categories()) + ")",
                    "Rephrase it, or have a person handle this case.");
        return r;
    }
}
