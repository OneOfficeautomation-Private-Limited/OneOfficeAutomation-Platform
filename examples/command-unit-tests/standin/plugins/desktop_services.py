"""Stand-in services for the desktop, browser, mail and transfer unit-test agents (helper "desktop").

HTTP routes (on the stand-in's port):
  /dsk/web/<page>                 test web pages for the Browser and Web goal agent cases (form, tables, pages, links)
  /dsk/sample/<file>/<n>/<name>   the "files" of command sample <n> (what the sample harness serves as {web})
  /dsk/ews/EWS/Exchange.asmx     a made-up Exchange (EWS) mailbox: two emails, folders Inbox/Invoices/Done
  /dsk/mock/<file>/<n>/...        replays the "mock" answers of command sample <n> of command-samples/<file>
  /dsk/ai/chat/completions        a scripted vision model (OpenAI-compatible) for the AI screen commands

Plain TCP services, started with the stand-in (skipped quietly when the port is taken, e.g. by a second stand-in):
  FTP on 127.0.0.1:2121 (user "agent", any password; files under <base>/standin/ftp-root)
  a VT100 host over Telnet on 127.0.0.1:2323 (login "agent", any password): a "Name:" prompt, a small menu screen
  and a "> " command line, as the platform's own terminal samples expect
"""
from __future__ import annotations

import json
import os
import posixpath
import re
import shutil
import socket
import socketserver
import threading
import time

core = None   # set by the stand-in before this module runs

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", "platform", "cli", "src", "test", "resources", "command-samples"))
FTP_PORT = int(os.environ.get("UT_FTP_PORT", "2121"))
TERM_PORT = int(os.environ.get("UT_TERMINAL_PORT", "2323"))

# ------------------------------------------------------------------------------------------------ web pages

FORM = """<!doctype html>
<html><head><meta charset="utf-8"><title>UT Order form</title>
<script>
function add(a, b) { return a + b; }
function greet(name) { return 'Hello ' + name; }
window.addEventListener('load', function () {
  setTimeout(function () { var d = document.createElement('div'); d.id = 'later'; d.textContent = 'Arrived'; document.body.appendChild(d); }, 1500);
});
function send() {
  var c = document.getElementById('color');
  document.getElementById('out').textContent = 'Thanks ' + document.getElementById('name').value + ' (' + c.options[c.selectedIndex].text + ')';
}
</script></head>
<body>
<h1 id="title">Order form</h1>
<p id="intro" class="lead" data-code="F-7">Fill in the order.</p>
<label for="name">Name</label> <input id="name" name="name" value="">
<label for="color">Colour</label>
<select id="color"><option value="r">Red</option><option value="g">Green</option><option value="b">Blue</option></select>
<label><input type="checkbox" id="agree"> I agree</label>
<button id="go" onclick="send()">Send</button>
<div id="out"></div>
<div id="hidden" style="display:none">Secret</div>
<table id="people"><tr><th>Name</th><th>City</th></tr><tr><td>Ann</td><td>Oslo</td></tr><tr><td>Bob</td><td>Rome</td></tr></table>
<ul id="items">
<li class="item"><span class="n">Pen</span> <span class="p">1.50</span></li>
<li class="item"><span class="n">Pad</span> <span class="p">3.20</span></li>
<li class="item"><span class="n">Ink</span> <span class="p">7.00</span></li>
</ul>
<a id="dl" href="report.csv">Download the report</a>
<a id="next" href="b.html">Next page</a>
<a id="newtab" href="b.html" target="_blank">Page B in a new tab</a>
<p><input type="file" id="upload"></p>
</body></html>
"""

