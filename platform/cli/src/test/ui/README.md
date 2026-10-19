# Studio UI test

Drives the Studio page in jsdom against a real Studio server.

```
cd platform
.\gradlew.bat :cli:run --args="studio --workspace C:\temp\ooa-ui-test"     # in one window (use an empty folder)
npm install jsdom@24                                                       # once, in this folder
set STUDIO_DIR=..\..\main\resources\studio
set OOA_WS=C:\temp\ooa-ui-test
node studio.ui.test.js
```
