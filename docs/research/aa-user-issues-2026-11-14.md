# AA 360: problems real users keep reporting (research, 14 Nov 2026)

Sources: A-People community forum (community.automationanywhere.com), AA docs, PeerSpot / G2 / Capterra review summaries, one public user script. Reddit and Stack Overflow had almost nothing on A360 itself. G2 blocked direct page fetches, so G2 figures come from search snippets.
Frequency: **many** = 5+ separate threads or reviews; **several** = 3-4; **few** = 1-2. Search hit counts are a rough guide only.

## A. Bot editor

1. **Child bot outputs do not arrive in the parent.** If the child bot fails, or a variable is not marked as output, the parent's mapped variable stays empty without any warning. People get around it by passing a dictionary in "by reference", or by deleting and re-adding the Run Task action. **Many.**
   - https://community.automationanywhere.com/developers-forum-36/child-bot-output-variable-doesn-t-go-to-a-mapped-parent-bot-variable-90487
   - https://community.automationanywhere.com/developers-forum-36/output-variable-from-child-bot-to-parent-bot-20241
   - **What we should do:** Return outputs even when the child fails (with an error flag). In the mapping panel, show a red warning when a mapped variable is not marked input/output in the child.

2. **Mapping breaks when the child changes.** A copied Run Task action, or a child whose inputs were renamed or deleted, keeps old mappings. AARI bots stopped triggering after a child's input variables were deleted. **Several.**
   - https://community.automationanywhere.com/developers-forum-36/variable-mapping-issue-with-migrated-bot-85738
   - https://community.automationanywhere.com/developers-forum-36/bug-identified-aari-bots-will-not-trigger-when-child-task-bot-input-variables-are-deleted-90176
   - **What we should do:** Check every parent's mapping on save and on check-in, and show "child changed: 2 mappings broken" with a one-click Remap.

3. **Variable type mismatches and fiddly conversion.** Values such as "expected String, received Double", and number-to-text conversions that silently drop decimals or fail on commas, are a constant source of questions. **Many.**
   - https://community.automationanywhere.com/developers-forum-36/is-there-an-easier-way-to-parse-numbers-to-strings-and-vice-versa-88625
   - https://community.automationanywhere.com/developers-forum-36/type-conversion-from-any-to-string-88619
   - **What we should do:** Convert simple types automatically where it is safe, flag a type mismatch at design time with a fix button, and never drop decimals without saying so.

4. **No real "where used" or rename for variables.** The editor offers text search and replace with the same type only. A forum user could not tell arguments from local variables without clicking each one. **Few to several.** (Uncertain whether newer builds added this.)
   - https://docs.automationanywhere.com/bundle/enterprise-v2019/page/enterprise-cloud/topics/aae-client/bot-creator/using-the-workbench/search-and-replace.html
   - https://community.automationanywhere.com/developers-forum-36/how-to-differentiate-variables-and-arguments-from-task-bot-flow-88402
   - **What we should do:** Add a Rename that updates every use, a "Used in N steps" link, and badges in the variable list (In / Out / Local).

5. **Copying actions between bots is awkward.** Users need a separate "shared clipboard". Variables and dependencies such as capture images do not come along, and copying failed between bots on different versions. A community user script exists just to copy actions across Control Rooms. **Several.**
   - https://community.automationanywhere.com/developers-forum-36/paste-from-shared-clipboard-error-2402
   - https://community.automationanywhere.com/developers-forum-36/a360-unable-to-copy-one-bot-to-another-bot-86711
   - https://greasyfork.org/hu/scripts/477891-better-automationanywhere
   - **What we should do:** Make normal Ctrl+C / Ctrl+V work across bots. When pasting, offer to create missing variables and bring dependencies along.

6. **Large bots slow the editor or break.** Users report lag, crashes at high line numbers and advice to keep bots under about 500 lines. Separately, bots too big to check out within 30 minutes are covered in item 17. **Several.**
   - https://community.automationanywhere.com/developers-forum-36/a360-very-slow-whilst-in-development-85763
   - https://community.automationanywhere.com/developers-forum-36/crash-in-line-0-or-random-line-90225
   - **What we should do:** Make the editor fast at 2,000+ steps (virtualised list), warn about size well before a hard limit, and offer an "Extract to child bot" refactor.

7. **Editor crashes or lags in some browser versions.** For example, Chrome and Edge v124 crashed when a variable drop-down was opened. **Few.**
   - https://community.automationanywhere.com/developers-forum-36/a360-task-bot-editor-crashes-when-trying-to-select-any-variables-in-a-dropdown-on-any-package-88089
   - **What we should do:** Run automated UI tests against browser beta channels, and use plain drop-downs rather than heavy custom widgets.

