"""Hand-written cases for the Kafka commands (Connect and Disconnect against the stand-in's Kafka REST proxy)."""
from helpers_cloud import adapt_samples, cred

T = "ooa.kafka/"


def add(ua):
    adapt_samples(ua, ua.f.name)
    ua.var("k_names", "List")
    ua.var("k_offset", "Number")
    ua.var("k_closed", "Boolean")
    with ua.case(T + "connect", "address and key kept for later steps",
                 "Connect keeps the REST proxy's address and the API key and secret (from the vault) under a session, so "
                 "later steps need not repeat them. Checked: a message sent and the topic listed without an address.") as f:
        f.act(T + "connect", desc="Connect to the stand-in Kafka", base_url="$standin_url$/kafka", api_key="ut-key",
              api_secret=cred("kafka/secret"))
        f.act(T + "produce_message", desc="Send an order event", topic="ut-orders", key="order-1",
              value={"order": 1, "total": 12.5}, out={"offset": "k_offset"})
        ua.check("ge", "$k_offset$", 0, what="the offset")
        f.act(T + "list_topics", desc="List the topics", out={"names": "k_names"})
        ua.check("contains", "$k_names$", "ut-orders", what="the topic")
    with ua.case(T + "disconnect", "close the session",
                 "Disconnect closes the session and forgets its sign-in. Checked: it reports the session closed.") as f:
        f.act(T + "disconnect", desc="Close the session", out={"closed": "k_closed"})
        ua.check_eq("k_closed", True)
