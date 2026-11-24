"""A small web shop sign-in page for the object repository unit test (UT Shop.objects.json): GET /plat-app/login.
Signing in shows "Welcome, <user>" in the message line; choosing a country shows it next to the form."""

PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><title>UT Shop - Sign in</title></head>
<body>
<h1 id="heading">UT Shop</h1>
<form id="login" onsubmit="return false">
  <label for="username">User name</label> <input id="username" name="user" type="text">
  <label for="password">Password</label> <input id="password" name="pass" type="password">
  <label for="country">Country</label>
  <select id="country" name="country"><option value="">-</option><option value="IN">India</option>
    <option value="US">United States</option><option value="DE">Germany</option></select>
  <button id="signin" type="button"
    onclick="document.getElementById('message').textContent='Welcome, '+document.getElementById('username').value+
    ' ('+document.getElementById('country').value+')'">Sign in</button>
</form>
<p id="message" data-test="message"></p>
</body></html>"""


def plat_app(h, m, path, q):
    if path == "/plat-app/login" and m == "GET":
        return h.send(200, PAGE, ctype="text/html")
    raise KeyError(path)


ROUTES = [("/plat-app/", plat_app, None)]
SEED = {}