8. **Fields are hard to read, and cramped on big or small screens.** Long expressions sit in one-line inputs that scroll sideways, and picker buttons are small. Reviewers find the editor cramped for long bots. A community user script exists to wrap input fields, enlarge picker buttons and make the folder sidebar scroll. **Several.**
   - https://greasyfork.org/hu/scripts/477891-better-automationanywhere
   - https://www.capterra.com/p/124575/Web-Data-Extraction/reviews/?page=2
   - **What we should do:** Use multi-line fields that grow with long values, picker buttons with text labels, panels that scroll, and a readable font for expressions.

9. **Debugging is weak.** Reviewers repeatedly name debugging as the main pain. Watched values show only for bots you stepped into, and there is no easy way to start from the middle of a bot. **Many** (in reviews).
   - https://www.peerspot.com/products/automation-anywhere-pros-and-cons
   - https://docs.automationanywhere.com/bundle/enterprise-v2019/page/watched-variables.html
   - https://community.automationanywhere.com/developers-forum-36/a360-debugging-assistant-89166
   - **What we should do:** Show every variable's live value in the debugger (child bots included), support "Run from here" and "Run to here", and let users edit a value while paused.

10. **Error line numbers do not match what you see.** In flow view, line numbers make little sense, so users switch to list view to find the failing action. Error variables must be wired by hand in each Catch. **Several.**
    - https://community.automationanywhere.com/developers-blog-85009/learning-to-use-the-error-handler-package-85042
    - https://community.automationanywhere.com/developers-forum-36/aa-360-error-handling-questions-11593
    - **What we should do:** Errors name the bot, the step label and the path through child bots, and are clickable to jump to that step. Catch provides built-in error variables.

11. **Error handling is hard to set up.** Reviewers say setting up error handling needs experience and is not user friendly. **Several** (reviews).
    - https://aws.amazon.com/marketplace/reviews/reviews-list/B085L5P311?page=52
    - **What we should do:** Offer ready-made error-handling patterns (retry N times, screenshot plus log, then continue or stop) as a one-click wrapper around selected steps.

12. **No usable version history or rollback for a bot.** A360 shows check-in history but has no clear compare or rollback (v11 had both). Users fall back on Git, and deleted public bots cannot be restored. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/a360-version-history-86175
    - https://community.automationanywhere.com/developers-forum-36/i-deleted-the-a360-bot-in-public-space-and-now-is-all-gone-how-do-i-restore-it-85687
    - **What we should do:** Every check-in becomes a version you can compare side by side and restore with one click. Deleted items go to a recycle bin.

13. **Undo is unreliable (uncertain).** No A360 thread specifically on broken undo turned up. A related thread reports bots that became impossible to edit and were fixed only by export and re-import. **Few.**
    - https://community.automationanywhere.com/community-edition-85050/unable-to-make-changes-in-few-existing-automations-89590
    - **What we should do:** Make undo and redo cover every edit, including property changes and paste. Save drafts automatically, and offer a self-repair for a damaged bot file.

## B. Recorder, capture and UI objects

14. **Capture does not work because the browser extension is missing, disabled or out of date.** The editor says the extension is not installed when it is. Edge disables it after updates, versions differ between Chrome and Edge, and the move to MV3 broke setups. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/a360-google-chrome-plugin-issue-20198
    - https://community.automationanywhere.com/developers-forum-36/automation-360-mv3-plugin-in-edge-gets-disabled-automatically-90007
    - https://community.automationanywhere.com/developers-forum-36/extension-not-working-in-edge-was-working-previously-unable-to-read-browser-from-list-i-verified-that-it-works-in-chrome-90832
    - **What we should do:** Add a built-in "Check my setup" that tests each browser and extension version and gives a Fix button. Do not depend on the extension where CDP or UI Automation can do the job.

15. **Captured objects stop being found (dynamic IDs, timing).** Elements are found intermittently, timeouts seem longer than the value set, and users have to add wildcards by hand. A separate validator extension exists just to see which locator failed. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/in-automation-anywhere-a360-the-recorder-action-is-not-capturing-the-object-getting-error-85375
    - https://community.automationanywhere.com/developers-forum-36/recorder-capture-action-inside-the-try-block-taking-lots-of-wait-time-than-usual-wait-time-given-in-the-action-5509
    - https://chromewebstore.google.com/detail/a360-element-validator/nblgkinmfcibcjmjidmkhogbmhhfhaan
    - **What we should do:** Save several locators per object with automatic fallback, flag unstable attributes when capturing, and include a "Test object now" button that shows which locator matched.

