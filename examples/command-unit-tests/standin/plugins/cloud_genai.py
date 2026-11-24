"""Model endpoints for the "UT Generative AI" agent that the scripted OpenAI-compatible model (/v1/chat/completions)
does not cover:

    POST /cloud-genai/hf/<owner>/<model>                         a Hugging Face inference server: ut/sentiment answers
                                                                 labels as JSON; ut/text-to-image answers a PNG picture
    POST /cloud-genai/sagemaker/endpoints/<name>/invocations     a SageMaker endpoint: a churn score for each instance
"""
import base64
import json
import re

PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def handle(h, m, path, q):
    raw = h.raw()
    mh = re.match(r"/cloud-genai/hf/(.+)$", path)
    if mh and m == "POST":
        model = mh.group(1)
        if model == "ut/text-to-image":
            h.send_response(200)
            h.send_header("Content-Type", "image/png")
            h.send_header("Content-Length", str(len(PNG)))
            h.end_headers()
            h.wfile.write(PNG)
            return
        if model == "ut/sentiment":
            text = json.loads(raw.decode("utf-8") or "{}").get("inputs", "")
            neg = any(w in str(text).lower() for w in ("late", "broken", "bad"))
            return h.send(200, [[{"label": "NEGATIVE" if neg else "POSITIVE", "score": 0.97}, {"label": "POSITIVE" if neg else "NEGATIVE", "score": 0.03}]])
        return h.send(404, {"error": "Model %s does not exist" % model})
    ms = re.match(r"/cloud-genai/sagemaker/endpoints/([^/]+)/invocations$", path)
    if ms and m == "POST":
        if not h.headers.get("Authorization", "").startswith("AWS4-HMAC-SHA256"):
            return h.send(403, {"message": "Missing Authentication Token"})
        try:
            inst = json.loads(raw.decode("utf-8")).get("instances", [])
        except ValueError:
            return h.send(400, {"ErrorCode": "CLIENT_ERROR_FROM_MODEL", "Message": "Body is not JSON"})
        preds = [round(min(0.99, 0.1 + 0.1 * sum(float(x) for x in i)), 2) for i in inst]
        return h.send(200, {"predictions": preds, "endpoint": ms.group(1)}, headers={"X-Amzn-Invoked-Production-Variant": "AllTraffic"})
    raise KeyError(path)


ROUTES = [("/cloud-genai/", handle, None)]
