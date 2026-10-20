package com.oneofficeautomation.packages.ui;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.OoaException;

import java.util.*;
import java.util.function.*;

/**
 * Self-healing element location. When the primary locator finds nothing, the alternatives recorded with the element
 * are tried in a fixed order, and the strategy that found it is logged so the agent's author can update the locator.
 *
 * <p>Alternatives are a dictionary such as {"automation_id": "save-btn", "name": "save", "id": "btnSave",
 * "aria_label": "Save", "tag": "button", "class": "btn primary", "index": 2, "text": "Save",
 * "relative": {"anchor": "Invoice number", "direction": "right"}}. Each toolkit turns a strategy into its own query
 * (a Playwright selector, a window match) and supplies a resolver; this class only decides the order and the timing,
 * so it can be tested without a browser or a desktop.
 */
public final class SelfHealing {
    private SelfHealing() {}

    /** Fallback order after the primary locator. */
    public static final List<String> ORDER = List.of("automation_id", "name", "id", "aria_label", "class_index", "text", "relative");

    /** One way of finding the element: the strategy name and the toolkit-specific query. */
    public record Candidate(String strategy, String query) {}

    /** The element and how it was found; healed is true when a fallback, not the primary locator, found it. */
    public record Found<E>(E element, Candidate by, boolean healed) {}

    /** Looks one candidate up once, without waiting; empty when nothing matches right now. */
    @FunctionalInterface
    public interface Resolver<E> { Optional<E> resolve(Candidate c); }

    /** Clock and sleep, replaceable in tests. */
    public interface Timer { long nowMillis(); void sleep(long ms); }

    public static final Timer SYSTEM = new Timer() {
        public long nowMillis() { return System.currentTimeMillis(); }
        public void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("RUN.CANCELLED", "Interrupted"); }
        }
    };

    /**
     * The primary candidate followed by one candidate per recorded alternative, in {@link #ORDER}.
     * The translator turns (strategy, alternatives) into a query, or null when the toolkit cannot use that strategy.
     */
    public static List<Candidate> chain(Candidate primary, Map<String, Object> alternatives, BiFunction<String, Map<String, Object>, String> translator) {
        return chain(primary, alternatives, ORDER, translator);
    }

    /** As above with a toolkit's own strategy order (for example windows: title pattern, class name, process name). */
    public static List<Candidate> chain(Candidate primary, Map<String, Object> alternatives, List<String> order, BiFunction<String, Map<String, Object>, String> translator) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (primary != null && primary.query() != null && !primary.query().isBlank()) { out.add(primary); seen.add(primary.query()); }
        if (alternatives == null || alternatives.isEmpty()) return out;
        for (String strategy : order) {
            if (!applies(strategy, alternatives)) continue;
            String q = translator.apply(strategy, alternatives);
            if (q != null && !q.isBlank() && seen.add(q)) out.add(new Candidate(strategy, q));
        }
        return out;
    }

    static boolean applies(String strategy, Map<String, Object> alt) {
        return switch (strategy) {
            case "class_index" -> notBlank(alt.get("class")) || notBlank(alt.get("class_name")) || (notBlank(alt.get("tag")) && alt.get("index") != null);
            case "relative" -> alt.get("relative") instanceof Map<?, ?> r && notBlank(r.get("anchor"));
            default -> notBlank(alt.get(strategy));
        };
    }

    static boolean notBlank(Object o) { return o != null && !String.valueOf(o).isBlank(); }

    /**
     * Polls the chain in order until one candidate resolves or the timeout passes. After the first full round with
     * no match, onMiss runs once (the interrupt handler check, which may dismiss a pop-up hiding the element).
     */
    public static <E> Found<E> locate(List<Candidate> chain, Resolver<E> resolver, long timeoutMs, Timer timer, long pollMs,
                                      Runnable onMiss, Consumer<String> log, Runnable checkCancelled) {
        if (chain.isEmpty()) throw new ActionException("UI.NO_LOCATOR", "No locator was given for the element", "Give a selector, or capture the element again.");
        long end = timer.nowMillis() + Math.max(0, timeoutMs);
        boolean missed = false;
        Map<String, String> errors = new LinkedHashMap<>();
        while (true) {
            for (Candidate c : chain) {
                Optional<E> e;
                try { e = resolver.resolve(c); }
                catch (RuntimeException ex) {
                    if (ex instanceof OoaException o && "RUN.CANCELLED".equals(o.code())) throw ex;
                    errors.put(c.strategy(), String.valueOf(ex.getMessage()));
                    continue;
                }
                if (e.isPresent()) {
                    boolean healed = c != chain.get(0);
                    if (healed && log != null)
                        log.accept("Self-healing: the primary locator '" + chain.get(0).query() + "' found nothing; found the element by " + c.strategy() + " (" + c.query() + "). Update the locator.");
                    return new Found<>(e.get(), c, healed);
                }
            }
            if (checkCancelled != null) checkCancelled.run();
            if (!missed) {
                missed = true;
                if (onMiss != null) { onMiss.run(); continue; }   // look again at once: the handler may have closed what hid the element
            }
            if (timer.nowMillis() >= end) break;
            timer.sleep(Math.max(1, Math.min(pollMs, end - timer.nowMillis())));
        }
        StringJoiner tried = new StringJoiner(", ");
        for (Candidate c : chain) tried.add(c.strategy() + " " + c.query());
        throw new ActionException("UI.ELEMENT_NOT_FOUND", "No element found within " + timeoutMs + " ms; tried " + tried + (errors.isEmpty() ? "" : "; errors: " + errors),
                "Check the page or window shows the element, capture it again, or raise the timeout.", true);
    }

    /** Escapes a value for use inside double quotes in a selector. */
    public static String quote(Object v) { return "\"" + String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    /** Playwright selectors for each strategy (web pages); the picker in the browser toolkit records these alternatives. */
    @SuppressWarnings("unchecked")
    public static String webSelector(String strategy, Map<String, Object> a) {
        String tag = notBlank(a.get("tag")) ? String.valueOf(a.get("tag")).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "") : "";
        return switch (strategy) {
            case "automation_id" -> "[data-testid=" + quote(a.get("automation_id")) + "]";
            case "name" -> tag + "[name=" + quote(a.get("name")) + "]";
            case "id" -> "[id=" + quote(a.get("id")) + "]";
            case "aria_label" -> "[aria-label=" + quote(a.get("aria_label")) + "]";
            case "class_index" -> {
                StringBuilder css = new StringBuilder(tag.isEmpty() ? "*" : tag);
                if (notBlank(a.get("class")))
                    for (String c : String.valueOf(a.get("class")).trim().split("\\s+")) if (c.matches("[A-Za-z_-][A-Za-z0-9_-]*")) css.append('.').append(c);
                int index = a.get("index") instanceof Number n ? n.intValue() : 0;
                yield "css=" + css + " >> nth=" + Math.max(0, index);
            }
            case "text" -> (tag.isEmpty() ? "" : tag + ":") + "text=" + quote(a.get("text"));
            case "relative" -> {
                Map<String, Object> r = (Map<String, Object>) a.get("relative");
                String dir = switch (String.valueOf(r.getOrDefault("direction", "near")).toLowerCase(Locale.ROOT)) {
                    case "right", "right-of", "right_of" -> "right-of";
                    case "left", "left-of", "left_of" -> "left-of";
                    case "above" -> "above";
                    case "below" -> "below";
                    default -> "near";
                };
                yield (tag.isEmpty() ? "*" : tag) + ":" + dir + "(:text(" + quote(r.get("anchor")) + "))";
            }
            default -> null;
        };
    }
}