16. **Image and region capture breaks when screen resolution or scaling changes.** This affects creator vs runner machines, RDP sessions, Citrix and DPI scaling. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/a11-image-recognition-captures-different-area-each-time-85707
    - https://community.automationanywhere.com/developers-forum-36/aa360-production-issue-image-recognition-and-web-tables-failing-on-2-production-vms-91438
    - **What we should do:** Record the capture resolution and DPI with each object, and warn at deploy time when the runner's differs. Scale images to match where possible.

## C. Check-in, check-out, public and private, dependencies

17. **The public/private workspace and check-out model confuses people.** Users ask what the public folder is for, why a private bot cannot call a public child, why check-in "succeeded" but nothing appears in public, and why dependencies were cloned. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/what-s-the-public-folder-for-and-how-to-run-bots-from-there-87343
    - https://community.automationanywhere.com/developers-forum-36/error-after-check-in-private-not-display-to-public-85704
    - https://community.automationanywhere.com/developers-forum-36/issues-trying-to-check-in-a-subbot-with-it-depedendences-86826
    - https://docs.automationanywhere.com/bundle/enterprise-v2019/page/enterprise-cloud/topics/aae-client/bot-creator/working-with-automation-tasks/check-out-bot-and-dependencies.html
    - **What we should do:** Show one bot with clear states (Draft / Checked out by X / Published vX), let a draft call published children directly, and show a check-in summary that lists exactly what changed.

18. **Check-in or check-out fails because of folder permissions on dependencies.** The error says the bot "or its dependencies" lack permission without naming which one. Check-in can also get stuck "in progress". **Several.**
    - https://community.automationanywhere.com/developers-forum-36/unable-to-check-in-the-bots-in-automation-360-89098
    - https://community.automationanywhere.com/developers-forum-36/please-wait-for-the-check-in-to-complete-code-repository-files-checkin-in-progress-91342
    - **What we should do:** Name the exact file and folder and the missing permission, and offer a "Request access" link. Let an admin clear a stuck check-in.

19. **"Unable to download the bot or the dependencies."** Runs fail with a generic message. The cause is often a missing or moved dependency, a broken child link, a proxy, or disk space. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/unable-to-download-the-bot-or-the-dependencies-to-the-device-90608
    - https://community.automationanywhere.com/developers-forum-36/the-bot-runs-on-botcreator-without-error-but-when-running-it-on-botrunner-it-does-not-start-to-run-why-86833
    - **What we should do:** Check dependencies before deploying, and name the missing file, folder, URL or proxy step and the fix.

## D. Packages and versions

20. **Package versions are pinned per bot, and updating them is manual and repetitive.** New package versions do not reach existing bots. Bulk update arrived late, works only in some places, and needs a full retest. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/after-an-update-is-it-possible-to-update-all-packages-to-their-latest-level-in-all-bots-at-once-or-must-i-edit-all-bots-select-all-packages-and-select-to-use-the-latest-version-17509
    - https://community.automationanywhere.com/developers-forum-36/solution-to-automatically-update-bot-packages-90828
    - **What we should do:** Add a package version report (bot by package), a safe bulk update with a dry run, and a test-run list after updating.

21. **Bots break after a platform or agent upgrade.** Mismatched Control Room and agent versions stop bots running, preprocessing errors appear after an upgrade, and upgrades are not communicated well. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/bots-are-unable-to-run-after-control-room-upgrade-to-v23-in-a360-85497
    - https://community.automationanywhere.com/developers-forum-36/preprocessing-issue-after-upgrade-on-prem-aa-single-node-from-v35-build-29058-to-v37-build-37379-90379
    - **What we should do:** Run a compatibility check before upgrading, keep agents backward compatible for N versions, show a clear version badge on each device, and publish release notes for each upgrade.

## E. Bot agent, devices, unattended runs

22. **Device shows disconnected, or connects and drops.** This is the single most common forum topic. Fixes are trial and error: restart the service, delete AA-DB files, match time zones, check the proxy, reinstall. **Many** (dozens of threads).
    - https://community.automationanywhere.com/developers-forum-36/unable-to-connect-bot-agent-10530
    - https://community.automationanywhere.com/developers-forum-36/hi-all-my-client-is-switching-constantly-from-being-connected-to-being-disconnected-18456
    - https://community.automationanywhere.com/developers-forum-36/a360-bot-agent-won-t-connect-after-install-and-the-installer-also-has-a-bug-89162
    - **What we should do:** Add a device health page and an agent self-test (network, proxy, clock skew, certificate, service state) that gives a plain cause and a fix. Reconnect automatically, and keep a connection history.

