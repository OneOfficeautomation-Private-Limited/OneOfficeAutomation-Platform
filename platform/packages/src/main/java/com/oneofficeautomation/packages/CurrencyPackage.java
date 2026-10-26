package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Currency conversion with a choice of rate source: the European Central Bank's daily reference rates, any JSON
 * endpoint (URL plus the path of the rates object in the answer), or a fixed Dictionary of rates supplied by the agent.
 * Downloaded rates are kept for the rest of the run, so a loop over many rows calls the source once.
 */
@OoaPackage(id = "ooa.currency", name = "Currency", version = "1.0.0",
        description = "Convert amounts between currencies and look up exchange rates from the ECB, a JSON rates service or your own table.")
public final class CurrencyPackage {
    static final String P = "CURRENCY";
    static final String ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    /** The ECB's published history files: the last 90 days, and every day since 1999 (larger). */
    static final String ECB_90D_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist-90d.xml";
    static final String ECB_HIST_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.xml";
    static final String CACHE = "_ooa.currency.rates";
    static final MathContext MC = MathContext.DECIMAL64;

    static final String D_SOURCE = "ecb: European Central Bank daily reference rates (base EUR). json: a JSON rates service at 'url'. fixed: the 'rates' Dictionary.";
    static final String D_URL = "For json: the address returning the rates. For ecb: leave empty to use the ECB's public file.";
    static final String D_PATH = "For json: where the rates object sits in the answer, e.g. rates, $.data.rates or results[0].rates. Empty = the whole answer.";
    static final String D_BASE = "Currency the rates are quoted against (rate 1). ecb: always EUR. json: taken from the answer's 'base' field when empty. fixed: required.";
    static final String D_RATES = "For fixed: currency code to rate against the base, e.g. {\"USD\": 1.08, \"GBP\": 0.85}.";

    record Rates(String base, Map<String, BigDecimal> rates, String date, String source) {
        BigDecimal rate(String code) {
            String c = code(code);
            if (c.equals(base)) return BigDecimal.ONE;
            BigDecimal r = rates.get(c);
            if (r == null) throw new ActionException(P + ".UNKNOWN_CURRENCY", "No rate for " + c + " in the " + source + " rates",
                    "Use List currencies to see the available codes.");
            return r;
        }
    }

    static String code(String c) {
        String s = c == null ? "" : c.strip().toUpperCase(Locale.ROOT);
        if (!s.matches("^[A-Z]{3}$")) throw new ActionException(P + ".BAD_CODE", "'" + c + "' is not a three-letter currency code", "Use ISO 4217 codes such as EUR, USD or INR.");
        return s;
    }

    // ---------- rate sources ----------

    @SuppressWarnings("unchecked")
    static Rates rates(ActionContext ctx, String source, String url, String path, String base, Map<String, Object> fixed) {
        String src = source == null || source.isBlank() ? "ecb" : source;
        if (src.equals("fixed")) return fixed(base, fixed);
        if (!src.equals("ecb") && !src.equals("json"))
            throw new ActionException(P + ".BAD_SOURCE", "Unknown rate source '" + source + "'", "Use ecb, json or fixed.");
        String address = src.equals("ecb") && (url == null || url.isBlank()) ? ECB_URL : url;
        if (address == null || address.isBlank()) throw new ActionException(P + ".NO_URL", "The json source needs a url", "Give the address of the rates service.");
        String key = src + "|" + address + "|" + (path == null ? "" : path) + "|" + (base == null ? "" : base);
        Map<String, Rates> cache = (Map<String, Rates>) ctx.sessions().computeIfAbsent(CACHE, k -> new ConcurrentHashMap<String, Rates>());
        Rates cached = cache.get(key);
        if (cached != null) return cached;
        HttpJson.Response r = HttpJson.req(P, "GET", address).header("Accept", src.equals("ecb") ? "application/xml, text/xml, */*" : "application/json").timeout(30).send(ctx);
        Rates loaded = src.equals("ecb") ? ecb(r.text()) : json(r.json(), path, base);
        cache.put(key, loaded);
        ctx.info("Loaded " + loaded.rates().size() + " exchange rates from " + HttpJson.host(address) + (loaded.date() == null ? "" : " dated " + loaded.date()));
        return loaded;
    }

