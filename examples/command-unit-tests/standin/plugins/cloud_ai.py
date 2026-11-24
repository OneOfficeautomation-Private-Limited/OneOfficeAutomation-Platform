"""Cloud AI services for the Connect and Disconnect cases of the AWS, Azure and Google AI agents (the other commands use
the command samples' scripted answers, standin/plugins/cloud_samples.py).

    /cloud-ai/aws/          STS GetCallerIdentity (account 123456789012) and Comprehend DetectDominantLanguage
    /cloud-ai/azure/language/:analyze-text      Azure AI Language sentiment
    /cloud-ai/google/v1/documents:analyzeSentiment   Google Natural Language sentiment
"""


def handle(h, m, path, q):
    raw = h.raw().decode("utf-8", "replace")
    if path.startswith("/cloud-ai/aws"):
        if "Action=GetCallerIdentity" in raw:
            return h.send(200, '<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>'
                               '<Arn>arn:aws:iam::123456789012:user/ut-bot</Arn><UserId>AIDAUT</UserId><Account>123456789012</Account>'
                               '</GetCallerIdentityResult></GetCallerIdentityResponse>', ctype="text/xml")
        target = h.headers.get("X-Amz-Target", "")
        if target.endswith("DetectDominantLanguage"):
            return h.send(200, {"Languages": [{"LanguageCode": "fr", "Score": 0.97}]}, ctype="application/x-amz-json-1.1")
        return h.send(400, {"__type": "UnknownOperationException", "message": "UT stand-in: " + target}, ctype="application/x-amz-json-1.1")
    if path == "/cloud-ai/azure/language/:analyze-text" and m == "POST":
        if not h.headers.get("Ocp-Apim-Subscription-Key"):
            return h.send(401, {"error": {"code": "401", "message": "Access denied due to invalid subscription key."}})
        return h.send(200, {"kind": "SentimentAnalysisResults", "results": {"documents": [
            {"id": "1", "sentiment": "negative", "confidenceScores": {"positive": 0.02, "neutral": 0.08, "negative": 0.9}, "sentences": []}],
            "errors": []}})
    if path == "/cloud-ai/google/v1/documents:analyzeSentiment" and m == "POST":
        return h.send(200, {"documentSentiment": {"score": -0.7, "magnitude": 0.9}, "language": "en", "sentences": []})
    raise KeyError(path)


ROUTES = [("/cloud-ai/", handle, None)]