23. **Bot works when someone watches it but fails unattended.** Causes include locked or minimised RDP sessions, black screenshots, auto-login failing, and idle-session policies. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/bot-runs-whilst-watching-but-fails-unattended-87872
    - https://community.automationanywhere.com/developers-forum-36/bots-failing-to-locate-elements-unattended-mode-black-screenshots-captured-89865
    - https://community.automationanywhere.com/developers-forum-36/keystrokes-not-working-when-rdp-window-is-minimized-or-disconnected-14379
    - **What we should do:** Run a pre-run session check (desktop unlocked, resolution, user logged in) with a clear message, and offer a "rehearse unattended" run from the editor.

24. **Run-as user, device credentials and auto-login are confusing.** Users hit errors like "unexpected error setting up a new user session", attest device credentials again and again, and run into console-user conflicts. **Several.**
    - https://docs.automationanywhere.com/bundle/enterprise-v2019/page/enterprise-cloud/topics/aae-client/bot-creator/working-with-automation-tasks/cloud-troubleshoot-new-user-session.html
    - https://community.automationanywhere.com/developers-forum-36/i-still-get-the-error-unknown-user-username-is-already-running-a-bot-on-the-device-if-the-problem-persists-contact-your-system-administrator-can-anybody-help-me-10555
    - **What we should do:** Add one "Run as" setup page per device with a Test login button, and give errors that name the user, the device and what failed.

25. **Runs stuck in Queued, Pending, Deploying or "Ready to run".** Users cannot cancel some of them (permissions), and restarting the agent is the only fix. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/bot-stuck-at-queued-status-in-in-progress-section-and-not-able-to-connect-and-disconnect-the-bot-agent-in-community-edition-of-a360-18345
    - https://community.automationanywhere.com/developers-forum-36/event-stuck-in-reconcile-how-to-prevent-87388
    - **What we should do:** Show why each run is waiting ("waiting for device X, busy with run Y"), set a time limit on each state, and give owners and admins a force-cancel.

## F. Scheduling, triggers and work queues

26. **Schedules silently do not fire.** Causes include time-zone or DR-site differences, missing schedules, and stuck queued events. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/bot-scheduling-not-working-in-a360-enterprise-87060
    - https://community.automationanywhere.com/developers-forum-36/bot-schedules-are-missing-to-run-in-a360-85498
    - **What we should do:** Show the next 5 run times with their time zone, record every skipped run with a reason, and alert on a missed schedule.

27. **Triggers fire only when someone is logged in, or work intermittently.** This affects file triggers and triggers on unattended runners. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/triggers-not-working-for-unattended-bot-runner-license-in-server-85444
    - https://community.automationanywhere.com/developers-forum-36/file-trigger-works-most-of-the-time-not-sure-if-something-is-timing-out-or-if-its-a-vm-setting-10630
    - **What we should do:** Keep a trigger status page (listening / last fired / last error), and state up front which triggers need a session.

28. **Work items stay in New, Ready to run or In progress.** Causes include the device pool, consumers, the run-as user's permissions and a paused queue. AA needed a whole FAQ for it. **Several.**
    - https://docs.automationanywhere.com/bundle/enterprise-v2019/page/wlm-faq.html
    - https://community.automationanywhere.com/developers-forum-36/stuck-in-ready-to-run-status-when-running-bot-with-queue-85756
    - https://community.automationanywhere.com/developers-forum-36/work-item-can-not-be-deployed-to-device-pool-86098
    - **What we should do:** Add a "Why is this item not moving?" panel that checks pool, device, consumer, permission and pause, plus clear retry for failed items.

## G. Credentials and lockers

29. **Bot cannot read a credential because the runner is not a locker consumer.** It works for the developer but fails when deployed or run through AARI. The roles needed (consumer, plus update and access rights) are not obvious. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/unable-to-fetch-credentials-from-particular-locker-88244
    - https://community.automationanywhere.com/developers-forum-36/access-to-credential-vault-fails-when-called-from-aari-succeeds-when-launched-manually-90016
    - **What we should do:** At deploy time, check that the run-as user can read every credential the bot uses. Name the locker and the missing role in the message.

