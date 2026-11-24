"""Stand-in for an exchange-rate address (Currency toolkit, source json): GET /rates/<base>.json gives made-up rates."""

RATES = {
    "usd": {"base": "USD", "date": "2026-11-20", "rates": {"EUR": 0.5, "INR": 80.0, "GBP": 0.25}},
    "eur": {"base": "EUR", "date": "2026-11-20", "rates": {"USD": 2.0, "INR": 160.0}},
}


def rates(h, method, path, q):
    key = path.rsplit("/", 1)[-1].replace(".json", "").lower()
    if method != "GET" or key not in RATES:
        return h.send(404, {"error": {"message": "no rates for " + key}})
    return h.send(200, RATES[key])


ROUTES = [("/rates/", rates, None)]