    private static final Pattern ECB_RATE = Pattern.compile("<Cube\\s+currency=['\"]([A-Za-z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]\\s*/?>");
    private static final Pattern ECB_TIME = Pattern.compile("<Cube\\s+time=['\"]([0-9-]+)['\"]");

    static Rates ecb(String xml) {
        Map<String, BigDecimal> rates = new TreeMap<>();
        Matcher m = ECB_RATE.matcher(xml == null ? "" : xml);
        while (m.find()) rates.put(m.group(1).toUpperCase(Locale.ROOT), new BigDecimal(m.group(2)));
        if (rates.isEmpty()) throw new ActionException(P + ".BAD_RESPONSE", "The ECB answer contained no exchange rates", "Check the url returns the eurofxref XML.", true);
        Matcher t = ECB_TIME.matcher(xml);
        return new Rates("EUR", rates, t.find() ? t.group(1) : null, "ECB");
    }

    static Rates json(Object answer, String path, String base) {
        Object node = select(answer, path);
        if (!(node instanceof Map<?, ?> m))
            throw new ActionException(P + ".BAD_RESPONSE", "No rates object at '" + (path == null || path.isBlank() ? "$" : path) + "' in the answer",
                    "Set rates_path to the field holding {\"CODE\": rate, ...}.");
        Map<String, BigDecimal> rates = new TreeMap<>();
        for (var e : m.entrySet()) {
            String k = String.valueOf(e.getKey()).toUpperCase(Locale.ROOT);
            if (!k.matches("^[A-Z]{3}$")) continue;
            try { rates.put(k, Values.toNumber(e.getValue())); } catch (OoaException ignored) { /* skip non-numeric entries */ }
        }
        if (rates.isEmpty()) throw new ActionException(P + ".BAD_RESPONSE", "The rates object has no numeric rates", "Check rates_path.");
        String b = base;
        String date = null;
        if (answer instanceof Map<?, ?> top) {
            if ((b == null || b.isBlank()) && top.get("base") != null) b = String.valueOf(top.get("base"));
            if ((b == null || b.isBlank()) && top.get("base_code") != null) b = String.valueOf(top.get("base_code"));
            if (top.get("date") != null) date = String.valueOf(top.get("date"));
        }
        if (b == null || b.isBlank()) throw new ActionException(P + ".NO_BASE", "The answer does not say which currency the rates are against", "Set base_currency.");
        return new Rates(code(b), rates, date, "JSON");
    }

    static Rates fixed(String base, Map<String, Object> fixed) {
        if (fixed == null || fixed.isEmpty()) throw new ActionException(P + ".NO_RATES", "The fixed source needs a rates Dictionary", D_RATES);
        if (base == null || base.isBlank()) throw new ActionException(P + ".NO_BASE", "The fixed source needs base_currency", "Name the currency your rates are quoted against.");
        Map<String, BigDecimal> rates = new TreeMap<>();
        for (var e : fixed.entrySet()) {
            BigDecimal v;
            try { v = Values.toNumber(e.getValue()); } catch (OoaException ex) { throw new ActionException(P + ".BAD_RATE", "Rate for " + e.getKey() + " is not a number"); }
            if (v.signum() <= 0) throw new ActionException(P + ".BAD_RATE", "Rate for " + e.getKey() + " must be greater than 0");
            rates.put(code(e.getKey()), v);
        }
        return new Rates(code(base), rates, null, "fixed");
    }

