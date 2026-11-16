"""RW04 Order-to-cash: Shopify dev store -> Salesforce sandbox -> SQL ledger -> Microsoft 365 (child agents and main).

Draft and test orders only: the store stand-in refuses payments, refunds and product changes, and the agents never call them.
Every RW04 agent keys its records by the order key O01 ... O10 (B1 by the product key P01 ... P10).
"""
from common import Child, Main, cred, evidence_var, fault_setup, graph, save_evidence, sf

FOLDER = "RW04 Order-to-cash"
QA_MAILBOX = "orders-qa@ooa-qa.test"
RETRY = {"max": 3, "backoff": "exponential", "delayMs": 1000}
SHOP = "${standin}/admin/api/2024-10"
QUEUE = "rw04-orders"


def shop(path, method="GET", **kw):
    """A Shopify Admin API call (REST). A live development store takes its token in the X-Shopify-Access-Token header."""
    return dict(method=method, url=SHOP + path, bearer_token=cred("shopify"), retry=RETRY, **kw)


def run_file(code, name="evidence.csv"):
    return "${base}/output/${run_id}/" + code + "/" + name


def read_prev(f, code, var, name="evidence.csv", what="evidence"):
    with f.if_({"condition": "ooa.file/exists", "args": {"path": run_file(code, name)}}, desc=f"{code} ran in this run?") as has:
        f.act("ooa.csv/read", desc=f"Read {code} {what}", path=run_file(code, name), out={"table": var})
    with f.else_(has):
        f.comment(f"Running on its own: use the fixture, a saved copy of {code}'s {what} from a checked run.")
        f.act("ooa.csv/read", desc=f"Read {code} {what} (fixture)", path="${base}/data/RW04/fixtures/" + code + "-" + name, out={"table": var})


