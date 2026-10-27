"""Merges translations into an interface language catalog (K3).

    python scripts/i18n-merge.py de translations.txt [more.txt ...]

Each line of a translations file is "<number><TAB><translation>", the number being the text's position in
ooa-ui/ooa-i18n-keys.json (made by i18n-extract.py). The translation must keep every {} of the English text.
The result is ooa-ui/ooa-i18n-<language>.json, sorted, holding only texts that are still in the key list.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, "control-room/src/main/resources/ooa-ui")


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    lang = argv[1]
    keys = json.load(open(os.path.join(UI, "ooa-i18n-keys.json"), encoding="utf-8"))
    path = os.path.join(UI, "ooa-i18n-" + lang + ".json")
    catalog = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
    problems = 0
    for f in argv[2:]:
        for n, line in enumerate(open(f, encoding="utf-8"), 1):
            line = line.rstrip("\r\n")
            if not line.strip():
                continue
            num, _, text = line.partition("\t")
            if not num.isdigit() or int(num) >= len(keys) or not text.strip():
                print(f"{f}:{n}: not '<number>\\t<translation>' for a known text: {line[:60]}")
                problems += 1
                continue
            key = keys[int(num)]
            if text.count("{}") != key.count("{}"):
                print(f"{f}:{n}: {{}} differs from the English text: {key}")
                problems += 1
                continue
            catalog[key] = text.strip()
    current = set(keys)
    catalog = {k: catalog[k] for k in sorted(catalog) if k in current}
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        json.dump(catalog, out, ensure_ascii=False, indent=0, sort_keys=True)
        out.write("\n")
    print(f"{lang}: {len(catalog)} of {len(keys)} texts ({100 * len(catalog) // max(1, len(keys))}%), {problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
