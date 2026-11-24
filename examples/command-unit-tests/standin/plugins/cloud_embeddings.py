"""OpenAI-compatible embeddings for the scripted model (POST /v1/embeddings), used by the "UT Knowledge Q&A" agent.

Each text becomes a 32-number vector of its hashed words (normalised), so texts sharing words are near each other and
the same text always gives the same vector.
"""
import hashlib
import math
import re


def vector(text):
    v = [0.0] * 32
    for w in re.findall(r"[a-z0-9]+", str(text).lower()):
        if len(w) > 2:
            v[int(hashlib.md5(w.encode()).hexdigest(), 16) % 32] += 1.0
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [round(x / n, 6) for x in v]


def handle(h, m, path, q):
    if path != "/v1/embeddings" or m != "POST":
        raise KeyError(path)
    b = h.body()
    inputs = b.get("input", [])
    if isinstance(inputs, str):
        inputs = [inputs]
    data = [{"object": "embedding", "index": i, "embedding": vector(t)} for i, t in enumerate(inputs)]
    tokens = sum(len(str(t).split()) for t in inputs)
    return h.send(200, {"object": "list", "data": data, "model": b.get("model", "ut-embed"),
                        "usage": {"prompt_tokens": tokens, "total_tokens": tokens}})


ROUTES = [("/v1/embeddings", handle, "ai")]