    /** A small JSONPath subset: $, dotted keys, [n] indexes and ['key'] brackets. */
    static Object select(Object root, String path) {
        if (path == null || path.isBlank() || path.strip().equals("$")) return root;
        String p = path.strip();
        if (p.startsWith("$")) p = p.substring(1);
        Matcher m = Pattern.compile("\\.?([^.\\[\\]]+)|\\[(\\d+)]|\\['([^']*)']|\\[\"([^\"]*)\"]").matcher(p);
        List<Object> steps = new ArrayList<>();   // String keys and Integer indexes, parsed fully before walking
        int pos = 0;
        while (pos < p.length()) {
            if (!m.find(pos) || m.start() != pos) throw new ActionException(P + ".BAD_PATH", "Cannot read the path '" + path + "'", "Use a form like data.rates or $.results[0].rates.");
            pos = m.end();
            if (m.group(2) != null) steps.add(Integer.parseInt(m.group(2)));
            else steps.add(m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : m.group(4));
        }
        Object cur = root;
        for (Object s : steps) {
            if (s instanceof Integer i) cur = cur instanceof List<?> l && i < l.size() ? l.get(i) : null;
            else cur = cur instanceof Map<?, ?> mm ? mm.get(s) : null;
            if (cur == null) return null;
        }
        return cur;
    }

    private static final Pattern ECB_DAY = Pattern.compile("<Cube\\s+time=['\"]([0-9]{4}-[0-9]{2}-[0-9]{2})['\"]\\s*>(.*?)</Cube>", Pattern.DOTALL);

    /** Every day of an ECB history file, by ISO date. */
    static TreeMap<String, Rates> ecbHistory(String xml) {
        TreeMap<String, Rates> days = new TreeMap<>();
        Matcher m = ECB_DAY.matcher(xml == null ? "" : xml);
        while (m.find()) {
            Map<String, BigDecimal> rates = new TreeMap<>();
            Matcher r = ECB_RATE.matcher(m.group(2));
            while (r.find()) rates.put(r.group(1).toUpperCase(Locale.ROOT), new BigDecimal(r.group(2)));
            if (!rates.isEmpty()) days.put(m.group(1), new Rates("EUR", rates, m.group(1), "ECB"));
        }
        if (days.isEmpty()) throw new ActionException(P + ".BAD_RESPONSE", "The ECB history contained no exchange rates", "Check the url returns the eurofxref history XML.", true);
        return days;
    }

    /**
     * The ECB rates of a past day: that day's, or the latest earlier working day's (the ECB publishes none on weekends
     * and holidays). Recent dates use the 90-day file, older ones the full history; a url replaces both.
     */
    @SuppressWarnings("unchecked")
    static Rates ecbOn(ActionContext ctx, java.time.LocalDate day, String url) {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        if (day.isAfter(today)) throw new ActionException(P + ".BAD_DATE", "The date " + day + " is in the future", "Give today's date or an earlier one.");
        String address = url != null && !url.isBlank() ? url.strip() : day.isAfter(today.minusDays(85)) ? ECB_90D_URL : ECB_HIST_URL;
        Map<String, TreeMap<String, Rates>> cache = (Map<String, TreeMap<String, Rates>>) ctx.sessions().computeIfAbsent(CACHE + ".history", k -> new ConcurrentHashMap<String, TreeMap<String, Rates>>());
        TreeMap<String, Rates> days = cache.get(address);
        if (days == null) {
            HttpJson.Response r = HttpJson.req(P, "GET", address).header("Accept", "application/xml, text/xml, */*").timeout(120).send(ctx);
            days = ecbHistory(r.text());
            cache.put(address, days);
            ctx.info("Loaded ECB exchange rates for " + days.size() + " days from " + HttpJson.host(address));
        }
        Map.Entry<String, Rates> e = days.floorEntry(day.toString());
        if (e == null || java.time.LocalDate.parse(e.getKey()).isBefore(day.minusDays(10)))
            throw new ActionException(P + ".NO_RATE_FOR_DATE", "The ECB has no rates on or shortly before " + day + " (its file starts " + days.firstKey() + ")",
                    "ECB reference rates start on 1999-01-04; choose a later date.");
        return e.getValue();
    }