# ------------------------------------------------------------------ B1
def b1_catalog():
    c = Child("RW04", 1, "Catalog and price reader",
              "Reads the 10 tagged test products of the store, then the same items on the public practice sites (Sauce Demo after a demo "
              "sign-in, Books to Scrape), checks the component behaviour of RPA Challenge and DemoQA, normalizes SKU, price, currency and stock, "
              "confirms each product's identity, converts to EUR, flags price drift and writes the catalogue snapshot. It never changes a product.",
              "10 test SKUs", data_file="RW04/products.csv")
    f = c.f
    for n, t, d in [("rates", "Dictionary", None), ("count_before", "Number", 0), ("count_after", "Dictionary", None), ("cnt", "Dictionary", None),
                    ("sauce", "List", []), ("books", "List", []), ("item", "Dictionary", None), ("public", "Dictionary", {}), ("sauce_note", "String", ""),
                    ("xlsx", "String", ""), ("people", "Table", None), ("row", "Dictionary", None), ("rpa_result", "String", ""), ("demoqa", "Table", None),
                    ("demoqa_rows", "Number", 0), ("prod", "Dictionary", None), ("variant", "Dictionary", None), ("sku", "String", ""), ("price", "Number", 0),
                    ("stock", "Number", 0), ("pprice", "Any", None), ("pcur", "String", ""), ("score", "Number", 0), ("found", "Boolean", False),
                    ("store_eur", "Number", 0), ("public_eur", "Number", 0), ("drift", "Number", 0), ("source", "String", ""), ("flag", "String", "")]:
        f.var(n, t, "local", d)
    f.var("demo_password", "Credential", "input", None, "Sauce Demo's public demo password from the vault (rw_qa_standins/saucedemo/password; a person enters it there).")
    f.var("drift_tolerance", "Number", "input", 1, "Price drift (per cent) above which a product is flagged.")
    evidence_var(f, ["key", "sku", "title", "store_price_usd", "stock", "public_site", "public_name", "public_price", "public_currency",
                     "store_eur", "public_eur", "drift_pct", "flag", "identity_score", "source"])
    c.start()
    with c.prepare("Count the store's products (to prove no writes), then read the public practice sites once each (browser) and check RPA Challenge and DemoQA."):
        fault_setup(f, "shopify")
        f.act("ooa.json/read_file", desc="Fixed exchange rates", path="${base}/data/RW04/rates.json", out={"value": "rates"})
        f.act("ooa.rest/request", desc="Products in the store before (no writes allowed)", out={"json": "cnt"}, **shop("/products/count.json"))
        f.expr("count_before", "toNumber($cnt.count$)", desc="Count before")
        with f.try_(desc="Sauce Demo: demo sign-in with the demo password from the vault") as t1:
            with f.if_("isEmpty($demo_password$)", desc="No demo password given?"):
                f.act("core/throw", desc="Skip Sauce Demo", code="SETUP.NO_DEMO_PASSWORD", message="no demo password: give demo_password (vault rw_qa_standins/saucedemo/password)")
            f.act("ooa.browser/open", desc="Open Sauce Demo", url="https://www.saucedemo.com/", headless={"expr": "not $watch$"}, engine="playwright", keep_open=False)
            f.act("ooa.browser/type_text", desc="Demo user", selector="#user-name", text="standard_user")
            f.act("ooa.browser/type_text", desc="Demo password (vault; entered there by a person)", selector="#password", text="$demo_password$")
            f.act("ooa.browser/click", desc="Sign in", selector="#login-button")
            f.act("ooa.browser/wait_for", desc="Wait for the product list", selector=".inventory_list", state="visible")
            f.act("ooa.browser/run_javascript", desc="Read names and prices",
                  script="Array.from(document.querySelectorAll('.inventory_item')).map(x => ({name: x.querySelector('.inventory_item_name').textContent.trim(), "
                         "price: Number(x.querySelector('.inventory_item_price').textContent.replace('$', ''))}))", out={"result": "sauce"})
            f.act("ooa.browser/click", desc="Open the menu", selector="#react-burger-menu-btn")
            f.act("ooa.browser/click", desc="Sign out", selector="#logout_sidebar_link")
            f.act("ooa.browser/close", desc="Close the browser")
            f.set("sauce_note", "read ${len($sauce$)} products", desc="Note")
        with f.catch(t1, message_to="sauce_note"):
            f.comment("No demo password in the vault yet, or the site is down: the Sauce Demo items go to the exception list below.")
            f.act("ooa.browser/close", desc="Close the browser if open", cont=True)
            f.act("ooa.log/write", desc="Note it", path="$log_file$", level="WARN", message="Sauce Demo not read: ${sauce_note}")
        with f.loop(desc="Sauce Demo prices by name (USD)", iterator="list", over="$sauce$", **{"as": "item"}):
            f.act("ooa.dictionary/put", desc="Name -> price", dictionary="$public$", key="$item.name$", value={"expr": "[$item.price$, 'USD', 'https://www.saucedemo.com/inventory.html']"},
                  out={"dictionary": "public"})
        f.act("ooa.browser/open", desc="Open Books to Scrape (read only)", url="https://books.toscrape.com/", headless={"expr": "not $watch$"}, engine="playwright",
              keep_open=False, retry=RETRY)
        f.act("ooa.browser/run_javascript", desc="Read the first page's titles and prices (GBP)",
              script="Array.from(document.querySelectorAll('article.product_pod')).map(x => ({name: x.querySelector('h3 a').getAttribute('title'), "
                     "price: Number(x.querySelector('.price_color').textContent.replace(/[^0-9.]/g, ''))}))", out={"result": "books"})
        f.act("ooa.browser/close", desc="Close the browser")
        with f.loop(desc="Book prices by title (GBP)", iterator="list", over="$books$", **{"as": "item"}):
            f.act("ooa.dictionary/put", desc="Title -> price", dictionary="$public$", key="$item.name$", value={"expr": "[$item.price$, 'GBP', 'https://books.toscrape.com/']"},
                  out={"dictionary": "public"})
        with f.try_(desc="RPA Challenge: the demo form, ten rounds (component behaviour: fields move every round)") as t2:
            f.act("ooa.browser/open", desc="Open RPA Challenge", url="https://rpachallenge.com/", headless={"expr": "not $watch$"}, engine="playwright", keep_open=False)
            f.act("ooa.browser/download", desc="Download the challenge sheet", selector="a[href*='challenge.xlsx']", folder="$out_dir$", timeout_ms=30000, out={"path": "xlsx"})
            f.act("ooa.excel/open", desc="Open it", path="$xlsx$", session="challenge", header=True)
            f.act("ooa.excel/get_all_cells", desc="Read the ten people", session="challenge", out={"table": "people"})
            f.act("ooa.excel/close", desc="Close it", session="challenge", save=False)
            f.act("ooa.browser/click", desc="Start", selector="xpath=//button[normalize-space()='Start']")
            with f.loop(desc="Each round", iterator="table_rows", over="$people$", **{"as": "row"}):
                with f.if_({"op": "notEmpty", "left": {"expr": "$row[\"First Name\"]$"}}, desc="A person on this row?"):
                    for label, col in [("FirstName", "First Name"), ("LastName", "Last Name "), ("CompanyName", "Company Name"), ("Role", "Role in Company"),
                                       ("Address", "Address"), ("Email", "Email"), ("Phone", "Phone Number")]:
                        f.act("ooa.browser/type_text", desc="Fill " + col.strip(), selector=f"input[ng-reflect-name='label{label}']", text={"expr": f"toString($row[\"{col}\"]$)"})
                    f.act("ooa.browser/click", desc="Submit the round", selector="input[type='submit']")
            f.act("ooa.browser/get_text", desc="Read the result", selector=".message2", out={"text": "rpa_result"})
            f.act("ooa.browser/close", desc="Close the browser")
        with f.catch(t2, message_to="rpa_result"):
            f.act("ooa.browser/close", desc="Close the browser if open", cont=True)
        with f.try_(desc="DemoQA web table: read only") as t3:
            f.act("ooa.browser/open", desc="Open DemoQA web tables", url="https://demoqa.com/webtables", headless={"expr": "not $watch$"}, engine="playwright", keep_open=False)
            f.act("ooa.browser/extract_table", desc="Read the table", selector="table", out={"table": "demoqa"})
            f.act("ooa.datatable/row_count", desc="Rows shown", table="$demoqa$", out={"count": "demoqa_rows"})
            f.act("ooa.browser/close", desc="Close the browser")
        with f.catch(t3):
            f.act("ooa.browser/close", desc="Close the browser if open", cont=True)
        f.act("ooa.log/write", desc="Public sites read", path="$log_file$",
              message="Sauce Demo: ${sauce_note}; Books: ${len($books$)}; RPA Challenge: ${rpa_result}; DemoQA rows: ${demoqa_rows}")
    with c.records("Per SKU: product detail, normalize, public twin, identity, EUR prices, drift, snapshot row.",
                   summary="${sku} ${prod.title}\nStore USD ${price} | public ${pcur} ${pprice}\nDrift ${drift}% ${flag}"):
        f.act("ooa.rest/request", desc="2. Product detail from the store", out={"json": "prod"}, **shop("/products/${rec.product_id}.json"))
        f.expr("variant", "first($prod.product.variants$)", desc="Its variant")
        f.expr("sku", "upper(trim($variant.sku$))", desc="3. Normalize the SKU")
        f.expr("price", "round(toNumber($variant.price$), 2)", desc="3. Normalize the price (USD)")
        f.expr("stock", "toNumber($variant.inventory_quantity$)", desc="3. Stock")
        with f.if_({"op": "ne", "left": "$sku$", "right": "$rec.sku$"}, desc="5. SKU differs from the test list?"):
            f.act("core/throw", desc="Identity not confirmed", code="ROUTE.IDENTITY", message="Product ${rec.product_id} has SKU ${sku}, expected ${rec.sku}")
        f.expr("pprice", "get($public$, $rec.public_name$)", desc="4. Public twin's price")
        with f.if_("isEmpty($pprice$)", desc="Not on the public site?"):
            f.act("core/throw", desc="Identity not confirmed", code="ROUTE.NOT_ON_SITE", message="${rec.public_name} was not found on ${rec.public_site}")
        f.act("ooa.fuzzy/best_match", desc="5. Same product? (title against the public name)", text="$prod.product.title$", candidates=["$rec.public_name$"],
              threshold=80, algorithm="token_set", out={"found": "found", "score": "score"})
        with f.if_("not $found$", desc="Titles too different?"):
            f.act("core/throw", desc="Identity not confirmed", code="ROUTE.IDENTITY", message="${prod.product.title} does not look like ${rec.public_name} (${score})")
        f.expr("pcur", "$pprice$[1]", desc="Public currency")
        f.expr("source", "$pprice$[2]", desc="6. Capture the source")
        f.expr("pprice", "$pprice$[0]", desc="Public price")
        f.act("ooa.currency/convert", desc="Store price in EUR", amount="$price$", from_currency="USD", to_currency="EUR", source="fixed", base_currency="EUR",
              rates="$rates$", out={"amount": "store_eur"})
        f.act("ooa.currency/convert", desc="Public price in EUR", amount="$pprice$", from_currency="$pcur$", to_currency="EUR", source="fixed", base_currency="EUR",
              rates="$rates$", out={"amount": "public_eur"})
        f.expr("drift", "round(($store_eur$ - $public_eur$) / $public_eur$ * 100, 2)", desc="7. Price drift (%)")
        f.expr("flag", "if(abs($drift$) > $drift_tolerance$, 'PRICE DRIFT', 'ok')", desc="Flag it")
        with f.if_({"op": "ne", "left": "$flag$", "right": "ok"}, desc="Drift?"):
            f.act("ooa.log/write", desc="Log the drift", path="$log_file$", level="WARN", message="${sku}: store ${price} USD vs public ${pprice} ${pcur} (${drift}%)")
        f.act("ooa.datatable/add_row", desc="8. Snapshot row", table="$evidence$",
              values={"key": "$rec.key$", "sku": "$sku$", "title": "$prod.product.title$", "store_price_usd": "$price$", "stock": "$stock$",
                      "public_site": "$rec.public_site$", "public_name": "$rec.public_name$", "public_price": "$pprice$", "public_currency": "$pcur$",
                      "store_eur": "$store_eur$", "public_eur": "$public_eur$", "drift_pct": "$drift$", "flag": "$flag$", "identity_score": "$score$",
                      "source": "$source$"}, out={"table": "evidence"})
        f.set("rec_note", "${flag} ${drift}%", desc="Note for the results")
    with c.finish("Prove no product was written, then save the snapshot (CSV and JSON)."):
        f.act("ooa.rest/request", desc="9. Products in the store after", out={"json": "count_after"}, **shop("/products/count.json"))
        with f.if_("toNumber($count_after.count$) != $count_before$", desc="Product count changed?"):
            f.act("core/throw", desc="Stop: a product write happened", code="VERIFY.PRODUCT_WRITE", message="The store had ${count_before} products and now ${count_after.count}")
        save_evidence(f)
        f.act("ooa.datatable/to_records", desc="Snapshot as records", table="$evidence$", out={"records": "sauce"})
        f.act("ooa.json/write_file", desc="Catalogue snapshot (JSON)", path="${out_dir}/catalog-snapshot.json", value="$sauce$", pretty=True)
    return c


