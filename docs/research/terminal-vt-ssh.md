# Terminal toolkit: VT over SSH, and 5250 (2026-10-25)

## What was added

`ooa.terminal/connect` has a third `terminal_type`, **vt-ssh**, next to tn3270e and tn3270. It signs in to a
VT100/ANSI host over SSH (JSch, the mwiede fork already used by the file transfer toolkit) with a password
Credential, a private key file (optionally with a passphrase), or both. Other SSH inputs: `host_key_fingerprint`
(pinning; a warning is logged when it is empty), `emulation` (vt100, vt220, xterm or ansi, the terminal name the host
is told) and `screen_rows` / `screen_columns` (default 24 x 80). The port defaults to 22 for SSH and 23 for 3270.
The 3270-only inputs are hidden in Studio for vt-ssh and the SSH inputs are hidden for 3270. The earlier `connect`
signatures are kept as overloads.

The screen is kept by `VtScreen`, a small VT100/ANSI interpreter written for this (cursor moves, erase, insert and
delete, scroll regions, the alternate screen used by full-screen programs, cursor-position reports; colours are
ignored). `VtSshSession` answers the same engine commands as the x3270 process, so every Terminal action works on
SSH hosts with the same inputs:

- **get_screen, get_text, search_field, wait_for_text, get_cursor**: read the VT screen.
- **send_text, send_key**: type and press keys. VT hosts also take Escape, End, PageUp, PageDown and Ctrl+letter
  (e.g. Ctrl+C); PF1-PF20 send the VT220 function keys. 3270-only keys (PA1-PA3, Clear, Attn, SysReq, Dup...) fail
  with `TERMINAL.KEY_NOT_ON_VT`.
- **get_field, set_field, get_all_fields**: VT hosts send no field layout, so fields are read from the text: a word
  ending in a colon ("Name:") is a label and what follows it (after one blank, up to the next label on the row) is
  an input field. Moving the cursor to a field sends arrow keys, which suits full-screen programs; at a shell
  prompt the cursor is normally already in place and nothing extra is sent. Clearing a field sends Delete once
  per character it holds.
- **wait**: VT hosts have no keyboard lock, so "wait for the host" means wait until it has answered and gone quiet
  for a quarter of a second. Waiting for specific text (`wait_for_text`) is the reliable choice.

New error codes: `TERMINAL.AUTH_FAILED`, `TERMINAL.CONNECT_FAILED`, `TERMINAL.HOST_KEY_MISMATCH`,
`TERMINAL.NO_USER`, `TERMINAL.NO_SIGN_IN`, `TERMINAL.KEY_FILE_NOT_FOUND`, `TERMINAL.BAD_KEY_FILE`,
`TERMINAL.BAD_EMULATION`, `TERMINAL.BAD_SCREEN_SIZE`, `TERMINAL.KEY_NOT_ON_VT`, `TERMINAL.NOT_ON_VT`.

Tests: `TerminalVtTest` starts an Apache MINA SSH server in the test (test-only dependency `sshd-core` 2.14.0) whose
shell draws a sign-in prompt, a small form and a command line. It covers password and key sign-in, a wrong
password, reading text, filling fields by label (including the arrow-key move), typing, Ctrl keys, and the screen
interpreter and field reading on their own.

## 5250: not built, recommendation

No 5250 engine can be added without a heavy or awkward dependency:

- **tn5250j** (the usual Java 5250 emulator, on Maven Central as `com.github.vebqa:tn5250j`) is GPL-2.0 and built
  around its Swing emulator window. Linking it would put the product under GPL terms.
- **xtn5250** (Apache-2.0, the engine BlazeMeter's JMeter terminal plugin uses headless) is published only on
  JitPack, not Maven Central, and is a Swing-era code base we would have to vendor and maintain.
- The x3270 family that drives our 3270 support has no 5250 member; the C `tn5250` project is Linux-only.

Recommendation: if 5250 hosts (IBM i / AS400) are needed, either (a) vendor xtn5250's protocol classes (Apache-2.0)
into a new `packages-terminal5250` module behind the same `Session` commands VT uses, or (b) write our own TN5250E
client (telnet negotiation, EBCDIC, the 5250 data stream's write-to-display and format table orders). (a) is the
smaller job; both need a real IBM i (or the free PUB400 public system) to test against. Decide based on demand.

## What Automation Anywhere's terminal emulator offers that is still missing

Compared with the AA terminal emulator as surveyed in the command comparison:

- **5250 hosts** (see above).
- **VT over plain Telnet** (no SSH). Small follow-up: the same `VtSshSession` command layer on a telnet stream.
- **A visible emulator window**, which also lets the bot builder pick fields from the live screen; our engines run
  without a window (show_terminal / hide_terminal only log a note).
- **Field definitions from the host on VT screens**: VT has none in the protocol; AA's own VT field model may differ
  from our colon-label rule, so bots that address VT fields by index may need adjusting when moved from AA.
- **Older SSH variants** (SSH-1) are not offered and will not be: they are insecure.
- Not offered by us either way: a proxy or jump host for the SSH connection, multi-prompt keyboard-interactive
  sign-in (one-time codes), resizing the screen after connecting, and reading character colours.