PAGES = {
    "form.html": ("text/html", FORM),
    "b.html": ("text/html", "<!doctype html><html><head><title>UT Page B</title></head><body><h1 id='title'>Second page</h1><a id='back' href='form.html'>Back</a></body></html>"),
    "p1.html": ("text/html", "<!doctype html><html><head><title>UT List page 1</title></head><body><p class='row'>A1</p><p class='row'>A2</p><a id='next' href='p2.html'>Next</a></body></html>"),
    "p2.html": ("text/html", "<!doctype html><html><head><title>UT List page 2</title></head><body><p class='row'>B1</p><a id='next' href='p3.html'>Next</a></body></html>"),
    "p3.html": ("text/html", "<!doctype html><html><head><title>UT List page 3</title></head><body><p class='row'>C1</p></body></html>"),
    "l.html": ("text/html", "<!doctype html><html><head><title>UT Links</title></head><body><a href='ok.html'>ok</a><a href='missing.html'>bad</a></body></html>"),
    "ok.html": ("text/html", "fine"),
    "f.txt": ("text/plain", "hello"),
    "report.csv": ("text/csv", "id,amount\n1,10.50\n2,20.00\n"),
}


def web(h, m, path, q):
    name = path[len("/dsk/web/"):] or "form.html"
    if name not in PAGES:
        return h.send(404, "<html><body>Not found</body></html>", ctype="text/html")
    ctype, text = PAGES[name]
    headers = {"Content-Disposition": 'attachment; filename="report.csv"'} if name == "report.csv" else None
    return h.send(200, text, ctype=ctype, headers=headers)


# ------------------------------------------------------------------------------------------------ Exchange (EWS)

def _env(inner: str) -> str:
    return ('<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
            '<s:Body xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages" '
            'xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">' + inner + '</s:Body></s:Envelope>')


def _ok(op: str, inner: str = "") -> str:
    return _env(f'<m:{op}Response><m:ResponseMessages><m:{op}ResponseMessage ResponseClass="Success"><m:ResponseCode>NoError</m:ResponseCode>'
                f'{inner}</m:{op}ResponseMessage></m:ResponseMessages></m:{op}Response>')


_FROM = "<t:From><t:Mailbox><t:EmailAddress>billing@vendor.test</t:EmailAddress></t:Mailbox></t:From>"
_LIST = ('<m:RootFolder IncludesLastItemInRange="true"><t:Items>'
         '<t:Message><t:ItemId Id="AAMkID1" ChangeKey="CK"/><t:Subject>Invoice 42</t:Subject><t:DateTimeReceived>2026-11-03T09:15:00Z</t:DateTimeReceived>'
         '<t:HasAttachments>true</t:HasAttachments>' + _FROM + '<t:IsRead>false</t:IsRead></t:Message>'
         '<t:Message><t:ItemId Id="AAMkID2" ChangeKey="CK"/><t:Subject>Invoice 43</t:Subject><t:DateTimeReceived>2026-11-02T09:15:00Z</t:DateTimeReceived>'
         '<t:HasAttachments>false</t:HasAttachments>' + _FROM + '<t:IsRead>false</t:IsRead></t:Message>'
         '</t:Items></m:RootFolder>')
_FOLDERS = ('<m:RootFolder TotalItemsInView="3" IncludesLastItemInRange="true"><t:Folders>'
            '<t:Folder><t:FolderId Id="INBOXID"/><t:ParentFolderId Id="ROOTID"/><t:DisplayName>Inbox</t:DisplayName></t:Folder>'
            '<t:Folder><t:FolderId Id="INVID"/><t:ParentFolderId Id="INBOXID"/><t:DisplayName>Invoices</t:DisplayName></t:Folder>'
            '<t:Folder><t:FolderId Id="DONEID"/><t:ParentFolderId Id="INBOXID"/><t:DisplayName>Done</t:DisplayName></t:Folder>'
            '</t:Folders></m:RootFolder>')
_MIME = "U3ViamVjdDogSW52b2ljZSA0Mg0KDQpQbGVhc2UgcGF5"   # "Subject: Invoice 42\r\n\r\nPlease pay"


