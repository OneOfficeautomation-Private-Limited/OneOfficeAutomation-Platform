"""Hand-written cases for the Google Cloud AI commands (Connect and Disconnect against $standin_url$/cloud-ai/google)."""
from helpers_cloud import adapt_samples, cred

T = "ooa.google_ai/"


def add(ua):
    adapt_samples(ua, ua.f.name)
    ua.var("g_sentiment", "String")
    with ua.case(T + "connect", "API key from the vault",
                 "Connect keeps the project, the key (from the vault) and the address in a session for the steps after "
                 "it. Checked: a sentiment call through the session.") as f:
        f.act(T + "connect", desc="Connect to the stand-in Google AI", credential=cred("google/key"), credential_type="api_key",
              project="ut-project", endpoint="$standin_url$/cloud-ai/google", session="google_ai")
        f.act(T + "language_sentiment", desc="Sentiment of a complaint", session="google_ai",
              text="The delivery was late and the box was broken.", out={"sentiment": "g_sentiment"})
        ua.check_eq("g_sentiment", "negative")
    with ua.case(T + "disconnect", "close the session",
                 "Disconnect closes the session and forgets the key. Checked: a step after it finds no session.") as f:
        f.act(T + "disconnect", desc="Close the session", session="google_ai")
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act(T + "language_sentiment", desc="This needs the closed session",
                                                                session="google_ai", text="Hello"))
