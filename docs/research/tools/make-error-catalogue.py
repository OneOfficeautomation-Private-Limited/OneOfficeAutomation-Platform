"""
Writes docs/reference/error-codes.md: every error code the platform raises, what it means and what to do, read from the
code (error batch E9, 13 Nov 2026). Run from the repository root:  python -I docs/research/tools/make-error-catalogue.py
CommandFixesTest fails when a code in the code is missing from the page, so new codes come with a fresh page.
"""
import os, re, sys
from collections import defaultdict

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..')
PLATFORM = os.path.join(ROOT, 'platform')
OUT = os.path.join(ROOT, 'docs', 'reference', 'error-codes.md')
NEW = re.compile(r'new (ActionException|OoaException)\(')
CONST = re.compile(r'(?:static\s+)?final\s+String\s+(\w+)\s*=\s*"([^"]*)"\s*;')

def args_of(s, i):
    depth, j, start, out, instr = 0, i, i, [], None
    while j < len(s):
        c = s[j]
        if instr:
            if c == '\\': j += 2; continue
            if c == instr: instr = None
        elif c in '"\'': instr = c
        elif c in '([{': depth += 1
        elif c in ')]}':
            if depth == 0: out.append(s[start:j]); return out
            depth -= 1
        elif c == ',' and depth == 0: out.append(s[start:j]); start = j + 1
        j += 1
    return out

def text_of(expr, consts):
    """The readable text of a Java string expression: its literals, with … where a value goes."""
    expr = expr.strip()
    if expr == 'null': return ''
    parts, out = re.split(r'("(?:[^"\\]|\\.)*")', expr), []
    for p in parts:
        if p.startswith('"') and p.endswith('"') and len(p) >= 2:
            out.append(bytes(p[1:-1], 'utf-8').decode('unicode_escape', errors='ignore') if '\\u' in p else p[1:-1].replace('\\"', '"').replace('\\\\', '\\'))
        else:
            q = p.strip().strip('+').strip()
            if not q: continue
            if q in consts: out.append(consts[q])
            elif out and out[-1] != '…': out.append('…')
            elif not out: out.append('…')
    return re.sub(r'\s+', ' ', ''.join(out)).strip()

def code_of(expr, consts):
    expr = expr.strip()
    m = re.fullmatch(r'"([A-Z][A-Z0-9_]*\.[A-Z0-9_.]+)"', expr)
    if m: return m.group(1)
    m = re.fullmatch(r'(\w+)\s*\+\s*"(\.[A-Z0-9_.]+)"', expr)
    if m and m.group(1) in consts: return consts[m.group(1)] + m.group(2)
    # a code with a number put after it, such as "AGENT.HTTP_" + status: listed by its start
    m = re.match(r'"([A-Z][A-Z0-9_]*\.[A-Z0-9_.]*_)"\s*\+', expr)
    if m: return m.group(1)
    return None

def module_of(path):
    rel = os.path.relpath(path, PLATFORM).replace(os.sep, '/')
    first = rel.split('/')[0]
    return rel.split('/')[0] + '/' + rel.split('/')[1] if first in ('toolkits', 'modules') else first

def scan():
    codes = defaultdict(lambda: {'messages': [], 'fixes': [], 'where': set()})
    for dp, dn, fn in os.walk(PLATFORM):
        d = dp.replace(os.sep, '/')
        if '/src/main/' not in d + '/' or '/build/' in d or 'node_modules' in d: continue
        for f in fn:
            if not f.endswith('.java'): continue
            p = os.path.join(dp, f)
            s = open(p, encoding='utf-8').read()
            consts = {m.group(1): m.group(2) for m in CONST.finditer(s)}
            for m in NEW.finditer(s):
                a = args_of(s, m.end())
                if len(a) < 2: continue
                code = code_of(a[0], consts)
                if not code: continue
                c = codes[code]
                msg = text_of(a[1], consts)
                fix = text_of(a[2], consts) if len(a) >= 3 else ''
                if msg and msg not in c['messages']: c['messages'].append(msg)
                if fix and fix not in c['fixes']: c['fixes'].append(fix)
                c['where'].add(module_of(p))
    return codes

def cell(t):
    return t.replace('|', '\\|').replace('{{', '{ {').replace('{%', '{ %').replace('\n', ' ')

def main():
    codes = scan()
    areas = defaultdict(list)
    for code in sorted(codes):
        areas[code.split('.')[0]].append(code)
    out = ['# Error codes', '',
           'Every error the platform raises, with what it means and what to do. A step\'s error shows its code (for example',
           '`EXCEL.IO_ERROR`), which a Try block\'s Catch can match; the run log and the console show the message and the fix.', '',
           'Generated from the code by `docs/research/tools/make-error-catalogue.py`; do not edit by hand.', '',
           '- **What it means** gives up to three of the messages the code is raised with (… stands for a value: a file, a name, a number).',
           '- **What to do** is the suggested fix. A command error without its own fix gets the one for its kind of code',
           '  (CommandFixes); an error One CommandCenter sends without one gets the one for its code where the API answers (ServerFixes).',
           '- An internal error of One CommandCenter shows a reference such as `ERR-…`: give it to an administrator, who finds the details beside it in the server log.', '',
           '%d codes in %d areas.' % (len(codes), len(areas)), '']
    out.append('Areas: ' + ', '.join('[%s](#%s)' % (a, a.lower()) for a in sorted(areas)))
    out.append('')
    for a in sorted(areas):
        out += ['## ' + a, '', '| Code | What it means | What to do | Where |', '|---|---|---|---|']
        for code in areas[a]:
            c = codes[code]
            means = '<br>'.join(cell(x) for x in c['messages'][:3]) or '—'
            fix = '<br>'.join(cell(x) for x in c['fixes'][:3]) or 'See the message; the fix for its kind of code applies.'
            out.append('| `%s` | %s | %s | %s |' % (code, means, fix, ', '.join(sorted(c['where']))))
        out.append('')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
    print(len(codes), 'codes ->', os.path.relpath(OUT, ROOT))

if __name__ == '__main__':
    main()