30. **Credentials vanish, cannot be updated, or "could not be found".** Some were never added to a locker. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/credentials-has-disappeared-from-the-list-after-i-received-some-errors-87651
    - https://community.automationanywhere.com/developers-forum-36/unable-to-update-the-credential-in-credential-vault-90293
    - **What we should do:** Show each credential's locker, owner and "used by" bots. Do not allow a credential with no locker, and log every change.

## H. Licences and devices

31. **Licence and device-type rules block runs with unclear messages.** Examples: a user-specific device cannot be the default device, unattended runs need an unattended-licence device, a device is held by another user, and AARI needs an attended licence. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/unable-to-add-default-device-11525
    - https://community.automationanywhere.com/developers-forum-36/bot-failing-87609
    - https://community.automationanywhere.com/developers-forum-36/when-trying-to-schedule-bot-there-are-no-bot-runners-available-how-do-we-configure-the-product-so-we-see-bot-runners-10676
    - **What we should do:** Show a plain table of who can run what where, with a "Why can't I pick this device?" explanation.

32. **Licensing is costly and inflexible.** This is a common review complaint, especially for unattended runners and AI add-ons. **Many** (reviews).
    - https://www.peerspot.com/products/automation-anywhere-pros-and-cons
    - **What we should do:** Keep the seat model simple and visible in the product (our licence work already covers this).

## I. Error messages and logs

33. **Generic errors with no fix.** Examples include "deploy failed", details: null, "unknown error ... contact your system administrator" and "BA bot execution error without reason". Users are sent to the audit log, Node Manager logs or a diagnostic exe. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/deploy-failed-in-aa-88338
    - https://community.automationanywhere.com/community-edition-85050/recorder-capture-and-recorder-actions-fail-with-ba-bot-execution-error-without-reason-91642
    - https://community.automationanywhere.com/developers-forum-36/an-error-occurred-when-closing-the-workbook-to-continue-contact-your-system-administrator-85681
    - **What we should do:** Every error carries a code, cause, fix and docs link. Never show "contact your administrator" without telling the admin what to do.

34. **The failure reason is spread across several places.** It can be in Activity history, the audit log, agent log files on the runner or Python output mixed into runner logs. **Several.**
    - https://docs.automationanywhere.com/bundle/enterprise-v2019/page/bot-agent-log-files-mos.html
    - https://community.automationanywhere.com/developers-forum-36/where-are-the-python-logs-stored-89925
    - **What we should do:** Give each run one page with the step timeline, the error, screenshots, agent log lines and script output, all reachable from the run's row.

## J. Excel, email and app packages

35. **Excel sessions break bots.** "Unable to create session X as it already exists" and "unable to find any open session Default" are common. Sessions leak between child bots and through error paths. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/error-unable-to-find-any-open-session-default-2912
    - https://community.automationanywhere.com/developers-forum-36/unable-to-create-session-dayforecast-as-it-already-exists-erroline-45-87892
    - https://community.automationanywhere.com/developers-forum-36/google-sheets-sessions-and-child-bots-91263
    - **What we should do:** Make sessions objects or variables, not free text. Close them automatically at the end of a bot or on error, offer a picker of open sessions, and give a design-time warning for a session used before it is opened.

36. **Excel Advanced Open fails or hangs.** Causes include leftover Excel processes, password-protected files, unusual workbooks and data entry slowing down on large sheets. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/error-in-excel-advance-open-action-89920
    - https://community.automationanywhere.com/developers-forum-36/excel-advanced-data-entry-slows-down-majorly-after-a-number-of-rows-populated-89701
    - **What we should do:** Offer an option to close leftover Excel processes, give clear password and protected-file errors, and provide bulk read and write ranges instead of cell-by-cell loops.

37. **Email connection breaks on auth changes.** This covers the end of basic auth, EWS being retired, OAuth setup failures, webmail vs desktop differences, and new Outlook not being supported. **Several.**
    - https://community.automationanywhere.com/developers-forum-36/hi-all-need-some-help-on-replacing-basic-authentication-with-oauth-standard-modern-authentication-to-connect-to-ews-exchange-web-services-in-a360-10501
    - https://community.automationanywhere.com/developers-forum-36/the-provided-connection-details-are-invalid-a360-outlook-web-87172
    - **What we should do:** Provide one email Connect with a guided OAuth setup and a Test connection button, and warn ahead of time about provider changes.

## K. Control Room, performance and navigation