    static BigDecimal rate(Rates r, String from, String to) {
        return r.rate(to).divide(r.rate(from), MC);
    }

    // ---------- actions ----------

    @Action(id = "convert", name = "Convert amount", category = "Currency", timeoutSeconds = 60, capabilities = "network",
            description = "Convert an amount from one currency to another using the chosen rate source.",
            panel = "amount, from_currency, to_currency | [Use the rates of a past day: date], decimals, source, url, rates_path, base_currency, rates")
    @Output(name = "amount", type = OoaType.NUMBER, description = "Converted amount, rounded to 'decimals'.")
    @Output(name = "rate", type = OoaType.NUMBER, description = "Rate used: units of the target currency for one unit of the source currency.")
    @Output(name = "rate_date", type = OoaType.STRING, description = "Date of the rates when the source gives one, otherwise empty.")
    public Map<String, Object> convert(ActionContext ctx,
            @Input(name = "amount", type = OoaType.NUMBER, description = "Amount to convert.") BigDecimal amount,
            @Input(name = "from_currency", type = OoaType.STRING, example = "USD", description = "Currency of the amount.") String from,
            @Input(name = "to_currency", type = OoaType.STRING, example = "EUR", description = "Currency to convert into.") String to,
            @Input(name = "decimals", type = OoaType.NUMBER, required = false, defaultValue = "2", min = 0, max = 10, description = "Decimal places in the result.") int decimals,
            @Input(name = "source", type = OoaType.STRING, required = false, defaultValue = "\"ecb\"", enumValues = {"ecb", "json", "fixed"}, description = D_SOURCE) String source,
            @Input(name = "url", type = OoaType.STRING, required = false, visibleWhen = "source=json", description = D_URL) String url,
            @Input(name = "rates_path", type = OoaType.STRING, required = false, visibleWhen = "source=json", description = D_PATH) String path,
            @Input(name = "base_currency", type = OoaType.STRING, required = false, visibleWhen = "source=json|fixed", description = D_BASE) String base,
            @Input(name = "rates", type = OoaType.DICTIONARY, required = false, visibleWhen = "source=fixed", description = D_RATES) Map<String, Object> fixed,
            @Input(name = "date", type = OoaType.DATETIME, required = false, example = "2026-03-31",
                    description = "Convert at the rates of this past day (ecb source only); on a weekend or holiday the last working day before it is used. Today's rates when empty.") Object date) {
        if (amount == null) throw new ActionException(P + ".NO_AMOUNT", "Give an amount to convert");
        if (decimals < 0 || decimals > 12) throw new ActionException(P + ".BAD_DECIMALS", "decimals must be between 0 and 12");
        Rates r;
        if (date != null && !(date instanceof String ds && ds.isBlank())) {
            String src = source == null || source.isBlank() ? "ecb" : source;
            if (!src.equals("ecb")) throw new ActionException(P + ".DATE_NOT_SUPPORTED", "A past date works only with the ecb source", "Use source ecb, or leave date empty.");
            java.time.LocalDate day;
            try { day = Values.toDateTime(date).toLocalDate(); }
            catch (OoaException e) { throw new ActionException(P + ".BAD_DATE", "Cannot read " + Values.safeRepr(date) + " as a date", "Use a date such as 2026-03-31."); }
            r = ecbOn(ctx, day, url);
        } else r = rates(ctx, source, url, path, base, fixed);
        BigDecimal rate = rate(r, from, to);
        return Out.of("amount", amount.multiply(rate, MC).setScale(decimals, RoundingMode.HALF_UP), "rate", rate.stripTrailingZeros(),
                "rate_date", r.date() == null ? "" : r.date());
    }