def _ews_answer(body: str) -> str:
    if "<m:GetFolder" in body:
        return _ok("GetFolder", '<m:Folders><t:Folder><t:FolderId Id="INBOXID"/></t:Folder></m:Folders>')
    if "<m:FindFolder" in body:
        return _ok("FindFolder", _FOLDERS)
    if "<m:CreateFolder" in body:
        return _ok("CreateFolder", '<m:Folders><t:Folder><t:FolderId Id="NEWFOLDERID"/></t:Folder></m:Folders>')
    if "<m:FindItem" in body:
        return _ok("FindItem", _LIST)
    if "<m:GetItem" in body and "IncludeMimeContent" in body:
        return _ok("GetItem", f'<m:Items><t:Message><t:MimeContent CharacterSet="UTF-8">{_MIME}</t:MimeContent>'
                              '<t:ItemId Id="AAMkID1" ChangeKey="CK"/><t:Subject>Invoice 42</t:Subject></t:Message></m:Items>')
    if "<m:GetItem" in body and "DRAFT1" in body:
        return _ok("GetItem", '<m:Items><t:Message><t:ItemId Id="DRAFT1" ChangeKey="CK9"/><t:ToRecipients>'
                              '<t:Mailbox><t:EmailAddress>billing@vendor.test</t:EmailAddress></t:Mailbox>'
                              '<t:Mailbox><t:EmailAddress>team@acme.test</t:EmailAddress></t:Mailbox></t:ToRecipients></t:Message></m:Items>')
    if "<m:GetItem" in body:
        if "AAMkGone" in body:
            return _env('<m:GetItemResponse><m:ResponseMessages><m:GetItemResponseMessage ResponseClass="Error"><m:MessageText>The specified object '
                        'was not found in the store.</m:MessageText><m:ResponseCode>ErrorItemNotFound</m:ResponseCode></m:GetItemResponseMessage>'
                        '</m:ResponseMessages></m:GetItemResponse>')
        second = "AAMkID2" in body
        att = ("" if second else '<t:Attachments><t:FileAttachment><t:AttachmentId Id="ATT1"/><t:Name>inv.pdf</t:Name></t:FileAttachment>'
               '<t:FileAttachment><t:AttachmentId Id="ATT2"/><t:Name>note.txt</t:Name></t:FileAttachment></t:Attachments>')
        return _ok("GetItem", f'<m:Items><t:Message><t:ItemId Id="{"AAMkID2" if second else "AAMkID1"}" ChangeKey="CK"/>'
                              f'<t:Subject>{"Invoice 43" if second else "Invoice 42"}</t:Subject>'
                              f'<t:Body BodyType="Text">Please pay</t:Body>{att}<t:DateTimeReceived>2026-11-03T09:15:00Z</t:DateTimeReceived>'
                              f'<t:HasAttachments>{"false" if second else "true"}</t:HasAttachments><t:IsRead>true</t:IsRead>{_FROM}</t:Message></m:Items>')
    if "<m:GetAttachment" in body:
        name, content = ("note.txt", "bm90ZQ==") if "ATT2" in body else ("inv.pdf", "JVBERi0xLjc=")
        return _ok("GetAttachment", f'<m:Attachments><t:FileAttachment><t:Name>{name}</t:Name><t:Content>{content}</t:Content></t:FileAttachment></m:Attachments>')
    if "<m:CreateItem" in body and 'MessageDisposition="SaveOnly"' in body:
        return _ok("CreateItem", '<m:Items><t:Message><t:ItemId Id="DRAFT1" ChangeKey="CK9"/></t:Message></m:Items>')
    if "<m:CreateItem" in body:
        return _ok("CreateItem", "<m:Items/>")
    if "<m:CreateAttachment" in body:
        return _ok("CreateAttachment", '<m:Attachments><t:FileAttachment><t:AttachmentId Id="A1" RootItemId="DRAFT1" RootItemChangeKey="CK10"/>'
                                       '</t:FileAttachment></m:Attachments>')
    if "<m:UpdateItem" in body:
        return _ok("UpdateItem", '<m:Items><t:Message><t:ItemId Id="DRAFT1" ChangeKey="CK11"/></t:Message></m:Items>')
    if "<m:SendItem" in body:
        return _ok("SendItem")
    if "<m:MoveItem" in body:
        return _ok("MoveItem", '<m:Items><t:Message><t:ItemId Id="AAMkNEW" ChangeKey="CK"/></t:Message></m:Items>')
    if "<m:DeleteItem" in body:
        return _ok("DeleteItem")
    return _env('<s:Fault><faultcode>s:Client</faultcode><faultstring>UT stand-in: operation not imitated</faultstring></s:Fault>')


