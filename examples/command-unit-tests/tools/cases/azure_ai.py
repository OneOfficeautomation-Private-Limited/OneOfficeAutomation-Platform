"""Hand-written cases for the Azure AI services commands (Connect and Disconnect against $standin_url$/cloud-ai/azure)."""
from helpers_cloud import adapt_samples, cred

T = "ooa.azure_ai/"


def add(ua):
    adapt_samples(ua, ua.f.name)
    ua.var("az_sentiment", "String")
    with ua.case(T + "connect", "resource key from the vault",
                 "Connect keeps the resource's address and key (from the vault) in a session for the steps after it. "
                 "Checked: a sentiment call through the session.") as f:
        f.act(T + "connect", desc="Connect to the stand-in Azure AI resource", endpoint="$standin_url$/cloud-ai/azure",
              credential=cred("azure/key"), credential_type="key", session="azure_ai")
        f.act(T + "language_sentiment", desc="Sentiment of a complaint", session="azure_ai",
              text="The delivery was late and the box was broken.", out={"sentiment": "az_sentiment"})
        ua.check_eq("az_sentiment", "negative")
    with ua.case(T + "disconnect", "close the session",
                 "Disconnect closes the session and forgets the key. Checked: a step after it finds no session.") as f:
        f.act(T + "disconnect", desc="Close the session", session="azure_ai")
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act(T + "language_sentiment", desc="This needs the closed session",
                                                                session="azure_ai", text="Hello"))