# ------------------------------------------------------------------ B2
def b2_orders():
    c = Child("RW04", 2, "Order intake and risk",
              "Reads the 10 tagged draft orders, checks the customer is a QA customer (also in the Salesforce sandbox), the quantities and the "
              "ship-to allow-list, recomputes the totals, catches duplicate idempotency keys, impossible addresses, a currency other than the "
              "store's and prices changed since the cart, scores the fixed risk rules, then holds or accepts each order with its reason and "
              "an idempotency token, and queues the accepted ones.",
              "10 tagged draft orders", data_file="RW04/orders.csv")
    f = c.f
    for n, t, d in [("products", "Table", None), ("countries", "Table", None), ("d", "Dictionary", None), ("o", "Dictionary", None), ("acct", "Table", None),
                    ("acct_n", "Number", 0), ("ln", "Dictionary", None), ("cat_price", "String", ""), ("pattern", "String", ""), ("sub", "Number", 0),
                    ("tax", "Number", 0), ("total", "Number", 0), ("hold", "String", ""), ("risk", "Number", 0), ("keys", "List", []), ("carts", "List", []),
                    ("cart_hash", "String", ""), ("token", "String", ""), ("item", "String", ""), ("decision", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "order_id", "name", "customer_ref", "email", "currency", "lines", "subtotal", "tax", "shipping", "total", "risk", "status",
                     "idempotency_key", "token", "queue_item"])
    c.start()
    with c.prepare("Read the store catalogue (prices), the ship-to allow-list with its postcode rules."):
        fault_setup(f, "shopify")
        f.act("ooa.csv/read", desc="Read the test products", path="${base}/data/RW04/products.csv", out={"table": "products"})
        f.act("ooa.csv/read", desc="Ship-to allow-list and postcode rules", path="${base}/data/RW04/countries.csv", out={"table": "countries"})
    with c.records("Per draft order: QA customer, CRM account, quantities, ship-to, totals, duplicates, address, currency, prices, risk, hold or accept.",
                   summary="${rec.name} ${o.email}\n${o.currency} ${total}  risk ${risk}\n${decision}"):
        f.act("ooa.rest/request", desc="1. Read the draft order", out={"json": "d"}, **shop("/draft_orders/${rec.order_id}.json"))
        f.set("o", "$d.draft_order$", desc="The order")
        f.set("hold", "", desc="No hold reason yet")
        with f.if_("not endsWith(lower($o.email$), '@ooa-qa.test')", desc="2. Not a QA customer?"):
            f.act("core/throw", desc="Reject: not a test customer", code="ROUTE.NOT_QA_CUSTOMER", message="${rec.name}: ${o.email} is not a QA customer")
        f.act("ooa.salesforce/query", desc="2. The customer's account in the Salesforce sandbox",
              soql="SELECT Id, Name FROM Account WHERE External_Id__c = '${o.customer_ref}'", out={"table": "acct", "count": "acct_n"}, **sf())
        with f.if_("$acct_n$ == 0", desc="No CRM account?"):
            f.set("hold", "no CRM account for ${o.customer_ref}", desc="Hold reason")
        f.set("sub", 0, desc="Start the subtotal")
        with f.loop(desc="3. Each line: quantity, catalogue price", iterator="list", over="$o.line_items$", **{"as": "ln"}):
            with f.if_("toNumber($ln.quantity$) <= 0 or toNumber($ln.quantity$) > 100", desc="Quantity out of bounds?"):
                f.set("hold", "quantity ${ln.quantity} of ${ln.sku} out of bounds", desc="Hold reason")
            f.act("ooa.datatable/lookup", desc="Catalogue price of the SKU", table="$products$", column="sku", value="$ln.sku$", return_column="price", if_not_found="",
                  out={"value": "cat_price"})
            with f.if_("isEmpty($cat_price$)", desc="SKU not a test SKU?") as known:
                f.set("hold", "${ln.sku} is not a test SKU", desc="Hold reason")
            with f.elif_(known, "abs(toNumber($ln.price$) - toNumber($cat_price$)) > 0.001"):
                f.set("hold", "price of ${ln.sku} changed since the cart (${ln.price} vs ${cat_price})", desc="Hold reason")
            f.expr("sub", "$sub$ + toNumber($ln.quantity$) * toNumber($ln.price$)", desc="4. Add the line")
        f.act("ooa.datatable/lookup", desc="3. Ship-to country allowed? (its postcode rule)", table="$countries$", column="country", value="$o.shipping_address.country$",
              return_column="zip_pattern", if_not_found="", out={"value": "pattern"})
        with f.if_("isEmpty($pattern$)", desc="Country not on the allow-list?") as cty:
            f.set("hold", "ship-to ${o.shipping_address.country} not allowed", desc="Hold reason")
        with f.elif_(cty, "not regexMatch($o.shipping_address.zip$, $pattern$)"):
            f.set("hold", "impossible address: postcode ${o.shipping_address.zip} for ${o.shipping_address.country}", desc="7. Hold reason")
        f.expr("sub", "round($sub$, 2)", desc="Subtotal")
        f.expr("tax", "round($sub$ * 0.08, 2)", desc="4. Tax (8%)")
        f.expr("total", "round($sub$ + $tax$ + toNumber($o.shipping$), 2)", desc="4. Total")
        with f.if_("abs($total$ - toNumber($o.total_price$)) > 0.01", desc="Order total differs?"):
            f.set("hold", "total ${o.total_price} differs from ${total}", desc="Hold reason")
        with f.if_({"op": "ne", "left": "$o.currency$", "right": "USD"}, desc="Currency other than the store's?"):
            f.set("hold", "currency ${o.currency} differs from the store currency USD", desc="Hold reason")
        f.act("ooa.crypto/hash_text", desc="5. Hash of customer and lines (duplicate check)", text="${o.email}|${jsonStringify($o.line_items$)}", out={"hash": "cart_hash"})
        with f.if_({"any": [{"op": "contains", "left": "$keys$", "right": "$o.idempotency_key$"}, {"op": "contains", "left": "$carts$", "right": "$cart_hash$"}]},
                   desc="5. Same idempotency key or same cart seen?"):
            f.set("hold", "duplicate of an earlier order (key ${o.idempotency_key})", desc="Hold reason")
        f.act("ooa.list/add_item", desc="Remember the key", list="$keys$", item="$o.idempotency_key$", out={"list": "keys"})
        f.act("ooa.list/add_item", desc="Remember the cart", list="$carts$", item="$cart_hash$", out={"list": "carts"})
        f.expr("risk", "if($total$ > 500, 40, 0) + if($o.billing_country$ != $o.shipping_address.country$, 30, 0) + if($acct_n$ == 0, 30, 0)",
               desc="6. Fixed risk rules (value, country mismatch, unknown account)")
        with f.if_("$risk$ >= 60", desc="High risk?"):
            f.set("hold", "risk score ${risk}", desc="Hold reason")
        with f.if_("not isEmpty($hold$)", desc="8. Hold?") as hd:
            f.act("ooa.rest/request", desc="9. Record the reason on the draft order", json={"draft_order": {"note": "OOA-QA ${run_id} hold: ${hold}", "tags": "ooa-qa, rw04, hold"}},
                  **shop("/draft_orders/${rec.order_id}.json", "PUT"))
            f.set("decision", "hold: ${hold}", desc="Decision")
            f.act("core/throw", desc="Hold the order", code="ROUTE.HOLD", message="${rec.name}: ${hold}")
        f.act("ooa.crypto/hash_text", desc="10. Issue the idempotency token", text="${run_id}|${o.id}|${o.idempotency_key}", out={"hash": "token"})
        f.act("ooa.workload/add_item", desc="Queue the accepted order", queue=QUEUE, reference="${run_id}-${rec.key}", if_exists="skip",
              data={"key": "$rec.key$", "order_id": "$o.id$", "token": "$token$"}, tags=["rw04"], out={"item_id": "item"})
        f.act("ooa.rest/request", desc="9. Record the acceptance on the draft order", json={"draft_order": {"note": "OOA-QA ${run_id} accepted", "tags": "ooa-qa, rw04, accepted"}},
              **shop("/draft_orders/${rec.order_id}.json", "PUT"))
        f.set("decision", "accepted", desc="Decision")
        f.act("ooa.datatable/add_row", desc="Accepted order row", table="$evidence$",
              values={"key": "$rec.key$", "order_id": "$o.id$", "name": "$o.name$", "customer_ref": "$o.customer_ref$", "email": "$o.email$", "currency": "$o.currency$",
                      "lines": {"expr": "jsonStringify($o.line_items$)"}, "subtotal": "$sub$", "tax": "$tax$", "shipping": "$o.shipping$", "total": "$total$",
                      "risk": "$risk$", "status": "$o.status$", "idempotency_key": "$o.idempotency_key$", "token": {"expr": "substring($token$, 0, 16)"},
                      "queue_item": "$item$"}, out={"table": "evidence"})
        f.set("rec_note", "accepted (risk ${risk})", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B3
def b3_inventory():
    c = Child("RW04", 3, "Inventory reservation",
              "Loads the stock into the QA ledger database, reserves each accepted order line in the store with the stock record's version "
              "(re-reading and retrying on a 409 conflict) and in the ledger the same way, backorders what is not in stock, works out split "
              "shipments, releases the reservation of a cancelled test order, reconciles ledger and store stock, checks the bounds, emits an "
              "event to Kafka and checkpoints the queue item.",
              "10 accepted orders (the accepted ones)")
    f = c.f
    for n, t, d in [("lines", "List", None), ("ln", "Dictionary", None), ("lvl", "Dictionary", None), ("level", "Dictionary", None), ("res", "Dictionary", None),
                    ("row", "Table", None), ("loc", "String", ""), ("ver", "Number", 0), ("avail", "Number", 0), ("take", "Number", 0), ("back", "Number", 0),
                    ("backordered", "Number", 0), ("reserved", "Number", 0), ("locs", "List", []), ("n", "Number", 0), ("conflicts", "Number", 0),
                    ("ok", "Boolean", False), ("tries", "Number", 0), ("err_text", "String", ""), ("state", "String", ""), ("ev", "Number", 0),
                    ("check", "Table", None), ("bad", "Number", 0), ("item", "Dictionary", None), ("db", "String", "")]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "order_id", "reserved", "backordered", "shipments", "locations", "conflicts", "state", "event_offset"])
    c.start()
    with c.prepare("Read the accepted orders, load the stock into the QA ledger (embedded H2) with a version per stock record."):
        fault_setup(f, "shopify")
        read_prev(f, "RW04-B2", "records")
        f.set("db", "${out_dir}/ledger", desc="Ledger database file of this run")
        f.act("ooa.database/connect", desc="Open the QA ledger (embedded H2; live: the SQL Server QA staging database)", database_type="h2", file="$db$", session="ledger")
        f.act("ooa.database/execute", desc="Drop an older stock table", sql="DROP TABLE IF EXISTS stock", session="ledger")
        f.act("ooa.database/execute", desc="Load the stock", session="ledger",
              sql="CREATE TABLE stock(sku VARCHAR(20), location VARCHAR(10), on_hand INT, reserved INT DEFAULT 0, version INT DEFAULT 1) AS "
                  "SELECT sku, location, CAST(on_hand AS INT), 0, 1 FROM CSVREAD('${base}/data/RW04/stock.csv')")
        f.act("ooa.database/execute", desc="Drop an older reservation ledger", sql="DROP TABLE IF EXISTS reservation", session="ledger")
        f.act("ooa.database/execute", desc="Create the reservation ledger", session="ledger",
              sql="CREATE TABLE reservation(order_key VARCHAR(10), sku VARCHAR(20), location VARCHAR(10), qty INT, state VARCHAR(20))")
    with c.records("Per order: reserve each line with versions (409: re-read, retry), backorder, split, release when cancelled, reconcile, bounds, event, checkpoint.",
                   summary="${rec.name}: reserved ${reserved}, backordered ${backordered}\nShipments ${len($locs$)}  conflicts ${conflicts}\n${state}"):
        f.expr("lines", "jsonParse($rec.lines$)", desc="The order lines")
        for v, val in [("reserved", 0), ("backordered", 0), ("conflicts", 0), ("locs", [])]:
            f.set(v, val, desc="Start: " + v)
        with f.loop(desc="Each line", iterator="list", over="$lines$", **{"as": "ln"}):
            f.act("ooa.database/query", desc="1. Read the ledger's stock record", session="ledger", parameters=["$ln.sku$"], out={"table": "row"},
                  sql='SELECT location AS "location", on_hand - reserved AS "avail", version AS "version" FROM stock WHERE sku = ?')
            f.act("ooa.datatable/get_value", desc="Its location", table="$row$", row=0, column="location", out={"value": "loc"})
            f.act("ooa.datatable/get_value", desc="Its version", table="$row$", row=0, column="version", out={"value": "ver"})
            f.set("ok", False, desc="Not reserved yet")
            f.set("tries", 0, desc="Tries")
            with f.loop(desc="2.-3. Reserve with the version; on 409 re-read and try again (at most 3 times)", iterator="while", condition="not $ok$ and $tries$ < 3", max_iterations=4):
                f.expr("tries", "$tries$ + 1", desc="Count the try")
                f.act("ooa.rest/request", desc="Read the store's stock record (version)", out={"json": "level"}, **shop("/inventory_levels.json", query={"sku": "$ln.sku$"}))
                f.expr("lvl", "first($level.inventory_levels$)", desc="The record")
                f.expr("take", "min(toNumber($ln.quantity$), toNumber($lvl.available$))", desc="4. What can be reserved now")
                with f.if_("$take$ <= 0", desc="Nothing in stock?") as none:
                    f.set("ok", True, desc="Nothing to reserve: all backordered")
                with f.else_(none):
                    with f.try_(desc="Reserve in the store") as tr:
                        f.act("ooa.rest/request", desc="Reserve with the version", json={"sku": "$ln.sku$", "location": "$lvl.location$", "quantity": "$take$",
                                                                                         "version": "$lvl.version$", "order": "$rec.name$", "run": "$run_id$"},
                              out={"json": "res"}, **dict(shop("/inventory/reserve.json", "POST"), retry=None))
                        f.set("ok", True, desc="Reserved")
                    with f.catch(tr, message_to="err_text"):
                        f.expr("conflicts", "$conflicts$ + 1", desc="3. A version conflict (409): count it, re-read and try again")
                        f.act("ooa.log/write", desc="Log the conflict", path="$log_file$", level="WARN", message="${rec.name} ${ln.sku}: ${err_text}")
            with f.if_("not $ok$", desc="Still not reserved?"):
                f.act("core/throw", desc="Give up on this order", code="INVENTORY.CONFLICT", message="${rec.name} ${ln.sku}: stock kept changing")
            f.act("ooa.database/execute", desc="2. Reserve in the ledger only if its version is still the one read (optimistic)", session="ledger",
                  parameters=["$take$", "$ln.sku$", "$loc$", "$ver$"],
                  sql="UPDATE stock SET reserved = reserved + ?, version = version + 1 WHERE sku = ? AND location = ? AND version = ?",
                  out={"rows_affected": "n"})
            with f.if_("$n$ == 0", desc="Ledger record changed meanwhile?"):
                f.act("core/throw", desc="Stop: ledger conflict", code="INVENTORY.LEDGER_CONFLICT", message="${rec.name} ${ln.sku}: the ledger record changed (version ${ver})")
            f.expr("back", "toNumber($ln.quantity$) - $take$", desc="4. Backorder the rest")
            f.act("ooa.database/execute", desc="Ledger row", session="ledger", parameters=["$rec.key$", "$ln.sku$", "$loc$", "$take$", {"expr": "if($back$ > 0, 'partial', 'reserved')"}],
                  sql="INSERT INTO reservation VALUES (?, ?, ?, ?, ?)")
            f.expr("reserved", "$reserved$ + $take$", desc="Add up")
            f.expr("backordered", "$backordered$ + $back$", desc="Add up")
            with f.if_("$take$ > 0 and not contains($locs$, $loc$)", desc="5. A new shipping location?"):
                f.act("ooa.list/add_item", desc="Add the location", list="$locs$", item="$loc$", out={"list": "locs"})
        f.expr("state", "if($backordered$ > 0 and $reserved$ == 0, 'backorder', if($backordered$ > 0, 'partial', if(len($locs$) > 1, 'split shipment', 'reserved')))",
               desc="The order's state")
        with f.if_({"op": "eq", "left": "$rec.status$", "right": "cancelled"}, desc="6. Cancelled test order: release (compensation)"):
            with f.loop(desc="Each reserved line", iterator="list", over="$lines$", **{"as": "ln"}):
                f.act("ooa.rest/request", desc="Read the version", out={"json": "level"}, **shop("/inventory_levels.json", query={"sku": "$ln.sku$"}))
                f.expr("lvl", "first($level.inventory_levels$)", desc="The record")
                f.act("ooa.rest/request", desc="Release in the store", json={"sku": "$ln.sku$", "location": "$lvl.location$", "quantity": "$ln.quantity$",
                                                                             "version": "$lvl.version$", "order": "$rec.name$", "run": "$run_id$"},
                      **shop("/inventory/release.json", "POST"))
                f.act("ooa.database/execute", desc="Release in the ledger", session="ledger", parameters=["$ln.quantity$", "$ln.sku$"],
                      sql="UPDATE stock SET reserved = reserved - ?, version = version + 1 WHERE sku = ?")
            f.act("ooa.database/execute", desc="Mark the ledger rows released", session="ledger", parameters=["$rec.key$"],
                  sql="UPDATE reservation SET state = 'released' WHERE order_key = ?")
            f.set("state", "cancelled, released", desc="State")
        f.act("ooa.database/query", desc="7.-8. Ledger stock of this order's SKUs (bounds)", session="ledger", out={"table": "check"},
              sql='SELECT sku AS "sku", on_hand - reserved AS "avail", reserved AS "reserved" FROM stock WHERE reserved < 0 OR reserved > on_hand')
        f.act("ooa.datatable/row_count", desc="Out of bounds rows", table="$check$", out={"count": "bad"})
        with f.if_("$bad$ > 0", desc="Reserved below zero or above stock?"):
            f.act("core/throw", desc="Stop", code="INVENTORY.BOUNDS", message="${bad} stock record(s) out of bounds after ${rec.name}")
        with f.loop(desc="7. Reconcile ledger and store stock per SKU", iterator="list", over="$lines$", **{"as": "ln"}):
            f.act("ooa.rest/request", desc="Store stock", out={"json": "level"}, **shop("/inventory_levels.json", query={"sku": "$ln.sku$"}))
            f.act("ooa.database/query", desc="Ledger stock", session="ledger", parameters=["$ln.sku$"], out={"table": "row"},
                  sql='SELECT on_hand - reserved AS "avail" FROM stock WHERE sku = ?')
            f.act("ooa.datatable/get_value", desc="Ledger available", table="$row$", row=0, column="avail", out={"value": "avail"})
            with f.if_("toNumber($avail$) != toNumber(first($level.inventory_levels$).available)", desc="Ledger and store differ?"):
                f.act("core/throw", desc="Stop", code="INVENTORY.MISMATCH", message="${ln.sku}: ledger ${avail}, store ${first($level.inventory_levels$).available}")
        f.act("ooa.kafka/produce_message", desc="9. Emit the reservation event", base_url="${standin}/kafka", cluster_id="qa-cluster", access_token=cred("kafka"),
              topic="ooa-qa.orders.reserved", key="$rec.name$", value={"order": "$rec.name$", "state": "$state$", "reserved": "$reserved$", "backordered": "$backordered$",
                                                                       "run": "$run_id$"}, retry=RETRY, out={"offset": "ev"})
        with f.if_("not isEmpty($rec.queue_item$)", desc="10. Checkpoint the queue item"):
            f.act("ooa.workload/complete", desc="Mark the queued order done", queue=QUEUE, item_id="$rec.queue_item$", result={"state": "$state$"}, cont=True)
        f.act("ooa.datatable/add_row", desc="10. Checkpoint row", table="$evidence$",
              values={"key": "$rec.key$", "order_id": "$rec.order_id$", "reserved": "$reserved$", "backordered": "$backordered$", "shipments": {"expr": "len($locs$)"},
                      "locations": {"expr": "join($locs$, ';')"}, "conflicts": "$conflicts$", "state": "$state$", "event_offset": "$ev$"}, out={"table": "evidence"})
        save_evidence(f)
        f.set("rec_note", "$state$", desc="Note for the results")
    with c.finish():
        f.act("ooa.database/disconnect", desc="Close the ledger", session="ledger")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B4