def ews(h, m, path, q):
    if m != "POST":
        return h.send(405, "POST only", ctype="text/plain")
    body = h.raw().decode("utf-8", "replace")
    return h.send(200, _ews_answer(body), ctype="text/xml")


# ------------------------------------------------------------------------------------------------ sample mock replay

_SAMPLE_CACHE: dict = {}


def _sample(file: str, idx: int) -> dict:
    if file not in _SAMPLE_CACHE:
        with open(os.path.join(SAMPLES, file), encoding="utf-8") as fh:
            _SAMPLE_CACHE[file] = json.loads(fh.read(), strict=False)
    return _SAMPLE_CACHE[file][idx]


def mock(h, m, path, q):
    """/dsk/mock/<file>/<idx>/<rest>: the first of the sample's mock routes whose method, path and "contains" fit."""
    parts = path.split("/", 5)          # '', 'dsk', 'mock', file, idx, rest
    if len(parts) < 5:
        raise KeyError(path)
    s = _sample(parts[3], int(parts[4]))
    rest = "/" + (parts[5] if len(parts) > 5 else "")
    body = h.raw().decode("utf-8", "replace")
    for rt in s.get("mock") or []:
        want = str(rt.get("path", "*"))
        path_ok = rest.startswith(want[:-1]) if want.endswith("*") else rest == want
        body_ok = not isinstance(rt.get("contains"), str) or rt["contains"] in body
        if path_ok and body_ok and str(rt.get("method", m)).upper() == m:
            b = rt.get("body", {})
            if isinstance(b, str):
                return h.send(int(rt.get("status", 200)), b, ctype="text/plain")
            return h.send(int(rt.get("status", 200)), b)
    return h.send(404, {"error": "no mock route"})


_TYPES = {".html": "text/html", ".htm": "text/html", ".txt": "text/plain", ".csv": "text/csv", ".json": "application/json",
          ".js": "text/javascript", ".css": "text/css", ".xml": "text/xml"}


def sample_files(h, m, path, q):
    """/dsk/sample/<file>/<idx>/<name>: the files of command sample <idx> (its "files"), as the sample harness serves
    them for {web}; a file the sample does not have is 404 (broken-link cases rely on that)."""
    parts = path.split("/", 5)
    if len(parts) < 6:
        raise KeyError(path)
    files = _sample(parts[3], int(parts[4])).get("files") or {}
    name = parts[5]
    if name not in files:
        return h.send(404, "<html><body>Not found</body></html>", ctype="text/html")
    ext = os.path.splitext(name)[1].lower()
    ctype = _TYPES.get(ext, "application/octet-stream")
    headers = None if ext in (".html", ".htm") else {"Content-Disposition": 'attachment; filename="' + os.path.basename(name) + '"'} \
        if ext in (".csv", ".pdf", ".zip", ".xlsx") else None
    return h.send(200, files[name], ctype=ctype, headers=headers)


# ------------------------------------------------------------------------------------------------ scripted vision model

