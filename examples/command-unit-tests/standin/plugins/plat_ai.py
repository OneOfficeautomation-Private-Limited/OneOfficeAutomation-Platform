"""Stand-in chat model for the platform unit tests (Prompt Skills, AI agents): OpenAI-compatible, under /plat-ai/v1,
with answers fixed by markers in the prompt so every run repeats exactly.

  CLASSIFY-TICKET ...        a JSON object {"category", "priority"}: billing when the text names an invoice or a refund,
                             high priority when it says urgent
  SUMMARISE ... Text: ...    "Summary: " and the first sentence of the text
  tools offered, no answer   calls the first tool with the JSON written after TOOL_ARGS: in the goal (or {})
  a tool has answered        the tool's outputs: as the JSON the agent asked for (status, answer, outputs) when the
                             system prompt asks for one, otherwise "Tool answered: <outputs as JSON>"
"""
import json
import re

# `core` (the stand-in module: LOCK, STATE) is set by standin.py before this file runs.


def _reply(h, model, msg, finish="stop"):
    with core.LOCK:
        core.STATE.setdefault("plat_ai_calls", []).append({"model": model, "tool_calls": bool(msg.get("tool_calls"))})
    return h.send(200, {"id": "chatcmpl-ut", "object": "chat.completion", "model": model,
                        "choices": [{"index": 0, "message": msg, "finish_reason": finish}],
                        "usage": {"prompt_tokens": 20, "completion_tokens": 10, "total_tokens": 30}})


def plat_ai(h, m, path, q):
    if path == "/plat-ai/v1/models":
        return h.send(200, {"object": "list", "data": [{"id": "ut-model", "object": "model", "owned_by": "ooa-ut"}]})
    if path == "/plat-ai/v1/calls":
        return h.send(200, {"calls": len(core.STATE.get("plat_ai_calls", []))})
    if path != "/plat-ai/v1/chat/completions" or m != "POST":
        raise KeyError(path)
    b = h.body()
    model = b.get("model", "ut-model")
    msgs = b.get("messages", [])

    def text_of(x):
        c = x.get("content", "")
        if isinstance(c, list):
            return " ".join(str(p.get("text", "")) for p in c if isinstance(p, dict))
        return str(c or "")
    system = " ".join(text_of(x) for x in msgs if x.get("role") == "system")
    user = "\n".join(text_of(x) for x in msgs if x.get("role") == "user")
    tool_msgs = [x for x in msgs if x.get("role") == "tool"]
    tools = [t.get("function", {}).get("name") for t in b.get("tools") or []]

    if tools and not tool_msgs:
        mt = re.search(r"TOOL_ARGS:\s*(\{.*?\})", user + "\n" + system, re.S)
        args = mt.group(1) if mt else "{}"
        name = next((t for t in tools if not t.startswith("ask_") and t != "finish"), tools[0])
        return _reply(h, model, {"role": "assistant", "content": None, "tool_calls": [
            {"id": "call_ut_1", "type": "function", "function": {"name": name, "arguments": args}}]}, "tool_calls")
    if tool_msgs:
        raw = text_of(tool_msgs[-1])
        try:
            outputs = json.loads(raw).get("outputs") or {}
        except (ValueError, AttributeError):
            outputs = {}
        keys = re.search(r"holding exactly these keys: (\{.*?\})\. Use null", system, re.S)
        if keys:
            wanted = json.loads(keys.group(1))
            answer = {"status": "completed", "answer": "The tool gave " + json.dumps(outputs, sort_keys=True),
                      "outputs": {k: outputs.get(k) for k in wanted}}
            return _reply(h, model, {"role": "assistant", "content": json.dumps(answer)})
        return _reply(h, model, {"role": "assistant", "content": "Tool answered: " + json.dumps(outputs, sort_keys=True)})
    both = system + "\n" + user
    if "CLASSIFY-TICKET" in both:
        low = user.lower()
        content = json.dumps({"category": "billing" if ("invoice" in low or "refund" in low) else "other",
                              "priority": "high" if "urgent" in low else "normal"})
    elif "SUMMARISE" in both:
        tm = re.search(r"Text:\s*(.+)", user, re.S)
        first = re.split(r"(?<=[.!?])\s", (tm.group(1) if tm else user).strip(), maxsplit=1)[0]
        content = "Summary: " + first.strip()
    else:
        content = "UT stand-in model: no fixed answer for this prompt."
    return _reply(h, model, {"role": "assistant", "content": content})


ROUTES = [("/plat-ai/", plat_ai, "plat_ai")]
SEED = {}
