ONEOFFICEAUTOMATION

Install (setup package, ooa-setup-<version>.zip: Java included)
  Unzip the package and double-click Install.cmd. It asks whether this computer is the Command Center or a runner,
  copies the product to your Programs folder (or Program Files with -AllUsers) and adds it to the Start menu.
  Silent install, for SCCM, Intune or Group Policy (run as administrator for -AllUsers or -Service):
    Install.cmd -Silent -Role CommandCenter -AllUsers -Service -Port 8720
    Install.cmd -Silent -Role Runner -AllUsers -Url https://ooa.example.com:8720 -RegistrationKey ooareg_... [-CaFile ooa.cer]
  With SQL Server from the start (passwords in environment variables, never on the command line):
    Install.cmd -Silent -Role CommandCenter -AllUsers -Service -DbServer SQL01 -DbPasswordEnv OOA_APP_PW -DbSetupPasswordEnv OOA_SETUP_PW
  Setup first checks the computer (bin\ooa.bat check); -SkipCheck installs anyway. Running it again over an
  installation updates it in place and upgrades the database.
  Exit code 0 means installed; the log is %TEMP%\ooa-install.log. Remove it in Apps and Features (or Uninstall-OOA.ps1
  -Silent); data folders are kept.

MSI (ooa-<version>.msi: Java included, all users, for SCCM, Intune or Group Policy)
  msiexec /i ooa-<version>.msi /qn ROLE=CommandCenter SERVICE=1 PORT=8720
  msiexec /i ooa-<version>.msi /qn ROLE=Runner URL=https://ooa.example.com:8720 REGISTRATION_KEY=ooareg_...
  Other properties: DATA_DIR, RUNNER_NAME, CA_FILE. Without ROLE only the files are installed. A runner installed by
  the MSI runs as a Windows service, for agents that need no screen; for desktop runners use Install.cmd as the user.
  msiexec /x ooa-<version>.msi /qn removes it and its services; data folders are kept.

Problems
  bin\ooa.bat check            is this computer ready for a Command Center
  bin\ooa.bat agent diagnose   why a runner cannot reach its Command Center (network, proxy, certificate, sign-in, clock)

Start (zip without the installer, ooa-customer-<version>.zip)
  Double-click START-OOA.cmd. Your browser opens http://localhost:8720.
  The first time, sign in as admin with the one-time password shown in the window, then choose your own password.
  This zip needs Java 21 or later installed; the setup package above brings its own.

Evaluation and license
  Without a license the product runs as a 30-day evaluation, counted from the first start, for 2 developers,
  2 attended runners and 2 unattended runners. A banner shows the days left.
  A licensed installation runs on Microsoft SQL Server: set it up first (see Database below), then sign in as an
  administrator and open Administration > Licenses. Send the install id shown there with your license request: the
  license you receive works on this Command Center only. Install the license file on the same page, then give people
  and runners their seats.
  To check a license file before installing it, run: bin\ooa.bat license show yourfile.lic

Database
  The product starts with a built-in database for evaluation. Before licensing, move to Microsoft SQL Server:
  run bin\ooa.bat db configure, set-password, set-password --setup, init, and copy --from your data folder
  (keeps the evaluation's data). Full steps: INSTALL.md in the release repository.
