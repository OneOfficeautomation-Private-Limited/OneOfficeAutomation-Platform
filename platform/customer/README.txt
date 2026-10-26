ONEOFFICEAUTOMATION

Start
  Double-click START-OOA.cmd. Your browser opens http://localhost:8720.
  The first time, sign in as admin with the one-time password shown in the window, then choose your own password.
  Java 21 or later must be installed.

Evaluation and license
  Without a license the product runs as a 30-day evaluation, counted from the first start, for 2 developers,
  2 attended runners and 2 unattended runners. A banner shows the days left.
  To license it, sign in as an administrator, open Administration > Licenses and install the license file you received.
  Then give people and runners their seats on the same page.
  To check a license file before installing it, run: bin\ooa.bat license show yourfile.lic

Database
  The product starts with a built-in database for evaluation. For production use Microsoft SQL Server:
  run bin\ooa.bat db configure, set-password and init (see the administrator guide).