def b4_crm():
    c = Child("RW04", 4, "Salesforce CRM updater",
              "For every order: checks the CRM host is the sandbox, finds the account by its synthetic external id, links the order (upsert by "
              "the order's external id, safe to repeat), writes the test status and delivery notes, opens one case per exception (never a "
              "duplicate), reads the order back to check the links and records the ids.",
              "10 CRM order records", data_file="RW04/orders.csv")
    f = c.f
    for n, t, d in [("b2", "Table", None), ("b2r", "Table", None), ("b3", "Table", None), ("acct", "Table", None), ("acct_id", "String", ""),
                    ("email", "String", ""), ("cust", "String", ""), ("state", "String", ""), ("bstatus", "String", ""), ("bnote", "String", ""),
                    ("order_sf", "String", ""), ("created", "Boolean", False), ("case_id", "String", ""), ("cases", "Table", None), ("nc", "Number", 0),
                    ("back", "Dictionary", None), ("host", "String", ""), ("notes", "String", ""), ("customers", "Table", None)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "account_id", "order_record", "created", "status", "case_id", "notes"])
    c.start()
    with c.prepare("Check the CRM address is the sandbox (never production) and read what intake and reservation found."):
        fault_setup(f, "salesforce")
        f.expr("host", "regexExtract($standin$, '//([^/]+)', 1)", desc="2. CRM host")
        with f.if_("not (startsWith($host$, '127.0.0.1') or contains($host$, '.sandbox.') or startsWith($host$, 'test.salesforce.com'))", desc="Production host?"):
            f.act("core/throw", desc="Stop: sandbox only", code="CRM.NOT_SANDBOX", message="${host} is not a Salesforce sandbox: no production changes")
        read_prev(f, "RW04-B2", "b2")
        read_prev(f, "RW04-B2", "b2r", "results.csv", "results")
        read_prev(f, "RW04-B3", "b3")
        f.act("ooa.csv/read", desc="QA customers", path="${base}/data/RW04/customers.csv", out={"table": "customers"})
    with c.records("Per order: account, link the order, status and notes, a case for an exception (deduplicated), read back, ids.",
                   summary="${rec.name}: account ${acct_id}\nOrder ${order_sf} (${state})\nCase: ${case_id}"):
        f.set("cust", "$rec.customer_ref$", desc="Customer of the order (synthetic external id)")
        f.act("ooa.salesforce/query", desc="1. Account by synthetic external id", soql="SELECT Id, Name FROM Account WHERE External_Id__c = '${cust}'",
              out={"table": "acct"}, retry=RETRY, **sf())
        f.act("ooa.datatable/get_value", desc="Account id", table="$acct$", row=0, column="Id", out={"value": "acct_id"})
        f.act("ooa.datatable/lookup", desc="Reservation state", table="$b3$", column="key", value="$rec.key$", return_column="state", if_not_found="", out={"value": "state"})
        f.act("ooa.datatable/lookup", desc="Intake result", table="$b2r$", column="key", value="$rec.key$", return_column="status", if_not_found="", out={"value": "bstatus"})
        f.act("ooa.datatable/lookup", desc="Intake note", table="$b2r$", column="key", value="$rec.key$", return_column="note", if_not_found="", out={"value": "bnote"})
        f.expr("state", "if($bstatus$ == 'exception', 'held', if(isEmpty($state$), 'not reserved', $state$))", desc="4. Test status")
        f.expr("notes", "if($state$ == 'held', $bnote$, 'Delivery: ' + $state$)", desc="4. Delivery notes")
        f.act("ooa.salesforce/upsert_by_external_id", desc="3. Link the order (upsert: safe to repeat)", object="Order__c", external_id_field="Order_Ref__c",
              external_id_value="OOA-QA-${run_id}-${rec.key}", fields={"Account__c": "$acct_id$", "Name": "$rec.name$", "Status__c": "$state$",
                                                                     "Delivery_Notes__c": "$notes$", "QA_Run__c": "$run_id$"},
              retry=RETRY, out={"id": "order_sf", "created": "created"}, **sf())
        f.set("case_id", "", desc="No case yet")
        with f.if_({"any": [{"op": "eq", "left": "$state$", "right": "held"}, {"op": "eq", "left": "$state$", "right": "backorder"},
                            {"op": "eq", "left": "$state$", "right": "partial"}]}, desc="5. An exception the service team must see?"):
            f.act("ooa.salesforce/query", desc="6. A case for this order already?", soql="SELECT Id FROM Case WHERE Subject = 'OOA-QA ${run_id} ${rec.name}'",
                  out={"table": "cases", "count": "nc"}, **sf())
            with f.if_("$nc$ == 0", desc="None yet") as nocase:
                f.act("ooa.salesforce/create_record", desc="5. Open the case", object="Case",
                      fields={"Subject": "OOA-QA ${run_id} ${rec.name}", "AccountId": "$acct_id$", "Description": "$notes$", "Origin": "OOA-QA", "Status": "New"},
                      out={"id": "case_id"}, **sf())
            with f.else_(nocase):
                f.act("ooa.datatable/get_value", desc="Use the existing case", table="$cases$", row=0, column="Id", out={"value": "case_id"})
        f.act("ooa.salesforce/get_record", desc="7. Read the order back", object="Order__c", id="$order_sf$", out={"record": "back"}, **sf())
        with f.if_({"op": "ne", "left": "$back.Account__c$", "right": "$acct_id$"}, desc="Link lost?"):
            f.act("core/throw", desc="Stop", code="VERIFY.CRM_LINK", message="${rec.name}: the order is not linked to ${acct_id}")
        f.act("ooa.datatable/add_row", desc="9. Record the ids", table="$evidence$",
              values={"key": "$rec.key$", "account_id": "$acct_id$", "order_record": "$order_sf$", "created": "$created$", "status": "$state$", "case_id": "$case_id$",
                      "notes": "$notes$"}, out={"table": "evidence"})
        f.set("rec_note", "${state}${if(isEmpty($case_id$), '', ', case ' + $case_id$)}", desc="Note for the results")
    with c.finish():
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B5
def b5_fulfilment():
    c = Child("RW04", 5, "Fulfilment and customer notice",
              "For each reserved order: moves it through the warehouse states (picked, packed, shipped) with checks, confirms the carrier stub is "
              "the test one, creates the test shipment, writes the packing slip (Word), drafts the notice, asks for approval before an exception "
              "notice (backorder or split), sends to the QA address only, records the message, reconciles the delivery event and never calls payment or refund.",
              "10 shipments (the reserved ones)")
    f = c.f
    for n, t, d in [("b2", "Table", None), ("carrier", "Dictionary", None), ("states", "List", ["reserved", "picked", "packed", "shipped"]), ("st", "String", ""),
                    ("prev", "String", ""), ("email", "String", ""), ("total", "String", ""), ("ship", "Dictionary", None), ("slip", "String", ""),
                    ("lines", "Table", None), ("body", "String", ""), ("needs_ok", "Boolean", False), ("req", "Dictionary", None), ("ans", "Dictionary", None),
                    ("tries", "Number", 0), ("subject", "String", ""), ("seen", "Table", None), ("seen_n", "Number", 0), ("msg_id", "String", ""),
                    ("events", "Dictionary", None), ("delivered", "Boolean", False)]:
        f.var(n, t, "local", d)
    evidence_var(f, ["key", "shipment", "tracking", "packing_slip", "approval", "message_id", "delivered"])
    c.start()
    with c.prepare("Only reserved orders ship (cancelled and fully backordered do not); check the carrier is the test stub."):
        fault_setup(f, "carrier")
        read_prev(f, "RW04-B3", "records")
        f.act("ooa.datatable/filter", desc="Orders that ship", table="$records$", column="reserved", operator="gt", value=0,
              more_conditions=[{"column": "state", "operator": "ne", "value": "cancelled, released"}], match="all", out={"table": "records"})
        read_prev(f, "RW04-B2", "b2")
        f.act("ooa.rest/request", desc="2. Verify the carrier stub (test mode)", url="${standin}/carrier/health", retry=RETRY, out={"json": "carrier"})
        with f.if_({"op": "ne", "left": "$carrier.mode$", "right": "test"}, desc="Not the test carrier?"):
            f.act("core/throw", desc="Stop", code="CARRIER.NOT_TEST", message="The carrier answered mode ${carrier.mode}; only the test carrier may be used")
        f.act("ooa.folder/create", desc="Folder for the packing slips", path="${out_dir}/slips")
    with c.records("Per shipment: warehouse states, test shipment, packing slip, notice (approval first for exceptions), QA-only send, message id, delivery event.",
                   summary="${rec.key}: ${ship.tracking}\nSlip: ${slip}\nNotice ${msg_id} delivered ${delivered}"):
        f.act("ooa.datatable/lookup", desc="Customer email", table="$b2$", column="key", value="$rec.key$", return_column="email", if_not_found="", out={"value": "email"})
        f.act("ooa.datatable/lookup", desc="Order total", table="$b2$", column="key", value="$rec.key$", return_column="total", if_not_found="", out={"value": "total"})
        f.set("prev", "", desc="Start of the state machine")
        with f.loop(desc="1. Warehouse states, in order", iterator="list", over="$states$", **{"as": "st"}):
            with f.if_("$prev$ == 'packed' and $st$ == 'shipped' and isEmpty($email$)", desc="Ship without a recipient?"):
                f.act("core/throw", desc="Illegal transition", code="STATE.ILLEGAL", message="${rec.key}: cannot ship without a recipient")
            f.act("ooa.log/write", desc="Transition", path="$log_file$", message="${rec.key}: ${if(isEmpty($prev$), 'start', $prev$)} -> ${st}")
            f.set("prev", "$st$", desc="Now in this state")
        with f.if_("not endsWith(lower($email$), '@ooa-qa.test')", desc="7. Not a QA address?"):
            f.act("core/throw", desc="Never send outside QA", code="NOTIFY.EXTERNAL", message="${rec.key}: ${email} is not a QA address")
        f.act("ooa.rest/request", desc="3. Create the test shipment", method="POST", url="${standin}/carrier/shipments", retry=RETRY,
              json={"order": "$rec.key$", "ship_to": {"email": "$email$"}, "locations": "$rec.locations$", "run": "$run_id$"}, out={"json": "ship"})
        f.set("slip", "${out_dir}/slips/${rec.key}-packing-slip.docx", desc="4. Packing slip file")
        f.act("ooa.word/create_document", desc="4. Packing slip (Word)", path="$slip$", overwrite=True, session="slip")
        f.act("ooa.word/add_paragraph", desc="Title", text="OOA-QA packing slip ${rec.key}", style="Heading1", session="slip")
        f.act("ooa.word/add_paragraph", desc="Shipment", text="Shipment ${ship.id}, tracking ${ship.tracking}, from ${rec.locations}. Test order: no payment taken.", session="slip")
        f.set("lines", {"columns": ["reserved", "backordered", "state"], "rows": [["$rec.reserved$", "$rec.backordered$", "$rec.state$"]]}, desc="The quantities as a table")
        f.act("ooa.word/add_table", desc="The reserved quantities", table="$lines$", session="slip")
        f.act("ooa.word/save", desc="Save the slip", session="slip")
        f.act("ooa.word/close", desc="Close it", session="slip")
        f.expr("needs_ok", "toNumber($rec.backordered$) > 0 or toNumber($rec.shipments$) > 1", desc="6. An exception notice (backorder or split)?")
        f.set("body", "Your test order ${rec.key} has shipped (tracking ${ship.tracking}).${if($needs_ok$, ' Part of it follows later: ' + $rec.state$ + '.', '')}",
              desc="5. Draft the notice")
        f.set("req", {}, desc="No approval needed yet")
        with f.if_("$needs_ok$", desc="6. Exception notice: ask for approval first"):
            f.act("ooa.rest/request", desc="Ask the service lead", method="POST", url="${standin}/approvals/requests", bearer_token=cred("servicenow"), retry=RETRY,
                  json={"subject": "notice ${rec.key}", "approver": "lead.service@ooa-qa.test", "requester": "orders-bot@ooa-qa.test", "run": "$run_id$", "comment": "$body$"},
                  out={"json": "req"})
            f.set("tries", 0, desc="Start counting")
            f.set("ans", {"status": "pending"}, desc="Waiting")
            with f.loop(desc="Wait for the answer", iterator="while", condition="$ans.status$ == 'pending' and $tries$ < 3", max_iterations=4):
                f.act("core/delay", desc="Wait a second", seconds=1)
                f.expr("tries", "$tries$ + 1", desc="Count")
                f.act("ooa.rest/request", desc="Read the answer", url="${standin}/approvals/requests/${req.id}", bearer_token=cred("servicenow"), out={"json": "ans"})
            with f.if_({"op": "ne", "left": "$ans.status$", "right": "approved"}, desc="Not approved?"):
                f.act("core/throw", desc="Hold the notice", code="APPROVAL.NOTICE", message="${rec.key}: exception notice ${ans.status}")
        f.set("subject", "OOA-QA ${run_id} order ${rec.key} shipped", desc="Subject")
        f.act("ooa.microsoft365/send_mail", desc="7. Send the notice (QA address only)", to="$email$", subject="$subject$", body="$body$", **graph(QA_MAILBOX))
        f.act("ooa.microsoft365/list_mail", desc="8. Find it in the customer's mailbox (message id)", folder="inbox", subject_contains="$subject$", max_results=1,
              out={"table": "seen", "count": "seen_n"}, **graph("$email$"))
        with f.if_("$seen_n$ == 0", desc="Not delivered?"):
            f.act("core/throw", desc="Report it", code="VERIFY.NOT_DELIVERED", message="Notice to ${email} did not arrive")
        f.act("ooa.datatable/get_value", desc="The message id", table="$seen$", row=0, column="id", out={"value": "msg_id"})
        f.act("ooa.rest/request", desc="9. Delivery events from the carrier", url="${standin}/carrier/shipments/${ship.id}/events", retry=RETRY, out={"json": "events"})
        f.expr("delivered", "last($events.events$).status == 'delivered'", desc="Delivered?")
        f.act("ooa.datatable/add_row", desc="Record it", table="$evidence$",
              values={"key": "$rec.key$", "shipment": "$ship.id$", "tracking": "$ship.tracking$", "packing_slip": "$slip$", "approval": {"expr": "get($req$, 'id', '')"},
                      "message_id": "$msg_id$", "delivered": "$delivered$"}, out={"table": "evidence"})
        f.set("rec_note", "${ship.tracking} delivered ${delivered}", desc="Note for the results")
    with c.finish("Save; no payment or refund call was made (the agent has none)."):
        f.act("ooa.log/write", desc="10. No payment or refund", path="$log_file$", message="No payment, capture or refund call was made for any order")
        save_evidence(f)
    return c


