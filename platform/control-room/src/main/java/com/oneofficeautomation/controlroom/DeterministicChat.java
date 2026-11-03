package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ChatEngine.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deterministic One Chat mode: no model, nothing leaves the Command Center. Picks the agent whose name, examples or
 * aliases best match the words, fills its inputs from enums, dates, numbers and yes/no answers, and asks for whatever
 * is still missing (the ui.chat prompt, or the input's description).
 */
final class DeterministicChat implements ChatEngine {
    static final String ID = "deterministic";
    private static final String STATE = "deterministic.fill";
    private static final Set<String> CANCEL = Set.of("cancel", "stop", "never mind", "nevermind", "forget it");
    private static final int MAX_TRIES = 2;

    private final Clock clock;
    private final double minScore, margin;

    DeterministicChat(Clock clock) { this(clock, 0.5, 0.05); }

    /** @param minScore best match below this is not understood; @param margin two matches this close get "did you mean" */
    DeterministicChat(Clock clock, double minScore, double margin) { this.clock = clock; this.minScore = minScore; this.margin = margin; }

    @Override public String id() { return ID; }

    /** Inputs filled so far for one agent, and the input asked for. */
    record Fill(String path, Map<String, Object> inputs, String awaiting, double confidence, int tries) {}

    @Override public Result decide(Chat.Session s, List<ChatBot> bots, String text) {
        if (s.state.get(STATE) instanceof Fill f) {
            Optional<Result> r = continueFill(s, bots, f, text);
            if (r.isPresent()) return r.get();
        }
        s.state.remove(STATE);
        List<Match> ranked = rank(text, bots);
        if (ranked.isEmpty() || ranked.get(0).score < minScore) return new Handoff("no agent matched", true);
        Match top = ranked.get(0);
        if (ranked.size() > 1 && ranked.get(1).score >= minScore && top.score - ranked.get(1).score < margin)
            return Clarify.question("Did you mean " + top.bot.name() + " or " + ranked.get(1).bot.name() + "?");
        return next(s, top.bot, extractAll(text, top.bot), top.score);
    }

    private Optional<Result> continueFill(Chat.Session s, List<ChatBot> bots, Fill f, String text) {
        if (CANCEL.contains(text.trim().toLowerCase(Locale.ROOT))) { s.state.remove(STATE); return Optional.of(new Reply("OK, I've dropped that request.")); }
        ChatBot bot = bots.stream().filter(b -> b.path().equals(f.path)).findFirst().orElse(null);
        if (bot == null) return Optional.empty();
        Map<String, Object> variable = bot.inputs().stream().filter(v -> f.awaiting.equals(v.get("name"))).findFirst().orElse(Map.of());
        Optional<Object> value = extract(new Text(text), variable, true);
        if (value.isEmpty()) {
            List<Match> ranked = rank(text, bots);
            if (!ranked.isEmpty() && ranked.get(0).score >= minScore && !ranked.get(0).bot.path().equals(bot.path())) return Optional.empty(); // a new request
            if (f.tries + 1 >= MAX_TRIES) { s.state.remove(STATE); return Optional.of(new Handoff("could not read " + f.awaiting, true)); }
            s.state.put(STATE, new Fill(f.path, f.inputs, f.awaiting, f.confidence, f.tries + 1));
            return Optional.of(new Clarify("Sorry, I couldn't read that. " + prompt(bot, variable), missing(bot, f.inputs), bot.path()));
        }
        Map<String, Object> inputs = new LinkedHashMap<>(f.inputs);
        inputs.put(f.awaiting, value.get());
        extractAll(text, bot).forEach(inputs::putIfAbsent);
        return Optional.of(next(s, bot, inputs, f.confidence));
    }

    private Result next(Chat.Session s, ChatBot bot, Map<String, Object> inputs, double confidence) {
        List<String> missing = missing(bot, inputs);
        if (missing.isEmpty()) { s.state.remove(STATE); return new Proposal(bot.path(), inputs, confidence, ID); }
        String field = missing.get(0);
        s.state.put(STATE, new Fill(bot.path(), Map.copyOf(inputs), field, confidence, 0));
        Map<String, Object> variable = bot.inputs().stream().filter(v -> field.equals(v.get("name"))).findFirst().orElse(Map.of());
        return new Clarify(prompt(bot, variable), missing, bot.path());
    }

    private static List<String> missing(ChatBot bot, Map<String, Object> inputs) {
        return bot.inputs().stream().filter(BotTools::required).map(v -> String.valueOf(v.get("name"))).filter(n -> !inputs.containsKey(n)).toList();
    }

    /** The question for an input: the ui.chat prompt, else its description; with the choices when it has an enum. */
    private static String prompt(ChatBot bot, Map<String, Object> variable) {
        String name = String.valueOf(variable.get("name"));
        String q = bot.prompts().containsKey(name) ? bot.prompts().get(name)
                : "What should I use for " + (variable.get("description") instanceof String d && !d.isBlank() ? d : name) + "?";
        return variable.get("enum") instanceof List<?> en ? q + " (" + en.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")" : q;
    }

    // ---- intent matching ----

    record Match(ChatBot bot, double score) {}

    private static final double CONTAINMENT = 0.65, ALIAS = 0.9, PENALTY = 0.3, STRONG_NEGATIVE = 0.75;

    static List<Match> rank(String text, List<ChatBot> bots) {
        Set<String> tokens = tokens(text);
        String flat = flatten(text);
        List<Match> out = new ArrayList<>();
        for (ChatBot b : bots) {
            double score = 0;
            for (String a : b.aliases()) if (flat.contains(flatten(a))) { score = ALIAS; break; }
            if (score < ALIAS) {
                List<String> phrases = new ArrayList<>(b.examples());
                phrases.add(b.name());
                for (String p : phrases) score = Math.max(score, similarity(tokens, tokens(p)));
            }
            double negative = b.notFor().stream().mapToDouble(n -> similarity(tokens, tokens(n))).max().orElse(0);
            if (negative > 0 && (negative >= score || negative >= STRONG_NEGATIVE)) score *= PENALTY;
            if (score > 0) out.add(new Match(b, score));
        }
        out.sort(Comparator.comparingDouble(Match::score).reversed());
        return out;
    }

    static double similarity(Set<String> text, Set<String> phrase) {
        if (text.isEmpty() || phrase.isEmpty()) return 0;
        Set<String> common = new HashSet<>(text); common.retainAll(phrase);
        if (common.isEmpty()) return 0;
        Set<String> union = new HashSet<>(text); union.addAll(phrase);
        return CONTAINMENT * common.size() / phrase.size() + (1 - CONTAINMENT) * common.size() / union.size();
    }

    private static final Set<String> STOP = Set.of("a", "an", "the", "for", "of", "to", "in", "on", "at", "by", "with", "from", "and", "or", "my", "me",
            "i", "we", "our", "us", "you", "please", "pls", "can", "could", "would", "will", "should", "kindly", "is", "are", "be", "it", "this", "that",
            "these", "those", "all", "some", "any", "do", "does", "now", "just", "want", "need", "like", "let", "lets", "s");

    static String flatten(String text) { return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim() + " "; }

    static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : flatten(text).trim().split(" ")) {
            if (w.isEmpty() || STOP.contains(w) || w.chars().allMatch(Character::isDigit)) continue;
            out.add(w.length() > 5 && w.endsWith("ing") ? w.substring(0, w.length() - 3)
                    : w.length() > 4 && w.endsWith("ies") ? w.substring(0, w.length() - 3) + "y"
                    : w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") ? w.substring(0, w.length() - 1) : w);
        }
        return out;
    }

    // ---- input filling ----

    private enum Kind { ENUM, DATE, NUMBER, BOOLEAN, TEXT }

    private static Kind kind(Map<String, Object> v) {
        if (v.get("enum") instanceof List<?>) return Kind.ENUM;
        return switch (String.valueOf(v.get("type"))) { case "DateTime" -> Kind.DATE; case "Number" -> Kind.NUMBER; case "Boolean" -> Kind.BOOLEAN; default -> Kind.TEXT; };
    }

    /** Free-form requests: enums first, then dates, then numbers, so later kinds skip words already used. */
    private Map<String, Object> extractAll(String text, ChatBot bot) {
        Map<String, Object> found = new LinkedHashMap<>();
        Text t = new Text(text);
        for (Kind pass : List.of(Kind.ENUM, Kind.DATE, Kind.NUMBER))
            for (Map<String, Object> v : bot.inputs())
                if (!found.containsKey(String.valueOf(v.get("name"))) && kind(v) == pass)
                    extract(t, v, false).ifPresent(x -> found.put(String.valueOf(v.get("name")), x));
        return found;
    }

    private Optional<Object> extract(Text t, Map<String, Object> v, boolean answering) {
        return switch (kind(v)) {
            case ENUM -> {
                for (Object e : (List<?>) v.get("enum")) if (t.has(String.valueOf(e))) { t.use(String.valueOf(e)); yield Optional.of(e); }
                yield Optional.empty();
            }
            case DATE -> date(t);
            case NUMBER -> number(t).map(n -> n.scale() <= 0 ? (Object) n.longValueExact() : (Object) n.doubleValue());
            case BOOLEAN -> answering ? yesNo(t.original) : Optional.empty();
            case TEXT -> answering && !t.original.isBlank() ? Optional.of(t.original.trim()) : Optional.empty();
        };
    }

    private static final String MONTHS = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?";
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b"),
            DMY = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b"),
            DAY_MONTH = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+" + MONTHS + "(?:\\s+(\\d{4}))?\\b"),
            MONTH_DAY = Pattern.compile("\\b" + MONTHS + "\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b"),
            RELATIVE = Pattern.compile("\\b(today|yesterday|tomorrow)\\b"),
            NUMBER = Pattern.compile("(?<![\\p{L}\\d.,-])(\\d{1,3}(?:,\\d{2,3})+|\\d+)(\\.\\d+)?\\s*(lakhs?|lacs?|crores?|cr|k|thousand)?(?![\\p{L}\\d])");
    private static final List<String> MONTH_KEYS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /** A date as ISO yyyy-MM-dd: today/yesterday/tomorrow, 2026-11-03, 03/11/2026 (day first), 3 Nov, Nov 3. */
    private Optional<Object> date(Text t) {
        LocalDate today = LocalDate.now(clock);
        Matcher m = RELATIVE.matcher(t.lower);
        if (m.find()) { t.use(m); return Optional.of((switch (m.group(1)) { case "yesterday" -> today.minusDays(1); case "tomorrow" -> today.plusDays(1); default -> today; }).toString()); }
        if ((m = ISO.matcher(t.lower)).find()) return date(t, m, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if ((m = DMY.matcher(t.lower)).find()) return date(t, m, Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        if ((m = DAY_MONTH.matcher(t.lower)).find())
            return date(t, m, m.group(3) != null ? Integer.parseInt(m.group(3)) : today.getYear(), MONTH_KEYS.indexOf(m.group(2).substring(0, 3)) + 1, Integer.parseInt(m.group(1)));
        if ((m = MONTH_DAY.matcher(t.lower)).find())
            return date(t, m, m.group(3) != null ? Integer.parseInt(m.group(3)) : today.getYear(), MONTH_KEYS.indexOf(m.group(1).substring(0, 3)) + 1, Integer.parseInt(m.group(2)));
        return Optional.empty();
    }

    private static Optional<Object> date(Text t, Matcher m, int y, int mo, int d) {
        try { String iso = LocalDate.of(y, mo, d).toString(); t.use(m); return Optional.of(iso); } catch (DateTimeException e) { return Optional.empty(); }
    }

    /** A number, with Indian groupings (1,12,000) and words (5 lakh, 2 crore, 10k). */
    private static Optional<BigDecimal> number(Text t) {
        Matcher m = NUMBER.matcher(t.lower);
        if (!m.find()) return Optional.empty();
        BigDecimal n = new BigDecimal(m.group(1).replace(",", "") + (m.group(2) != null ? m.group(2) : ""));
        if (m.group(3) != null) n = n.multiply(BigDecimal.valueOf(switch (m.group(3)) { case "k", "thousand" -> 1_000L; case "cr", "crore", "crores" -> 10_000_000L; default -> 100_000L; }));
        t.use(m);
        return Optional.of(n.stripTrailingZeros());
    }

    private static Optional<Object> yesNo(String answer) {
        String a = answer.trim().toLowerCase(Locale.ROOT);
        if (a.matches("(yes|y|true|yeah|yep|haan)\\b.*")) return Optional.of(true);
        if (a.matches("(no|n|false|nope|nahi)\\b.*")) return Optional.of(false);
        return Optional.empty();
    }

    /** The message with the words already used by earlier inputs blanked out. */
    private static final class Text {
        final String original;
        String lower;

        Text(String s) { original = s; lower = s.toLowerCase(Locale.ROOT); }

        boolean has(String phrase) { return phrase(phrase).matcher(lower).find(); }
        void use(String phrase) { Matcher m = phrase(phrase).matcher(lower); if (m.find()) use(m); }
        void use(Matcher m) { lower = lower.substring(0, m.start()) + " ".repeat(m.end() - m.start()) + lower.substring(m.end()); }

        private static Pattern phrase(String p) {
            String body = Arrays.stream(flatten(p).trim().split(" ")).map(Pattern::quote).collect(Collectors.joining("[^\\p{L}\\p{Nd}]+"));
            return Pattern.compile("(?<![\\p{L}\\p{Nd}])" + body + "(?![\\p{L}\\p{Nd}])");
        }
    }
}
