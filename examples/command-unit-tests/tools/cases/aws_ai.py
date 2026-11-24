"""Hand-written cases for the AWS AI services commands (Connect and Disconnect against $standin_url$/cloud-ai/aws)."""
from helpers_cloud import adapt_samples, cred

T = "ooa.aws_ai/"


def add(ua):
    adapt_samples(ua, ua.f.name)
    ua.var("aws_account", "String")
    ua.var("aws_lang", "String")
    with ua.case(T + "connect", "keys checked with the identity service",
                 "Connect keeps the region and the access keys from the vault in a session; check_now asks the identity "
                 "service (a free call) which account the keys belong to. Checked: the account id and a Comprehend call "
                 "through the session.") as f:
        f.act(T + "connect", desc="Connect to the stand-in AWS", region="eu-west-1", access_key_id=cred("aws/id"),
              secret_access_key=cred("aws/secret"), endpoint="$standin_url$/cloud-ai/aws", check_now=True,
              session="aws_ai", out={"account": "aws_account"})
        ua.check_eq("aws_account", "123456789012")
        f.act(T + "comprehend_detect_language", desc="Which language is it?", session="aws_ai",
              text="Bonjour, la facture est en retard.", out={"language": "aws_lang"})
        ua.check_eq("aws_lang", "fr")
    with ua.case(T + "disconnect", "close the session",
                 "Disconnect closes the session and forgets the keys. Checked: a step after it finds no session.") as f:
        f.act(T + "disconnect", desc="Close the session", session="aws_ai")
        ua.expect_error("SESSION.NOT_FOUND", lambda ff: ff.act(T + "comprehend_detect_language", desc="This needs the closed session",
                                                                session="aws_ai", text="Hello"))