def vision(h, m, path, q):
    if not path.endswith("/chat/completions") or m != "POST":
        raise KeyError(path)
    b = h.body() if isinstance(h.body(), dict) else {}
    msgs = b.get("messages", [])

    def text_of(c):
        if isinstance(c, list):
            return " ".join(str(p.get("text", "")) for p in c if isinstance(p, dict))
        return str(c or "")
    user = " ".join(text_of(x.get("content")) for x in msgs if x.get("role") == "user")
    missing = re.search(r"(?i)\b(nothing|missing|does not exist)\b", user)
    answer = {"found": False, "left": 0, "top": 0, "width": 0, "height": 0, "confidence": 0.1, "label": "", "text": ""} if missing else \
        {"found": True, "left": 0.45, "top": 0.45, "width": 0.1, "height": 0.1, "confidence": 0.93, "label": "UT scripted control", "text": "UT screen text"}
    content = json.dumps(answer)
    return h.send(200, {"id": "chatcmpl-ut", "object": "chat.completion", "model": b.get("model", "ut-vision"),
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 100, "completion_tokens": 20}})


ROUTES = [("/dsk/web/", web, None), ("/dsk/sample/", sample_files, None),("/dsk/ews/", ews, None), ("/dsk/mock/", mock, None), ("/dsk/ai/", vision, None)]


# ------------------------------------------------------------------------------------------------ FTP server

def _ftp_root() -> str:
    base = getattr(core, "BASE", os.path.join(os.path.expanduser("~"), "OOA Unit tests"))
    root = os.path.join(base, "standin", "ftp-root")
    os.makedirs(root, exist_ok=True)
    return root