    public Map<String, Object> convert(ActionContext ctx, BigDecimal amount, String from, String to, int decimals, String source, String url, String path,
            String base, Map<String, Object> fixed) {
        return convert(ctx, amount, from, to, decimals, source, url, path, base, fixed, null);
    }

    @Action(id = "get_rate", name = "Get exchange rate", category = "Currency", timeoutSeconds = 60, capabilities = "network",
            description = "Return how many units of the target currency one unit of the source currency buys.",
            panel = "from_currency, to_currency | source, url, rates_path, base_currency, rates")
    @Output(name = "rate", type = OoaType.NUMBER, description = "Exchange rate.")
    @Output(name = "rate_date", type = OoaType.STRING, description = "Date of the rates when the source gives one, otherwise empty.")
    public Map<String, Object> getRate(ActionContext ctx,
            @Input(name = "from_currency", type = OoaType.STRING, example = "GBP", description = "Currency you have.") String from,
            @Input(name = "to_currency", type = OoaType.STRING, example = "INR", description = "Currency you want.") String to,
            @Input(name = "source", type = OoaType.STRING, required = false, defaultValue = "\"ecb\"", enumValues = {"ecb", "json", "fixed"}, description = D_SOURCE) String source,
            @Input(name = "url", type = OoaType.STRING, required = false, visibleWhen = "source=json", description = D_URL) String url,
            @Input(name = "rates_path", type = OoaType.STRING, required = false, visibleWhen = "source=json", description = D_PATH) String path,
            @Input(name = "base_currency", type = OoaType.STRING, required = false, visibleWhen = "source=json|fixed", description = D_BASE) String base,
            @Input(name = "rates", type = OoaType.DICTIONARY, required = false, visibleWhen = "source=fixed", description = D_RATES) Map<String, Object> fixed) {
        Rates r = rates(ctx, source, url, path, base, fixed);
        return Out.of("rate", rate(r, from, to).stripTrailingZeros(), "rate_date", r.date() == null ? "" : r.date());
    }

    @Action(id = "list_currencies", name = "List currencies", category = "Currency", timeoutSeconds = 60, capabilities = "network",
            description = "List the currency codes the chosen rate source can convert, with each rate against its base currency.",
            panel = "source, url, rates_path, base_currency, rates")
    @Output(name = "currencies", type = OoaType.LIST, description = "Currency codes, sorted, including the base currency.")
    @Output(name = "rates", type = OoaType.DICTIONARY, description = "Code to rate against the base currency.")
    @Output(name = "base_currency", type = OoaType.STRING, description = "Currency the rates are quoted against.")
    @Output(name = "rate_date", type = OoaType.STRING, description = "Date of the rates when the source gives one, otherwise empty.")
    public Map<String, Object> listCurrencies(ActionContext ctx,
            @Input(name = "source", type = OoaType.STRING, required = false, defaultValue = "\"ecb\"", enumValues = {"ecb", "json", "fixed"}, description = D_SOURCE) String source,
            @Input(name = "url", type = OoaType.STRING, required = false, visibleWhen = "source=json", description = D_URL) String url,
            @Input(name = "rates_path", type = OoaType.STRING, required = false, visibleWhen = "source=json", description = D_PATH) String path,
            @Input(name = "base_currency", type = OoaType.STRING, required = false, visibleWhen = "source=json|fixed", description = D_BASE) String base,
            @Input(name = "rates", type = OoaType.DICTIONARY, required = false, visibleWhen = "source=fixed", description = D_RATES) Map<String, Object> fixed) {
        Rates r = rates(ctx, source, url, path, base, fixed);
        Map<String, Object> all = new TreeMap<>(r.rates());
        all.put(r.base(), BigDecimal.ONE);
        return Out.of("currencies", new ArrayList<Object>(all.keySet()), "rates", new LinkedHashMap<>(all), "base_currency", r.base(),
                "rate_date", r.date() == null ? "" : r.date());
    }
}
