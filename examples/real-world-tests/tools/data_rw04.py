"""Test data for RW04 Order-to-cash: 10 test SKUs (with their public practice-site twins), 10 tagged draft orders,
stock in two warehouses, QA customers in the Salesforce sandbox stand-in."""
import os


STORE_CURRENCY = "USD"
RATES = {"EUR": 1, "USD": 1.08, "GBP": 0.86}     # fixed rates for automated runs (1 EUR = ...)

# key, product id, sku, title, store price (USD), public site, public name, public price, public currency
PRODUCTS = [
    ("P01", 8800000001, "QA-SKU-01", "Sauce Labs Backpack", 29.99, "saucedemo", "Sauce Labs Backpack", 29.99, "USD"),
    ("P02", 8800000002, "QA-SKU-02", "Sauce Labs Bike Light", 9.99, "saucedemo", "Sauce Labs Bike Light", 9.99, "USD"),
    ("P03", 8800000003, "QA-SKU-03", "Sauce Labs Bolt T-Shirt", 15.99, "saucedemo", "Sauce Labs Bolt T-Shirt", 15.99, "USD"),
    ("P04", 8800000004, "QA-SKU-04", "Sauce Labs Fleece Jacket", 45.99, "saucedemo", "Sauce Labs Fleece Jacket", 49.99, "USD"),   # price drift
    ("P05", 8800000005, "QA-SKU-05", "Sauce Labs Onesie", 7.99, "saucedemo", "Sauce Labs Onesie", 7.99, "USD"),
    ("P06", 8800000006, "QA-SKU-06", "Test.allTheThings() T-Shirt (Red)", 15.99, "saucedemo", "Test.allTheThings() T-Shirt (Red)", 15.99, "USD"),
    ("P07", 8800000007, "QA-SKU-07", "A Light in the Attic (paperback)", 65.01, "books", "A Light in the Attic", 51.77, "GBP"),
    ("P08", 8800000008, "QA-SKU-08", "Tipping the Velvet", 67.49, "books", "Tipping the Velvet", 53.74, "GBP"),
    ("P09", 8800000009, "QA-SKU-09", "Soumission", 59.99, "books", "Soumission", 50.10, "GBP"),                                  # price drift
    ("P10", 8800000010, "QA-SKU-10", "The Missing Atlas", 40.00, "books", "The Missing Atlas", 0, "GBP"),                         # not on the site
]
# sku, location, on hand
STOCK = [("QA-SKU-01", "WH-A", 40), ("QA-SKU-02", "WH-A", 25), ("QA-SKU-03", "WH-A", 12), ("QA-SKU-04", "WH-A", 3), ("QA-SKU-05", "WH-A", 20),
         ("QA-SKU-06", "WH-A", 0), ("QA-SKU-07", "WH-B", 6), ("QA-SKU-08", "WH-B", 8), ("QA-SKU-09", "WH-B", 5), ("QA-SKU-10", "WH-B", 0)]
CONFLICT_ONCE = ["QA-SKU-03|WH-A"]    # another channel reserves first: the first reservation try gets 409
CUSTOMERS = [("C1", "ann.lee@ooa-qa.test", "Ann Lee"), ("C2", "raj.iyer@ooa-qa.test", "Raj Iyer"), ("C3", "mia.chen@ooa-qa.test", "Mia Chen"),
             ("C4", "tom.berg@ooa-qa.test", "Tom Berg"), ("C5", "eva.mora@ooa-qa.test", "Eva Mora")]
COUNTRIES = [("US", r"^\d{5}$"), ("GB", r"^[A-Z]{1,2}\d[A-Z\d]? ?\d[A-Z]{2}$"), ("DE", r"^\d{5}$"), ("IN", r"^\d{6}$")]
TAX = 0.08
SHIPPING = 5.00