class _Ftp(socketserver.StreamRequestHandler):
    def reply(self, text: str):
        self.wfile.write((text + "\r\n").encode("utf-8"))
        self.wfile.flush()

    def real(self, arg: str) -> str | None:
        p = posixpath.normpath(posixpath.join(self.cwd, arg or "."))
        if not p.startswith("/"):
            p = "/" + p
        full = os.path.normpath(os.path.join(self.root, p.lstrip("/")))
        return full if full == self.root or full.startswith(self.root + os.sep) else None

    def data(self):
        if not self.pasv:
            return None
        self.pasv.settimeout(15)
        try:
            c, _ = self.pasv.accept()
        finally:
            self.pasv.close()
            self.pasv = None
        return c

    @staticmethod
    def line(name, full):
        st = os.stat(full)
        d = os.path.isdir(full)
        t = time.strftime("%b %d %H:%M", time.gmtime(st.st_mtime))
        return f"{'d' if d else '-'}rw-r--r-- 1 agent agent {0 if d else st.st_size:>10} {t} {name}"

    def handle(self):
        self.root = os.path.normpath(_ftp_root())
        self.cwd = "/"
        self.pasv = None
        self.rnfr = None
        self.reply("220 UT stand-in FTP ready")
        while True:
            raw = self.rfile.readline()
            if not raw:
                break
            text = raw.decode("utf-8", "replace").rstrip("\r\n")
            cmd, _, arg = text.partition(" ")
            cmd = cmd.upper()
            try:
                if not self.dispatch(cmd, arg):
                    break
            except OSError as e:
                self.reply("550 " + str(e).replace("\n", " ")[:200])

    def dispatch(self, cmd, arg):
        r = self.reply
        if cmd == "USER":
            r("331 Password please")
        elif cmd == "PASS":
            r("230 Signed in")
        elif cmd == "SYST":
            r("215 UNIX Type: L8")
        elif cmd == "FEAT":
            self.wfile.write(b"211-Features:\r\n MDTM\r\n MFMT\r\n SIZE\r\n UTF8\r\n211 End\r\n")
        elif cmd in ("OPTS", "TYPE", "MODE", "STRU", "NOOP", "ALLO"):
            r("200 OK")
        elif cmd == "PWD":
            r(f'257 "{self.cwd}" is the current folder')
        elif cmd in ("CWD", "CDUP"):
            target = ".." if cmd == "CDUP" else arg
            full = self.real(target)
            if full and os.path.isdir(full):
                self.cwd = "/" + os.path.relpath(full, self.root).replace("\\", "/").lstrip(".").lstrip("/")
                r("250 Folder changed")
            else:
                r("550 No such folder")
        elif cmd in ("PASV", "EPSV"):
            s = socket.socket()
            s.bind(("127.0.0.1", 0))
            s.listen(1)
            self.pasv = s
            port = s.getsockname()[1]
            if cmd == "PASV":
                r(f"227 Entering Passive Mode (127,0,0,1,{port >> 8},{port & 255})")
            else:
                r(f"229 Entering Extended Passive Mode (|||{port}|)")
        elif cmd in ("LIST", "NLST", "MLSD"):
            a = " ".join(x for x in arg.split() if not x.startswith("-"))
            full = self.real(a)
            if not full or not os.path.exists(full):
                c = self.data()
                if c:
                    c.close()
                r("550 No such file or folder")
                return True
            names = sorted(os.listdir(full)) if os.path.isdir(full) else [os.path.basename(full)]
            folder = full if os.path.isdir(full) else os.path.dirname(full)
            r("150 Here comes the listing")
            c = self.data()
            out = []
            for n in names:
                p = os.path.join(folder, n)
                out.append(n if cmd == "NLST" else self.line(n, p))
            if c:
                c.sendall(("".join(x + "\r\n" for x in out)).encode("utf-8"))
                c.close()
            r("226 Listing sent")
        elif cmd == "RETR":
            full = self.real(arg)
            if not full or not os.path.isfile(full):
                c = self.data()
                if c:
                    c.close()
                r("550 No such file")
                return True
            r("150 Sending")
            c = self.data()
            with open(full, "rb") as fh:
                c.sendall(fh.read())
            c.close()
            r("226 Sent")
        elif cmd in ("STOR", "APPE"):
            full = self.real(arg)
            if not full or not os.path.isdir(os.path.dirname(full)):
                c = self.data()
                if c:
                    c.close()
                r("553 Cannot store there")
                return True
            r("150 Ready")
            c = self.data()
            with open(full, "ab" if cmd == "APPE" else "wb") as fh:
                while True:
                    chunk = c.recv(65536)
                    if not chunk:
                        break
                    fh.write(chunk)
            c.close()
            r("226 Stored")
        elif cmd == "DELE":
            full = self.real(arg)
            if full and os.path.isfile(full):
                os.remove(full)
                r("250 Deleted")
            else:
                r("550 No such file")
        elif cmd == "MKD":
            full = self.real(arg)
            if full and os.path.isdir(full):
                r(f'257 "{arg}" is there already')        # kept from an earlier run: the cases may run again
            elif full and not os.path.exists(full) and os.path.isdir(os.path.dirname(full)):
                os.mkdir(full)
                r(f'257 "{arg}" created')
            else:
                r("550 Cannot create folder")
        elif cmd == "RMD":
            full = self.real(arg)
            if full and os.path.isdir(full) and not os.listdir(full) and full != self.root:
                os.rmdir(full)
                r("250 Folder removed")
            else:
                r("550 Cannot remove folder")
        elif cmd == "RNFR":
            full = self.real(arg)
            if full and os.path.exists(full):
                self.rnfr = full
                r("350 Ready for the new name")
            else:
                r("550 No such file")
        elif cmd == "RNTO":
            full = self.real(arg)
            if self.rnfr and full:
                shutil.move(self.rnfr, full)
                self.rnfr = None
                r("250 Renamed")
            else:
                r("503 RNFR first")
        elif cmd == "SIZE":
            full = self.real(arg)
            if full and os.path.isfile(full):
                r(f"213 {os.path.getsize(full)}")
            else:
                r("550 No such file")
        elif cmd == "MDTM":
            full = self.real(arg)
            if full and os.path.exists(full):
                r("213 " + time.strftime("%Y%m%d%H%M%S", time.gmtime(os.path.getmtime(full))))
            else:
                r("550 No such file")
        elif cmd == "MFMT":
            stamp, _, name = arg.partition(" ")
            full = self.real(name)
            if full and os.path.exists(full):
                t = time.mktime(time.strptime(stamp[:14], "%Y%m%d%H%M%S")) - time.timezone
                os.utime(full, (t, t))
                r(f"213 Modify={stamp[:14]}; {name}")
            else:
                r("550 No such file")
        elif cmd == "STAT":
            r("211 UT stand-in FTP, all fine")
        elif cmd == "HELP":
            r("214 Commands: USER PASS PWD CWD LIST RETR STOR DELE MKD RMD RNFR RNTO SIZE MDTM NOOP QUIT")
        elif cmd == "SITE":
            r("200 SITE command accepted")
        elif cmd == "QUIT":
            r("221 Bye")
            return False
        else:
            r("502 Command not implemented")
        return True


