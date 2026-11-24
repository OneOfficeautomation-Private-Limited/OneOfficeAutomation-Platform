"""Unit-test agents for the desktop, browser, mail, terminal and transfer toolkits (helper "desktop").

needs: "local" runs anywhere; "standin" needs the stand-in with plugins/desktop_services.py (its web pages, EWS
mailbox, FTP server on port 2121 and VT host on port 2323); "desktop" drives the screen (mouse, keyboard, windows,
desktop programs, sound): run it only when nobody is using the computer; "mailserver" needs a real test SMTP/IMAP
server (build and validate only here); "sap" needs SAP GUI with scripting on (build and validate only here).
"""

GROUPS = {
    "application": {"id": "ut_application", "name": "UT Application", "toolkits": ["ooa.application"], "needs": "local",
                    "skip": ["open_file", "open_program_file"],
                    "about": "Unit tests of the Application commands that run programs without windows: run a program, "
                             "start and stop one, command sessions, and the Program is running condition."},
    "application_windows": {"id": "ut_application_windows", "name": "UT Application (windows)", "toolkits": ["ooa.application"],
                            "needs": "desktop", "only": ["open_file", "open_program_file"],
                            "about": "Unit tests of the Application commands that open windows: open a file with its program and "
                                     "open a program file. Each case closes what it opened."},
    "browser": {"id": "ut_browser", "name": "UT Browser", "toolkits": ["ooa.browser"], "needs": "standin",
                "skip": ["pick_element", "record", "test_object"],
                "about": "Unit tests of the Browser commands in a headless Edge on the stand-in's test pages (/dsk/web): "
                         "each case opens its own browser, works on the page and checks what it read back."},
    "browser_capture": {"id": "ut_browser_capture", "name": "UT Browser (capture)", "toolkits": ["ooa.browser"], "needs": "desktop",
                        "only": ["pick_element", "record", "test_object"],
                        "about": "Unit tests of the Browser design-time commands (pick an element, record, test a capture). "
                                 "They open a visible browser; pick and record wait for a person, so their cases check the time-out."},
    "web_agent": {"id": "ut_web_agent", "name": "UT Web goal agent", "toolkits": ["ooa.web_agent"], "needs": "standin",
                  "about": "Unit tests of the Web goal agent: a headless browser session on a stand-in page and a goal run "
                           "against the stand-in's scripted model (OpenAI-compatible)."},
    "ai_screen": {"id": "ut_ai_screen", "name": "UT AI screen (vision)", "toolkits": ["ooa.ai_screen"], "needs": "desktop",
                  "about": "Unit tests of the AI screen commands: a test window is opened, the screen is sent to the stand-in's "
                           "scripted vision model, and the control it names is found, read, clicked and typed into."},
    "desktop": {"id": "ut_desktop", "name": "UT Mouse and keyboard", "toolkits": ["ooa.desktop"], "needs": "desktop",
                "about": "Unit tests of the Mouse and keyboard commands: clipboard, screen capture, mouse, keys, message boxes and "
                         "prompts that answer themselves after a time-out. Drives the screen."},
    "desktop_ui": {"id": "ut_desktop_ui", "name": "UT Desktop objects", "toolkits": ["ooa.desktop_ui"], "needs": "desktop",
                   "about": "Unit tests of the Desktop objects commands on a small Windows Forms test window: find controls, "
                            "read and set them, read a table, parents, legacy (MSAA) objects."},
    "window": {"id": "ut_window", "name": "UT Window", "toolkits": ["ooa.window"], "needs": "desktop",
               "about": "Unit tests of the Window commands on test windows the cases open and close themselves."},
    "java_apps": {"id": "ut_java_apps", "name": "UT Java apps", "toolkits": ["ooa.java_apps"], "needs": "desktop",
                  "about": "Unit tests of the Java apps commands. No Java desktop program runs here, so the cases check the "
                           "empty answers and the clear errors; with a Java program open the same steps work on it."},
    "sap": {"id": "ut_sap", "name": "UT SAP GUI", "toolkits": ["ooa.sap"], "needs": "sap",
            "about": "Unit tests of the SAP GUI commands against a made-up test system (UT1, client 100) on the usual "
                     "SAP Easy Access and transaction screens. Needs SAP GUI with scripting on: build and validate only here."},
    "terminal": {"id": "ut_terminal", "name": "UT Terminal emulator", "toolkits": ["ooa.terminal"], "needs": "standin",
                 "skip": ["show_terminal"],
                 "about": "Unit tests of the Terminal commands on the stand-in's VT100 host over Telnet (port 2323): sign in, "
                          "fill the Name prompt, read the menu screen, fields, cursor and the command line."},
    "terminal_viewer": {"id": "ut_terminal_viewer", "name": "UT Terminal emulator (viewer)", "toolkits": ["ooa.terminal"],
                        "needs": "desktop", "only": ["show_terminal"],
                        "about": "Unit test of Show terminal: opens the terminal viewer window on the stand-in's VT host and closes it."},
    "file_transfer": {"id": "ut_file_transfer", "name": "UT FTP and SFTP", "toolkits": ["ooa.file_transfer"], "needs": "standin",
                      "about": "Unit tests of the FTP / SFTP commands on the stand-in's FTP server (port 2121, user agent): "
                               "folders, uploads, downloads, listing, renames, deletes and folder sync."},
    "email": {"id": "ut_email", "name": "UT Email", "toolkits": ["ooa.email"], "needs": "mailserver",
              "about": "Unit tests of the Email (IMAP/POP3 and SMTP) commands against a made-up test mailbox on "
                       "mail.ut.test. Needs a test mail server: build and validate only here; the cases without a server run."},
    "exchange": {"id": "ut_exchange", "name": "UT Exchange (EWS)", "toolkits": ["ooa.exchange"], "needs": "standin",
                 "about": "Unit tests of the Exchange (EWS) commands on the stand-in's made-up mailbox (/dsk/ews): two invoice "
                          "emails, folders Inbox, Invoices and Done. Nothing is really sent."},
    "outlook_desktop": {"id": "ut_outlook_desktop", "name": "UT Outlook desktop", "toolkits": ["ooa.outlook_desktop"],
                        "needs": "desktop",
                        "about": "Unit tests of the Outlook desktop commands. They need Outlook with a mailbox; mail is only "
                                 "saved as drafts and deleted again, never sent."},
    "interrupts": {"id": "ut_interrupts", "name": "UT Interrupt handlers", "toolkits": ["ooa.interrupts"], "needs": "local",
                   "about": "Unit tests of the Interrupt handler commands: add, list, check, remove and clear rules."},
    "triggerloop": {"id": "ut_triggerloop", "name": "UT Trigger Loop", "toolkits": ["ooa.triggerloop"], "needs": "local",
                    "skip": ["form_events", "key_pressed", "mouse_clicked", "window_changes"],
                    "about": "Unit tests of the Trigger Loop types that watch files, processes, services, timers and lists, "
                             "each bounded by max_events or max_seconds."},
    "triggerloop_screen": {"id": "ut_triggerloop_screen", "name": "UT Trigger Loop (screen)", "toolkits": ["ooa.triggerloop"],
                           "needs": "desktop", "only": ["form_events", "key_pressed", "mouse_clicked", "window_changes"],
                           "about": "Unit tests of the Trigger Loop types that watch the screen: keys, clicks, windows and forms. "
                                    "The cases make the events themselves in a parallel branch."},
    "sound": {"id": "ut_sound", "name": "UT Play sound", "toolkits": ["ooa.sound"], "needs": "desktop",
              "about": "Unit tests of the Play sound commands with a short quiet test tone. Makes sound."},
    "printer": {"id": "ut_printer", "name": "UT Printer", "toolkits": ["ooa.printer"], "needs": "local",
                "about": "Unit tests of the Printer commands that change nothing: list printers, the default printer, and the "
                         "errors for a missing file, an unknown printer and a removal without confirmation. Nothing is printed."},
}