# ------------------------------------------------------------------ B6
def b6_reconciler():
    c = Child("RW04", 6, "Windows revenue reconciler",
              "Brings the order, stock, CRM and shipment rows together for the 10 orders, compares line counts, tax, currency, shipment and "
              "status, finds duplicates and missing rows, keys each bundle into the demo order-entry desktop app (the legacy revenue system, "
              "with its validation pop-up handled by an interrupt rule), writes the exception sheet, recalculates the workbook in Excel, "
              "stores it in SharePoint, reads it back and checks the totals.",
              "10 order bundles", data_file="RW04/orders.csv")
    f = c.f
    src = {"b2": "RW04-B2", "b3": "RW04-B3", "b4": "RW04-B4", "b5": "RW04-B5"}
    for k in src:
        f.var(k, "Table", "local", {"columns": ["key"], "rows": []})
    for n, t, d in [("b2r", "Table", None), ("total", "String", ""), ("cur", "String", ""), ("state", "String", ""), ("crm", "String", ""), ("trk", "String", ""),
                    ("intake", "String", ""), ("issue", "String", ""), ("seen", "List", []), ("pid", "Number", 0), ("status_text", "String", ""),
                    ("objs", "List", None), ("objn", "Number", 0), ("win", "Dictionary", None), ("wh", "Number", 0), ("wx", "Number", 0), ("wy", "Number", 0), ("clip", "String", ""), ("app_out", "String", ""),
                    ("app_rows", "Table", None), ("app_n", "Number", 0), ("sum_total", "Number", 0), ("cell", "Any", None), ("item", "String", ""),
                    ("bundle", "Table", {"columns": ["key", "name", "intake", "currency", "total", "reservation", "crm_order", "tracking", "issue"], "rows": []}),
                    ("issues", "Table", None)]:
        f.var(n, t, "local", d)
    f.var("workbook", "String", "output", "", "The reconciliation workbook.")
    obj = lambda aid, title="OOA Order Entry": {"technology": "desktop", "window": {"title": title, "title_match": "equals"},
                                                "properties": [{"name": "AutomationId", "value": aid, "match": "equals", "use": True}]}
    c.start()
    with c.prepare("1. Export every child's rows; start the demo order-entry app (the legacy revenue system) with an interrupt rule for its pop-up."):
        for k, code in src.items():
            read_prev(f, code, k)
        read_prev(f, "RW04-B2", "b2r", "results.csv", "results")
        f.set("app_out", "${out_dir}/revenue-entries.csv", desc="Where the demo app writes its entries")
        f.act("ooa.file/delete", desc="Start with no entries", path="$app_out$", cont=True)
        f.act("ooa.application/start_program", desc="Start the demo order-entry app", program="powershell.exe",
              arguments=["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "${base}/data/RW04/order-entry.ps1", "-Out", "$app_out$"], window_state="normal",
              out={"process_id": "pid"})
        f.act("ooa.window/wait_for", desc="Wait for its window", title="OOA Order Entry", timeout_seconds=30)
        f.act("ooa.interrupts/add_rule", desc="Interrupt rule: close the app's Validation pop-up whenever it appears", name="validation-popup", when_window="Validation",
              then="close_window")
        f.act("ooa.desktop_ui/find_legacy_objects", desc="List the app's controls through MSAA (legacy view)", window_title="OOA Order Entry", cont=True,
              out={"objects": "objs", "count": "objn"})
        f.act("ooa.window/get_info", desc="Where the app's window is", title="OOA Order Entry", out={"window": "win", "height": "wh", "x": "wx", "y": "wy"})
        f.act("ooa.window/activate", desc="Bring the app to the front", title="OOA Order Entry", timeout_seconds=3, cont=True)
        with f.if_({"condition": "ooa.window/is_active", "args": {"title": "OOA Order Entry"}}, desc="Is the app in front? (mouse and keys only reach the app then)") as front:
            f.act("ooa.desktop/mouse_click", desc="Mouse: click inside the app's order grid (the app is in front)", relative_to="screen",
                  x={"expr": "$wx$ + 60"}, y={"expr": "$wy$ + $wh$ - 60"})
        with f.else_(front):
            f.act("ooa.log/write", desc="Skip the mouse step", path="$log_file$", level="WARN", message="The app is not in front (Windows focus rules): mouse step skipped")
    with f.try_(desc="Work through the bundles; the app is closed afterwards whatever happens") as tf:
        with c.records("Per order: compare rows, find duplicates and gaps, key it into the legacy app, read its status.",
                       summary="${rec.name}: ${state} | CRM ${crm} | ${trk}\nIssue: ${issue}\nApp: ${status_text}"):
            f.act("ooa.datatable/lookup", desc="Intake result", table="$b2r$", column="key", value="$rec.key$", return_column="status", if_not_found="missing", out={"value": "intake"})
            f.act("ooa.datatable/lookup", desc="Total", table="$b2$", column="key", value="$rec.key$", return_column="total", if_not_found="", out={"value": "total"})
            f.act("ooa.datatable/lookup", desc="Currency", table="$b2$", column="key", value="$rec.key$", return_column="currency", if_not_found="", out={"value": "cur"})
            f.act("ooa.datatable/lookup", desc="Reservation", table="$b3$", column="key", value="$rec.key$", return_column="state", if_not_found="", out={"value": "state"})
            f.act("ooa.datatable/lookup", desc="CRM order", table="$b4$", column="key", value="$rec.key$", return_column="order_record", if_not_found="", out={"value": "crm"})
            f.act("ooa.datatable/lookup", desc="Tracking", table="$b5$", column="key", value="$rec.key$", return_column="tracking", if_not_found="", out={"value": "trk"})
            f.expr("issue", "if($intake$ == 'exception', 'held at intake', if(isEmpty($crm$), 'missing CRM row', if($state$ == 'cancelled, released', 'cancelled', "
                            "if(isEmpty($trk$) and $state$ != 'backorder', 'missing shipment', if(not isEmpty($cur$) and $cur$ != 'USD', 'currency', 'none')))))",
                   desc="3.-4. Compare and find gaps")
            with f.if_({"op": "contains", "left": "$seen$", "right": "$rec.name$"}, desc="4. Duplicate row?"):
                f.set("issue", "duplicate row", desc="Issue")
            f.act("ooa.list/add_item", desc="Remember it", list="$seen$", item="$rec.name$", out={"list": "seen"})
            f.act("ooa.desktop_ui/object_action", desc="Customer field: the order", object=obj("txtCustomer"), action_="set_text", text="${rec.name} ${rec.key}")
            f.act("ooa.desktop_ui/object_action", desc="Amount field", object=obj("txtAmount"), action_="set_text",
                  text={"expr": "if(isEmpty($total$), 'n/a', $total$)"})
            f.act("ooa.desktop_ui/object_action", desc="Kind", object=obj("cboKind"), action_="select_item", item={"expr": "if($issue$ == 'cancelled', 'Return', 'Standard')"})
            with f.if_({"op": "ne", "left": "$issue$", "right": "none"}, desc="An issue: mark it urgent"):
                f.act("ooa.desktop_ui/object_action", desc="Urgent", object=obj("chkUrgent"), action_="check")
            with f.if_({"condition": "ooa.window/is_active", "args": {"title": "OOA Order Entry"}}, desc="App in front? (keys only reach the app then)"):
                f.act("ooa.desktop/press_keys", desc="Keyboard: Tab to the next field", keys="TAB")
            f.act("ooa.desktop_ui/object_action", desc="Save", object=obj("btnSave"), action_="click", click_method="pattern")
            f.act("ooa.interrupts/check_now", desc="Handle the Validation pop-up now if it came (amount n/a)")
            f.act("ooa.desktop_ui/object_action", desc="Read the app's status", object=obj("lblStatus"), action_="get_text", out={"result": "status_text"})
            with f.if_("not startsWith($status_text$, 'Saved')", desc="Not saved (held order without amount)?"):
                f.act("ooa.desktop_ui/object_action", desc="Clear the amount for the next entry", object=obj("txtAmount"), action_="set_text", text="")
            f.act("ooa.datatable/add_row", desc="Bundle row", table="$bundle$",
                  values={"key": "$rec.key$", "name": "$rec.name$", "intake": "$intake$", "currency": "$cur$", "total": {"expr": "toNumber(coalesce($total$, '0'))"},
                          "reservation": "$state$",
                          "crm_order": "$crm$", "tracking": "$trk$", "issue": "$issue$"}, out={"table": "bundle"})
            f.set("rec_note", "${issue}; app: ${status_text}", desc="Note for the results")
    with f.finally_(tf):
        f.act("ooa.interrupts/clear_rules", desc="Remove the interrupt rule", cont=True)
        f.act("ooa.application/stop_program", desc="Close the demo app", process_id="$pid$", force=True, cont=True)
    with c.finish("Exception sheet, Excel recalculation, SharePoint, read back and totals."):
        with f.if_({"condition": "ooa.file/exists", "args": {"path": "$app_out$"}}, desc="Did the app write entries?"):
            f.act("ooa.csv/read", desc="9. The legacy app's entries", path="$app_out$", out={"table": "app_rows"})
            f.act("ooa.datatable/row_count", desc="How many", table="$app_rows$", out={"count": "app_n"})
        f.act("ooa.datatable/filter", desc="5. Exception rows", table="$bundle$", column="issue", operator="ne", value="none", out={"table": "issues"})
        f.act("ooa.datatable/sum_column", desc="Revenue of the accepted orders", table="$bundle$", column="total", if_not_number="skip", out={"total": "sum_total"})
        f.set("workbook", "${out_dir}/RW04-revenue.xlsx", desc="Name of the workbook")
        f.act("ooa.excel/create_workbook", desc="The reconciliation workbook", path="$workbook$", overwrite=True, sheet="Orders")
        f.act("ooa.excel/write_table", desc="All bundles", table="$bundle$", sheet="Orders")
        f.act("ooa.excel/set_cell", desc="Label", cell="K1", value="Revenue (USD)", sheet="Orders")
        f.act("ooa.excel/set_formula", desc="Revenue formula", cell="L1", formula="SUM(E2:E11)", sheet="Orders")
        f.act("ooa.excel/add_sheet", desc="Exceptions sheet", name="Exceptions")
        f.act("ooa.excel/write_table", desc="5. Write the exceptions", table="$issues$", sheet="Exceptions")
        f.act("ooa.excel/save", desc="Save")
        f.act("ooa.excel/close", desc="Close")
        f.act("ooa.excel_desktop/refresh_all", desc="2. Open in Excel desktop: recalculate and save", path="$workbook$")
        f.act("ooa.excel/open", desc="7. Read it back", path="$workbook$", session="check")
        f.act("ooa.excel/get_cell", desc="The revenue Excel worked out", cell="L1", sheet="Orders", session="check", out={"value": "cell"})
        f.act("ooa.excel/close", desc="Close", session="check", save=False)
        with f.if_("abs(toNumber($cell$) - $sum_total$) > 0.01", desc="8. Totals differ?"):
            f.act("core/throw", desc="Stop", code="VERIFY.TOTALS", message="Workbook revenue ${cell}, rows add up to ${sum_total}")
        f.act("ooa.desktop/clipboard_set", desc="Revenue line on the clipboard (for the finance chat)", text="RW04 ${run_id}: revenue USD ${sum_total}, ${app_n} legacy entries")
        f.act("ooa.desktop/clipboard_get", desc="Read it back", out={"text": "clip"})
        f.act("ooa.microsoft365/upload_file", desc="6. Store it in the QA SharePoint site", local_path="$workbook$", remote_path="OOA-QA/${run_id}/RW04-revenue.xlsx",
              drive="sites/ooa-qa/drive", access_token=cred("graph"), base_url="${standin}/graph/v1.0", retry=RETRY, out={"item_id": "item"})
        f.act("ooa.datatable/write_to_file", desc="9. Checkpoint: the bundles as CSV", table="$bundle$", path="${out_dir}/RW04-bundles.csv")
        f.act("ooa.log/write", desc="Masked audit event", path="$log_file$", message="Reconciled ${len($seen$)} orders; revenue ${sum_total}; legacy entries ${app_n}; clipboard ok ${$clip$ != ''}")
    return c


def main():
    return Main("RW04", "Order-to-cash", "Order-to-cash: Shopify dev store -> Salesforce sandbox -> SQL ledger -> Microsoft 365 (draft and test orders only).",
                [("rw04_b1", "RW04-B1", "Catalog and price reader"), ("rw04_b2", "RW04-B2", "Order intake and risk"),
                 ("rw04_b3", "RW04-B3", "Inventory reservation"), ("rw04_b4", "RW04-B4", "Salesforce CRM updater"),
                 ("rw04_b5", "RW04-B5", "Fulfilment and customer notice"), ("rw04_b6", "RW04-B6", "Windows revenue reconciler")], FOLDER, parallel_group=(0, 2), credential_inputs={"demo_password": "Sauce Demo's demo password from the vault, passed to RW04-B1."}).build()


CHILDREN = [b1_catalog, b2_orders, b3_inventory, b4_crm, b5_fulfilment, b6_reconciler]
MAINS = [main]