38. **Control Room and editor are slow.** Users report slow login, opening bots, deploying and listing bots (minutes on Community Edition). Reviewers list slowness as a common con. **Many.**
    - https://community.automationanywhere.com/developers-forum-36/a360-control-room-90332
    - https://community.automationanywhere.com/community-edition-85050/control-room-is-slow-today-6-mar-89452
    - https://community.automationanywhere.com/developers-forum-36/control-room-performance-90395
    - **What we should do:** Set and test performance budgets for each page, use paged and lazy lists, and add a background clean-up of old logs and notifications.

39. **Running a bot from the editor is slow ("Deploying to your computer").** It sometimes hangs, while the same bot runs fine from the list. Loops in task bots are slow because resources reload. **Several.**
    - https://community.automationanywhere.com/community-edition-85050/sometimes-very-randomly-can-t-find-a-pattern-i-got-stuck-at-the-deploying-to-your-computer-animation-when-i-run-from-inside-the-bot-editor-oddly-it-runs-perfectly-if-i-run-from-run-bot-now-list-any-ideas-18606
    - https://community.automationanywhere.com/community-edition-85050/have-recently-signed-up-a360-community-edition-with-bot-agent-version-21-121-first-3-days-product-worked-like-a-charm-now-when-i-try-to-run-a-bot-it-always-display-deploying-to-computer-and-doesn-t-proceed-to-next-step-18575
    - **What we should do:** Make test runs from the editor start instantly on a warm local runner, and show the deploy stages with timings.

40. **Steep learning curve, dated UI, and weak documentation or support.** These are frequent review themes. Setup steps are scattered across Administration pages. **Many** (reviews).
    - https://www.peerspot.com/products/automation-anywhere-pros-and-cons
    - https://g2.com/products/automation-anywhere
    - **What we should do:** Add contextual help on every field, a first-run setup checklist (agent, device, credentials, first bot), and settings search.

41. **Admin bulk tasks are manual.** Examples are creating private folders for 20+ developers, setting folder permissions for developers, and exporting dashboard data. **Few to several.**
    - https://community.automationanywhere.com/developers-forum-36/i-want-to-create-folders-and-sub-folder-in-private-for-more-than-20-creator-accounts-if-there-any-way-to-avoid-manual-work-a360-cr-8556
    - https://community.automationanywhere.com/developers-forum-36/in-a360-aa-admin-is-not-able-to-give-folder-permission-to-the-bot-developer-14615
    - **What we should do:** Support bulk actions and CSV import or export for users, folders, permissions and run history.

42. **v11 to A360 migration needed heavy rework.** MetaBots were missing, variable passing to scripts changed, and migrated bots kept odd mapping bugs. **Several** (reviews and forum).
    - https://community.automationanywhere.com/developers-forum-36/migration-from-v11-to-a360-87442
    - https://www.peerspot.com/products/automation-anywhere-reviews/page-12?rr=t
    - **What we should do:** Keep our bot format stable across versions, and when it must change, migrate automatically with a report of every change.

## Top 10 to fix first (ranked)

1. **Errors that explain the cause and the fix, and jump to the step** (items 33, 10, 19, 18). This touches every other problem.
2. **Device and agent health with self-diagnosis** (items 22, 25). This is the most-asked forum topic.
3. **Reliable child bot input and output mapping** (items 1, 2): outputs returned on failure, broken mappings flagged on save.
4. **Pre-run and pre-deploy checks**: credentials readable by the run-as user, dependencies present, session and resolution OK, licence and device fit (items 29, 23, 24, 31, 19).
5. **Object capture that survives change**: several locators per object, a Test button, and a setup check for browser extensions (items 14, 15, 16).
6. **A debugger that shows everything**: all variables including children, Run from here, edit values (item 9).
7. **One run detail page** with timeline, screenshots, logs and script output (item 34), plus "why is this waiting" for queued runs, schedules and work items (items 25, 26, 28).
8. **Simple versioning**: clear draft and published states, compare, one-click restore and a recycle bin (items 12, 17).
9. **Editor ergonomics**: fast at large sizes, wrapping fields, Ctrl+C / Ctrl+V across bots with variables, rename and "where used" (items 4, 5, 6, 8).
10. **Excel and application sessions handled automatically**: session objects, close on error or exit, no free-text names (items 35, 36).

**Notes and uncertainty:**
- Reddit and Stack Overflow returned almost no A360-specific threads, so the evidence comes mostly from the AA forum and from review sites.
- Item 13 (undo) and item 4 (rename) rest on little evidence.
- Frequency labels are based on the number of distinct threads found, not a full count.