def order(n, cust, lines, country="US", zip_="10001", currency="USD", status="open", ikey=None, price_override=None):
    prices = {p[2]: p[4] for p in PRODUCTS}
    items = [{"sku": s, "quantity": q, "price": "%.2f" % (price_override if price_override and i == 0 else prices[s])} for i, (s, q) in enumerate(lines)]
    sub = round(sum(q * float(it["price"]) for (s, q), it in zip(lines, items)), 2)
    tax = round(sub * TAX, 2)
    c = next(x for x in CUSTOMERS if x[0] == cust)
    return {"id": 9900000000 + n, "name": "#D%03d" % (100 + n), "email": c[1], "customer_ref": c[0], "currency": currency, "status": status,
            "tags": "ooa-qa, rw04", "note": "", "idempotency_key": ikey or "IK-%04d" % (1000 + n), "line_items": items,
            "shipping_address": {"country": country, "zip": zip_, "city": "QA City", "name": c[2]}, "billing_country": country,
            "subtotal_price": "%.2f" % sub, "total_tax": "%.2f" % tax, "shipping": "%.2f" % SHIPPING, "total_price": "%.2f" % (sub + tax + SHIPPING)}


ORDERS = [
    ("O01", order(1, "C1", [("QA-SKU-01", 2), ("QA-SKU-02", 1)])),                       # normal
    ("O02", order(2, "C1", [("QA-SKU-01", 2), ("QA-SKU-02", 1)], ikey="IK-1001")),       # same idempotency key as O01
    ("O03", order(3, "C2", [("QA-SKU-04", 5)])),                                         # 3 in stock: partial, 2 backordered
    ("O04", order(4, "C3", [("QA-SKU-03", 2), ("QA-SKU-07", 1)])),                       # two warehouses: split shipment (and a 409 on SKU-03)
    ("O05", order(5, "C4", [("QA-SKU-06", 2)])),                                         # none in stock: backorder
    ("O06", order(6, "C5", [("QA-SKU-05", 3)], status="cancelled")),                     # cancelled test order: reserve, then release
    ("O07", order(7, "C2", [("QA-SKU-08", 1)], country="GB", zip_="SW1A 1AA", currency="GBP")),   # currency differs from the store
    ("O08", order(8, "C3", [("QA-SKU-01", 1)], price_override=24.99)),                   # price changed since the cart
    ("O09", order(9, "C4", [("QA-SKU-02", 2)], zip_="ABCDE")),                            # impossible US address
    ("O10", order(10, "C5", [("QA-SKU-08", 1), ("QA-SKU-02", 2)], country="IN", zip_="560001")),   # normal, ships to India
]


def write(w):
    w("RW04/products.csv", [(p[0], p[1], p[2], "%.2f" % p[4], p[5], p[6]) for p in PRODUCTS], ["key", "product_id", "sku", "price", "public_site", "public_name"])
    w("RW04/orders.csv", [(k, o["id"], o["name"], o["customer_ref"]) for k, o in ORDERS], ["key", "order_id", "name", "customer_ref"])
    here = os.path.dirname(os.path.abspath(__file__))     # the demo order-entry app (the legacy revenue system of RW04-B6)
    with open(os.path.join(here, "..", "..", "use-cases", "apps", "order-entry.ps1"), "rb") as fh:
        w("RW04/order-entry.ps1", fh.read())
    w("RW04/stock.csv", STOCK, ["sku", "location", "on_hand"])
    w("RW04/countries.csv", COUNTRIES, ["country", "zip_pattern"])
    w("RW04/customers.csv", CUSTOMERS, ["customer_ref", "email", "name"])
    w("RW04/rates.json", '{"EUR": 1, "USD": 1.08, "GBP": 0.86}\n')


def seed():
    products = [{"id": p[1], "title": p[3], "tags": "ooa-qa, rw04", "status": "active",
                 "variants": [{"sku": p[2], "price": "%.2f" % p[4], "inventory_quantity": next(s[2] for s in STOCK if s[0] == p[2])}]} for p in PRODUCTS]
    inventory = {"%s|%s" % (s, l): {"on_hand": n, "available": n, "reserved": 0, "version": 1} for s, l, n in STOCK}
    accounts = [{"Id": "001QA%010d" % i, "Name": c[2], "External_Id__c": c[0], "Email__c": c[1], "Type": "QA test customer"}
                for i, c in enumerate(CUSTOMERS, 1)]
    return {"shopify": {"products": products, "draft_orders": [o for _, o in ORDERS], "inventory": inventory, "reservations": [],
                        "conflict_once": list(CONFLICT_ONCE)},
            "salesforce": {"Account": accounts}}