# ------------------------------------------------------------------------------------------------ VT100 host (Telnet)

class _Vt(socketserver.BaseRequestHandler):
    def w(self, s: str):
        self.request.sendall(s.encode("utf-8"))

    def byte(self) -> int:
        while True:
            b = self.request.recv(1)
            if not b:
                raise EOFError
            c = b[0]
            if c != 255:
                return c
            cmd = self.request.recv(1)[0]          # telnet negotiation from the client: skip it
            if cmd in (251, 252, 253, 254):
                self.request.recv(1)
            elif cmd == 250:
                prev = 0
                while True:
                    x = self.request.recv(1)[0]
                    if prev == 255 and x == 240:
                        break
                    prev = x
            elif cmd == 255:
                return 255

    def line(self, echo=True) -> str:
        buf = []
        while True:
            c = self.byte()
            if c == 13:
                return "".join(buf)
            if c in (10, 0):
                continue
            if c == 27:
                n = self.byte()
                if n in (91, 79):              # ESC [ x or ESC O x
                    k = self.byte()
                    if k == 67:
                        self.w("\033[C")
                    elif k == 68:
                        self.w("\033[D")
                continue
            if c == 3:
                self.w("^C")
            elif c in (8, 127):
                if buf:
                    buf.pop()
                    if echo:
                        self.w("\b \b")
            elif c >= 32:
                buf.append(chr(c))
                if echo:
                    self.w(chr(c))

    def handle(self):
        try:
            self.request.sendall(bytes([255, 251, 1, 255, 251, 3]))   # WILL ECHO, WILL SUPPRESS-GO-AHEAD
            self.w("UT stand-in VT host\r\nlogin: ")
            self.line()
            self.w("\r\nPassword: ")
            self.line(echo=False)
            self.w("\r\n\033[2J\033[HSAMPLE HOST\r\nName: ")
            name = self.line()
            self.w("\r\n\033[2J\033[1;1HMAIN MENU\033[3;1HUser: " + name + "\033[5;1HCode:\033[5;1H")
            code = self.line()
            self.w("\033[7;1HResult: " + code + "\033[9;1H> ")
            while True:
                cmd = self.line()
                if cmd == "exit":
                    break
                self.w("\r\nYou typed: " + cmd + "\r\n> ")
            self.w("\r\nbye\r\n")
        except (EOFError, OSError, IndexError):
            pass


class _Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False


def _start(port, handler, what):
    try:
        srv = _Server(("127.0.0.1", port), handler)
    except OSError:
        return None                       # already served (another stand-in has it)
    threading.Thread(target=srv.serve_forever, name="ut-" + what, daemon=True).start()
    return srv


_start(FTP_PORT, _Ftp, "ftp")
_start(TERM_PORT, _Vt, "vt-host")
