"""Lists the interface texts of the Command Center console for translation (K3).

    python scripts/i18n-extract.py

Writes control-room/src/main/resources/ooa-ui/ooa-i18n-keys.json: every visible text found in cr.js and its
index.html (Agent Studio, platform/ui, is not translated yet). A text built as "Delete " + name + "?" becomes
the key "Delete {}?". Translations live beside it in ooa-i18n-<language>.json ({"key": "translation"}); missing
keys show in English. The I18nCatalogsTest checks the catalogs against this list.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = ["control-room/src/main/resources/console/cr.js", "control-room/src/main/resources/console/index.html"]
OUT = os.path.join(ROOT, "control-room/src/main/resources/ooa-ui/ooa-i18n-keys.json")

LIT = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
# "text " + expression + " text" (+ expression + "text" ...): one key with {} for each expression (simple
# expressions without quotes), and expression + " text" at the start of a text
_L = LIT.pattern
_E = r'(?:[A-Za-z_$][\w$.\[\]]*(?:\([^"()]*\))?|\([^"()]*\))'
CHAIN = re.compile(_L + r'(?:\s*\+\s*' + _E + r'\s*\+\s*' + _L + r')+')
LEAD = re.compile(r'(?<![\w"$.])' + _E + r'\s*\+\s*' + _L)
PIECE = re.compile(_L)
# texts made in code that no literal shows whole: run states, ages and durations
EXTRA = ["success", "failed", "running", "queued", "stopped", "cancelled", "pending", "enabled", "disabled", "online",
         "offline", "waiting", "done", "approved", "rejected", "open", "closed", "active", "paused", "expired", "deferred",
         "locked", "idle", "review", "passed", "norun", "unattended", "attended", "headless", "manual", "high", "low",
         "normal", "{} min ago", "{} h ago", "{} days ago", "in {}", "{} runs, {} failed", "{} runs, {} failed, about {}"]
HTML_TEXT = re.compile(r">([^<>{}]*[A-Za-z]{2,}[^<>{}]*)<")
HTML_ATTR = re.compile(r'(?:placeholder|title|aria-label|alt)="([^"]+)"')


def unescape(s):
    try:
        return json.loads('"' + s + '"')
    except ValueError:
        return s


def visible(s):
    t = s.strip()
    if len(t) < 2 or not re.search(r"[A-Za-z]{2}", t):
        return False
    if re.match(r"M[\d.\-]+[ ,]", t):                # SVG paths
        return False
    if re.search(r"[{}<>=;`]|^https?:|/api/|^/|\.(js|css|png|json|svg)$|^#[0-9a-fA-F]{3,8}$", t):
        return False
    if re.fullmatch(r"[a-z0-9_.:\-]+", t):          # ids, keys, codes, icon and class names
        return False
    if re.fullmatch(r"[A-Z0-9_.]+", t) and "_" in t:  # error codes such as CR.BAD_INPUT
        return False
    words = t.split()
    if all(re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)+|[a-z]+", w) for w in words) and any("-" in w for w in words):
        return False                                  # class lists such as "row-actions tight"
    if re.fullmatch(r"[a-z]+(\.[a-z_]+)+(/[a-z_]+)?", t):  # toolkit and action ids
        return False
    if " " not in t and ("/" in t or re.fullmatch(r"[A-Z0-9+\-]+|[a-z]+[A-Z]\w*|\*\.\w+|[\w-]+\(s\)", t)):
        return False                                  # paths, methods, keys, camelCase names, *.pdf
    if re.match(r"[#$'.,:;)?!\]%*\-+]|\(\?", t) and not t.startswith("+ "):
        return False                                  # selectors, variables, code and sentence pieces
    if t.count("(") != t.count(")") or t.count("“") != t.count("”") or "\\" in t or "\n" in t or "replace(" in t:
        return False                                  # pieces cut out of a longer text, or code
    if len(words) <= 2 and (re.match(r"[a-z,.;:)\]-]", t) or re.search(r"[,(\[-]$", t)) and not re.fullmatch(r"[a-z]+( [a-z]+)?", t):
        return False                                  # pieces of a longer sentence ("MB)", "runs,")
    return True


def main():
    keys = set()
    for rel in SOURCES:
        text = open(os.path.join(ROOT, rel), encoding="utf-8").read()
        if rel.endswith(".html"):
            for m in HTML_TEXT.finditer(text):
                if visible(m.group(1)):
                    keys.add(" ".join(m.group(1).split()))
            for m in HTML_ATTR.finditer(text):
                if visible(m.group(1)):
                    keys.add(m.group(1).strip())
            continue
        for m in CHAIN.finditer(text):
            pieces = [unescape(x) for x in PIECE.findall(m.group(0))]
            whole = "{}".join(pieces)
            if visible(whole.replace("{}", "x")) and re.search(r"[A-Za-z]{3}", "".join(pieces)):
                keys.add(whole.strip())
        for m in LEAD.finditer(text):
            b = unescape(m.group(1))
            if b[:1] in " " and re.search(r"[A-Za-z]{2}", b) and visible("x" + b):
                keys.add(("{}" + b).strip())
        for m in LIT.finditer(text):
            s = unescape(m.group(1))
            if visible(s):
                keys.add(s.strip())
    keys.update(EXTRA)
    out = sorted(keys)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, ensure_ascii=False, indent=0)
        f.write("\n")
    print(len(out), "texts in", os.path.relpath(OUT, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
