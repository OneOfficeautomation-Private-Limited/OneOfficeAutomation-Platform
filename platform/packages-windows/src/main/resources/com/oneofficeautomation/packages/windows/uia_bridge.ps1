# ONEOFFICEAUTOMATION desktop object bridge.
# Reads one JSON request per line on standard input and writes one JSON response per line on standard output.
# It talks to Windows UI Automation through the .NET UIAutomationClient assemblies. The Desktop objects toolkit
# starts it with an argument list (never through a shell) and stops it at the end of the run; it also exits as soon
# as its standard input closes, so it never outlives the agent.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$names = @('UIAutomationClient', 'UIAutomationTypes', 'WindowsBase', 'System.Drawing', 'System.Windows.Forms', 'System.Web.Extensions')
foreach ($n in $names) { Add-Type -AssemblyName $n }
$refs = @([System.Uri].Assembly.Location)
foreach ($a in [AppDomain]::CurrentDomain.GetAssemblies()) { if ($names -contains $a.GetName().Name) { $refs += $a.Location } }

$source = @'
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Automation;

namespace OoaUia {

public class BridgeError : Exception {
    public string Code;
    public BridgeError(string code, string message) : base(message) { Code = code; }
}

public static class Native {
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern short GetAsyncKeyState(int vKey);
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT pt);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int hgt, bool repaint);
    public delegate IntPtr HookProc(int code, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", SetLastError = true)] public static extern IntPtr SetWindowsHookEx(int id, HookProc fn, IntPtr mod, uint thread);
    [DllImport("user32.dll")] public static extern bool UnhookWindowsHookEx(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr CallNextHookEx(IntPtr h, int code, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll")] public static extern IntPtr GetModuleHandle(string name);
    [StructLayout(LayoutKind.Sequential)] public struct MSLLHOOKSTRUCT { public POINT pt; public uint mouseData; public uint flags; public uint time; public IntPtr extra; }
    [StructLayout(LayoutKind.Sequential)] public struct KBDLLHOOKSTRUCT { public uint vk; public uint scan; public uint flags; public uint time; public IntPtr extra; }

    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X; public int Y; }
    [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION u; }

    public const uint KEYUP = 0x2, UNICODE = 0x4;

    static void Send(INPUT i) { Send(new INPUT[] { i }); }
    static void Send(INPUT[] i) { SendInput((uint)i.Length, i, Marshal.SizeOf(typeof(INPUT))); }

    public static INPUT Key(ushort vk, bool up) {
        INPUT i = new INPUT(); i.type = 1; i.u.ki.wVk = vk; i.u.ki.dwFlags = up ? KEYUP : 0; return i;
    }
    public static INPUT Char(char c, bool up) {
        INPUT i = new INPUT(); i.type = 1; i.u.ki.wScan = c; i.u.ki.dwFlags = UNICODE | (up ? KEYUP : 0); return i;
    }
    public static INPUT Mouse(uint flags) { INPUT i = new INPUT(); i.type = 0; i.u.mi.dwFlags = flags; return i; }

    public static void Press(ushort vk) { Send(new INPUT[] { Key(vk, false), Key(vk, true) }); }

    public static void SelectAllAndDelete() {
        Send(new INPUT[] { Key(0x11, false), Key(0x41, false), Key(0x41, true), Key(0x11, true) });
        Thread.Sleep(30);
        Press(0x2E);
    }

    public static void Type(string text, int delayMs) {
        foreach (char c in text) {
            if (c == '\r') continue;
            if (c == '\n') Press(0x0D);
            else if (c == '\t') Press(0x09);
            else Send(new INPUT[] { Char(c, false), Char(c, true) });
            if (delayMs > 0) Thread.Sleep(delayMs);
        }
    }

    public static void Click(int x, int y, string button, int count) {
        uint down = 0x2, up = 0x4;
        if (button == "right") { down = 0x8; up = 0x10; }
        else if (button == "middle") { down = 0x20; up = 0x40; }
        SetCursorPos(x, y);
        Thread.Sleep(40);
        for (int i = 0; i < count; i++) {
            Send(new INPUT[] { Mouse(down), Mouse(up) });
            Thread.Sleep(count > 1 ? 30 : 10);
        }
    }

    public static bool Down(int vk) { return (GetAsyncKeyState(vk) & 0x8000) != 0; }

    public static void Activate(IntPtr h) {
        if (h == IntPtr.Zero) return;
        if (IsIconic(h)) ShowWindow(h, 9);
        IntPtr fg = GetForegroundWindow();
        if (fg == h) return;
        uint me = GetCurrentThreadId(), other = GetWindowThreadProcessId(fg, IntPtr.Zero);
        bool attached = other != 0 && other != me && AttachThreadInput(me, other, true);
        try { BringWindowToTop(h); SetForegroundWindow(h); }
        finally { if (attached) AttachThreadInput(me, other, false); }
        Thread.Sleep(60);
    }
}

/** Thin red frame drawn around the object under the pointer while a person is capturing. */
public class Frame : System.Windows.Forms.Form {
    protected override System.Windows.Forms.CreateParams CreateParams {
        get {
            System.Windows.Forms.CreateParams p = base.CreateParams;
            p.ExStyle |= 0x80000 | 0x20 | 0x80 | 0x08000000;   // layered, click-through, tool window, no activate
            return p;
        }
    }
    protected override bool ShowWithoutActivation { get { return true; } }
    public Frame() {
        FormBorderStyle = System.Windows.Forms.FormBorderStyle.None;
        ShowInTaskbar = false; TopMost = true; BackColor = System.Drawing.Color.Red; Opacity = 0.85;
        StartPosition = System.Windows.Forms.FormStartPosition.Manual;
        Bounds = new System.Drawing.Rectangle(0, 0, 1, 1);
    }
}

/** The bar shown while recording a desktop application: what is happening, how many steps, and Stop recording. */
public class RecordBar : System.Windows.Forms.Form {
    public volatile bool Stopped;
    readonly System.Windows.Forms.Label count;
    protected override bool ShowWithoutActivation { get { return true; } }
    protected override System.Windows.Forms.CreateParams CreateParams {
        get { System.Windows.Forms.CreateParams p = base.CreateParams; p.ExStyle |= 0x80 | 0x08000000; return p; }   // tool window, no activate
    }
    public RecordBar() {
        FormBorderStyle = System.Windows.Forms.FormBorderStyle.None; ShowInTaskbar = false; TopMost = true;
        BackColor = System.Drawing.Color.FromArgb(47, 51, 54); ForeColor = System.Drawing.Color.White;
        StartPosition = System.Windows.Forms.FormStartPosition.Manual;
        System.Drawing.Rectangle wa = System.Windows.Forms.Screen.PrimaryScreen.WorkingArea;
        Bounds = new System.Drawing.Rectangle(wa.Right - 380, wa.Top + 10, 370, 40);
        System.Windows.Forms.Label l = new System.Windows.Forms.Label();
        l.Text = "\u25CF  ONEOFFICEAUTOMATION is recording"; l.AutoSize = false; l.Bounds = new System.Drawing.Rectangle(10, 11, 210, 20);
        l.Font = new System.Drawing.Font("Segoe UI", 9f);
        count = new System.Windows.Forms.Label(); count.Text = "0 steps"; count.Bounds = new System.Drawing.Rectangle(222, 11, 60, 20); count.Font = l.Font;
        System.Windows.Forms.Button b = new System.Windows.Forms.Button();
        b.Text = "Stop recording"; b.Bounds = new System.Drawing.Rectangle(282, 6, 82, 28); b.FlatStyle = System.Windows.Forms.FlatStyle.Flat;
        b.BackColor = System.Drawing.Color.FromArgb(10, 123, 187); b.ForeColor = System.Drawing.Color.White; b.Font = new System.Drawing.Font("Segoe UI", 8f);
        b.Click += (o, e) => { Stopped = true; };
        Controls.Add(l); Controls.Add(count); Controls.Add(b);
    }
    public void SetCount(int n) { count.Text = n + (n == 1 ? " step" : " steps"); }
    public bool Contains(int x, int y) { return Bounds.Contains(x, y); }
}

public static class Bridge {
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    static readonly Dictionary<int, AutomationElement> Refs = new Dictionary<int, AutomationElement>();
    static readonly Dictionary<int, string> ProcessNames = new Dictionary<int, string>();
    static int nextRef = 1;

    static readonly string[] Names = { "Name", "AutomationId", "ClassName", "ControlType", "FrameworkId", "LocalizedControlType",
        "HelpText", "AccessKey", "IsEnabled", "IsOffscreen", "Value", "BoundingRectangle", "RuntimeId", "ProcessId" };
    static readonly AutomationProperty[] Props = { AutomationElement.NameProperty, AutomationElement.AutomationIdProperty,
        AutomationElement.ClassNameProperty, AutomationElement.ControlTypeProperty, AutomationElement.FrameworkIdProperty,
        AutomationElement.LocalizedControlTypeProperty, AutomationElement.HelpTextProperty, AutomationElement.AccessKeyProperty,
        AutomationElement.IsEnabledProperty, AutomationElement.IsOffscreenProperty, ValuePattern.ValueProperty,
        AutomationElement.BoundingRectangleProperty, AutomationElement.RuntimeIdProperty, AutomationElement.ProcessIdProperty };

    static readonly string[] ExtraNames = { "AcceleratorKey", "ItemStatus", "ItemType", "IsKeyboardFocusable", "HasKeyboardFocus",
        "IsPassword", "IsContentElement", "IsControlElement", "NativeWindowHandle", "Orientation" };
    static readonly AutomationProperty[] Extra = { AutomationElement.AcceleratorKeyProperty, AutomationElement.ItemStatusProperty,
        AutomationElement.ItemTypeProperty, AutomationElement.IsKeyboardFocusableProperty, AutomationElement.HasKeyboardFocusProperty,
        AutomationElement.IsPasswordProperty, AutomationElement.IsContentElementProperty, AutomationElement.IsControlElementProperty,
        AutomationElement.NativeWindowHandleProperty, AutomationElement.OrientationProperty };

    public static void Run() {
        Json.MaxJsonLength = int.MaxValue;
        try { Native.SetProcessDPIAware(); } catch (Exception) { }
        StreamReader input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        StreamWriter output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
        Output = output;
        output.AutoFlush = true; output.NewLine = "\n";
        output.WriteLine("{\"ready\":true,\"protocol\":1}");
        string line;
        while ((line = input.ReadLine()) != null) {
            if (line.Trim().Length == 0) continue;
            Dictionary<string, object> resp = new Dictionary<string, object>();
            object id = null;
            bool exit = false;
            try {
                Dictionary<string, object> req = Json.Deserialize<Dictionary<string, object>>(line);
                if (req.ContainsKey("id")) id = req["id"];
                string op = Str(req, "op", "");
                if (op == "exit") exit = true;
                else resp["result"] = Dispatch(op, req);
                resp["ok"] = true;
            } catch (Exception e) {
                Exception x = (e is TargetInvocationException && e.InnerException != null) ? e.InnerException : e;
                resp["ok"] = false;
                resp["code"] = x is BridgeError ? ((BridgeError)x).Code
                        : x is ElementNotAvailableException ? "STALE"
                        : x is ElementNotEnabledException ? "DISABLED"
                        : x is InvalidOperationException ? "NOT_SUPPORTED" : "FAILED";
                resp["error"] = x.Message;
            }
            resp["id"] = id;
            output.WriteLine(Json.Serialize(resp));
            if (exit) return;
        }
    }

    // ---------- request helpers ----------
    static string Str(IDictionary<string, object> m, string k, string d) { object v; return m.TryGetValue(k, out v) && v != null ? Convert.ToString(v, CultureInfo.InvariantCulture) : d; }
    static int Int(IDictionary<string, object> m, string k, int d) { object v; return m.TryGetValue(k, out v) && v != null ? Convert.ToInt32(v, CultureInfo.InvariantCulture) : d; }
    static bool Bool(IDictionary<string, object> m, string k, bool d) { object v; return m.TryGetValue(k, out v) && v != null ? Convert.ToBoolean(v, CultureInfo.InvariantCulture) : d; }

    static StreamWriter Output;

    /** A line before the answer, for a long request (the recorder's step count). */
    static void Progress(object id, Dictionary<string, object> data) {
        data["id"] = id; data["progress"] = true;
        lock (Output) Output.WriteLine(Json.Serialize(data));
    }

    static object Dispatch(string op, Dictionary<string, object> req) {
        switch (op) {
            case "ping": return "pong";
            case "windows": return Windows();
            case "foreground": return Native.GetForegroundWindow().ToInt64();
            case "find": return Find(req);
            case "act": return Act(req);
            case "capture_at": return CaptureAt(Int(req, "x", 0), Int(req, "y", 0), Bool(req, "screenshot", true));
            case "pick": return Pick(Int(req, "timeout_ms", 60000), Bool(req, "screenshot", true), Bool(req, "highlight", true));
            case "record": return Record(req.ContainsKey("id") ? req["id"] : null, Int(req, "timeout_ms", 1800000), Bool(req, "screenshot", true));
            case "resize": return Resize(Int(req, "handle", 0), Int(req, "width", 0), Int(req, "height", 0));
            default: throw new BridgeError("BAD_REQUEST", "Unknown operation '" + op + "'");
        }
    }

    // ---------- property formatting ----------
    static string Fmt(object v) {
        if (v == null || v == AutomationElement.NotSupported) return "";
        if (v is ControlType) return ((ControlType)v).ProgrammaticName.Replace("ControlType.", "");
        if (v is System.Windows.Rect) {
            System.Windows.Rect r = (System.Windows.Rect)v;
            if (r.IsEmpty || double.IsInfinity(r.X)) return "";
            return string.Format(CultureInfo.InvariantCulture, "{0},{1},{2},{3}", (int)r.X, (int)r.Y, (int)r.Width, (int)r.Height);
        }
        if (v is bool) return ((bool)v) ? "true" : "false";
        if (v is int[]) { int[] a = (int[])v; string[] s = new string[a.Length]; for (int i = 0; i < a.Length; i++) s[i] = a[i].ToString(CultureInfo.InvariantCulture); return string.Join(".", s); }
        return Convert.ToString(v, CultureInfo.InvariantCulture);
    }

    static string ProcessName(int pid) {
        string n;
        if (ProcessNames.TryGetValue(pid, out n)) return n;
        try { n = Process.GetProcessById(pid).ProcessName; } catch (Exception) { n = ""; }
        if (ProcessNames.Count > 2000) ProcessNames.Clear();
        ProcessNames[pid] = n;
        return n;
    }

    static Dictionary<string, object> Cached(AutomationElement e) {
        Dictionary<string, object> d = new Dictionary<string, object>();
        for (int i = 0; i < Props.Length; i++) {
            object v;
            try { v = e.GetCachedPropertyValue(Props[i], true); } catch (Exception) { v = null; }
            d[Names[i]] = Fmt(v);
        }
        return d;
    }

    static Dictionary<string, object> Live(AutomationElement e, bool extra) {
        Dictionary<string, object> d = new Dictionary<string, object>();
        for (int i = 0; i < Props.Length; i++) {
            object v;
            try { v = e.GetCurrentPropertyValue(Props[i], true); } catch (ElementNotAvailableException) { throw; } catch (Exception) { v = null; }
            d[Names[i]] = Fmt(v);
        }
        if (!extra) return d;
        for (int i = 0; i < Extra.Length; i++) {
            object v;
            try { v = e.GetCurrentPropertyValue(Extra[i], true); } catch (Exception) { v = null; }
            d[ExtraNames[i]] = Fmt(v);
        }
        int pid; if (int.TryParse(Convert.ToString(d["ProcessId"]), out pid)) d["ProcessName"] = ProcessName(pid);
        object p;
        if (e.TryGetCurrentPattern(ValuePattern.Pattern, out p)) d["IsReadOnly"] = Fmt(((ValuePattern)p).Current.IsReadOnly);
        if (e.TryGetCurrentPattern(TogglePattern.Pattern, out p)) d["ToggleState"] = ((TogglePattern)p).Current.ToggleState.ToString();
        if (e.TryGetCurrentPattern(SelectionItemPattern.Pattern, out p)) d["IsSelected"] = Fmt(((SelectionItemPattern)p).Current.IsSelected);
        if (e.TryGetCurrentPattern(ExpandCollapsePattern.Pattern, out p)) d["ExpandCollapseState"] = ((ExpandCollapsePattern)p).Current.ExpandCollapseState.ToString();
        if (e.TryGetCurrentPattern(RangeValuePattern.Pattern, out p)) d["RangeValue"] = Fmt(((RangeValuePattern)p).Current.Value);
        List<string> pats = new List<string>();
        foreach (AutomationPattern ap in e.GetSupportedPatterns()) pats.Add(ap.ProgrammaticName.Replace("PatternIdentifiers.Pattern", ""));
        d["SupportedPatterns"] = string.Join(",", pats.ToArray());
        return d;
    }

    static CacheRequest Cache() {
        CacheRequest cr = new CacheRequest();
        foreach (AutomationProperty p in Props) cr.Add(p);
        cr.Add(AutomationElement.NativeWindowHandleProperty);
        return cr;
    }

    static int Remember(AutomationElement e) {
        if (Refs.Count > 20000) Refs.Clear();
        int r = nextRef++;
        Refs[r] = e;
        return r;
    }

    static AutomationElement Ref(Dictionary<string, object> req) {
        AutomationElement e;
        if (!Refs.TryGetValue(Int(req, "ref", -1), out e)) throw new BridgeError("STALE", "The object reference is no longer valid; find the object again");
        return e;
    }

    // ---------- windows and search ----------
    static List<object> Windows() {
        List<object> list = new List<object>();
        CacheRequest cr = new CacheRequest();
        cr.Add(AutomationElement.NameProperty); cr.Add(AutomationElement.ClassNameProperty);
        cr.Add(AutomationElement.ProcessIdProperty); cr.Add(AutomationElement.NativeWindowHandleProperty);
        AutomationElementCollection col;
        using (cr.Activate()) col = AutomationElement.RootElement.FindAll(TreeScope.Children, Condition.TrueCondition);
        // dialogs a window owns (a validation pop-up, a Save As box) appear under it, not at the top: list them as windows too
        List<AutomationElement> all = new List<AutomationElement>();
        Condition isWindow = new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Window);
        foreach (AutomationElement w in col) {
            all.Add(w);
            try { using (cr.Activate()) foreach (AutomationElement o in w.FindAll(TreeScope.Children, isWindow)) all.Add(o); }
            catch (ElementNotAvailableException) { }
        }
        foreach (AutomationElement w in all) {
            if ((int)w.GetCachedPropertyValue(AutomationElement.NativeWindowHandleProperty) == 0) continue;
            Dictionary<string, object> d = new Dictionary<string, object>();
            int pid = (int)w.GetCachedPropertyValue(AutomationElement.ProcessIdProperty);
            d["handle"] = (int)w.GetCachedPropertyValue(AutomationElement.NativeWindowHandleProperty);
            d["title"] = Fmt(w.GetCachedPropertyValue(AutomationElement.NameProperty));
            d["class_name"] = Fmt(w.GetCachedPropertyValue(AutomationElement.ClassNameProperty));
            d["pid"] = pid;
            d["process"] = ProcessName(pid);
            list.Add(d);
        }
        return list;
    }

    static ControlType ControlTypeNamed(string name) {
        FieldInfo f = typeof(ControlType).GetField(name, BindingFlags.Public | BindingFlags.Static | BindingFlags.IgnoreCase);
        return f == null ? null : f.GetValue(null) as ControlType;
    }

    static List<object> Find(Dictionary<string, object> req) {
        List<Condition> parts = new List<Condition>();
        object pre;
        if (req.TryGetValue("prefilter", out pre) && pre is IDictionary<string, object>) {
            IDictionary<string, object> m = (IDictionary<string, object>)pre;
            string ct = Str(m, "ControlType", null);
            if (ct != null) {
                ControlType t = ControlTypeNamed(ct);
                if (t == null) return new List<object>();   // no control has this type
                parts.Add(new PropertyCondition(AutomationElement.ControlTypeProperty, t));
            }
            string aid = Str(m, "AutomationId", null);
            if (aid != null) parts.Add(new PropertyCondition(AutomationElement.AutomationIdProperty, aid, PropertyConditionFlags.IgnoreCase));
        }
        Condition cond = parts.Count == 0 ? Condition.TrueCondition : parts.Count == 1 ? parts[0] : new AndCondition(parts.ToArray());
        int max = Int(req, "max", 5000);
        List<AutomationElement> roots = new List<AutomationElement>();
        object ws;
        if (req.TryGetValue("windows", out ws) && ws is ArrayList) {
            foreach (object h in (ArrayList)ws) {
                try { roots.Add(AutomationElement.FromHandle(new IntPtr(Convert.ToInt64(h, CultureInfo.InvariantCulture)))); }
                catch (Exception) { }   // the window closed meanwhile
            }
        } else roots.Add(AutomationElement.RootElement);
        Refs.Clear();
        List<object> list = new List<object>();
        foreach (AutomationElement root in roots) {
            AutomationElementCollection col;
            try { using (Cache().Activate()) col = root.FindAll(TreeScope.Subtree, cond); }
            catch (ElementNotAvailableException) { continue; }
            bool detail = Bool(req, "detail", false);
            foreach (AutomationElement e in col) {
                if (list.Count >= max) return list;
                Dictionary<string, object> d = Cached(e);
                if (detail && list.Count < 500) { try { foreach (KeyValuePair<string, object> kv in Detail(e, false)) if (!d.ContainsKey(kv.Key)) d[kv.Key] = kv.Value; } catch (ElementNotAvailableException) { continue; } }
                d["ref"] = Remember(e);
                list.Add(d);
            }
        }
        return list;
    }

    // ---------- actions ----------
    static IntPtr TopWindow(AutomationElement e) {
        TreeWalker w = TreeWalker.ControlViewWalker;
        AutomationElement root = AutomationElement.RootElement, cur = e, parent;
        while ((parent = w.GetParent(cur)) != null && !Automation.Compare(parent, root)) cur = parent;
        return new IntPtr(cur.Current.NativeWindowHandle);
    }

    static void Activate(AutomationElement e) { try { Native.Activate(TopWindow(e)); } catch (ElementNotAvailableException) { throw; } catch (Exception) { } }

    static void ScrollIntoView(AutomationElement e) {
        object p;
        if (e.TryGetCurrentPattern(ScrollItemPattern.Pattern, out p)) { try { ((ScrollItemPattern)p).ScrollIntoView(); } catch (Exception) { } }
    }

    static System.Windows.Rect Bounds(AutomationElement e) {
        System.Windows.Rect r = e.Current.BoundingRectangle;
        if (r.IsEmpty || r.Width <= 0 || r.Height <= 0)
            throw new BridgeError("NO_POSITION", "The object has no position on screen; it may be hidden, collapsed or scrolled out of view");
        return r;
    }

    static void MouseClick(AutomationElement e, string button, int count, int ox, int oy) {
        Activate(e);
        if (e.Current.IsOffscreen) ScrollIntoView(e);
        System.Windows.Rect r = Bounds(e);
        Native.Click((int)(r.X + r.Width / 2) + ox, (int)(r.Y + r.Height / 2) + oy, button, count);
    }

    static bool PatternClick(AutomationElement e) {
        object p;
        if (e.TryGetCurrentPattern(InvokePattern.Pattern, out p)) { ((InvokePattern)p).Invoke(); return true; }
        if (e.TryGetCurrentPattern(TogglePattern.Pattern, out p)) { ((TogglePattern)p).Toggle(); return true; }
        if (e.TryGetCurrentPattern(SelectionItemPattern.Pattern, out p)) { ((SelectionItemPattern)p).Select(); return true; }
        if (e.TryGetCurrentPattern(ExpandCollapsePattern.Pattern, out p)) {
            ExpandCollapsePattern x = (ExpandCollapsePattern)p;
            if (x.Current.ExpandCollapseState == ExpandCollapseState.Collapsed) x.Expand(); else x.Collapse();
            return true;
        }
        return false;
    }

    static T Pattern<T>(AutomationElement e, AutomationPattern ap, string what) where T : BasePattern {
        object p;
        if (!e.TryGetCurrentPattern(ap, out p)) throw new BridgeError("NOT_SUPPORTED", "This object cannot be " + what + " (" + e.Current.ControlType.ProgrammaticName.Replace("ControlType.", "") + " '" + e.Current.Name + "')");
        return (T)p;
    }

    static string Text(AutomationElement e) {
        object p;
        string t;
        if (e.TryGetCurrentPattern(ValuePattern.Pattern, out p)) t = ((ValuePattern)p).Current.Value ?? "";
        else if (e.TryGetCurrentPattern(TextPattern.Pattern, out p)) t = ((TextPattern)p).DocumentRange.GetText(-1) ?? "";
        else return e.Current.Name ?? "";
        // rich edit controls report their closing paragraph mark as a trailing carriage return
        return t.EndsWith("\r") && !t.EndsWith("\r\n") ? t.Substring(0, t.Length - 1) : t;
    }

    static readonly Condition ItemCondition = new OrCondition(
        new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ListItem),
        new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.TreeItem),
        new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.DataItem),
        new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.MenuItem),
        new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.TabItem));

    static bool ExpandIfCollapsed(AutomationElement e) {
        object p;
        if (!e.TryGetCurrentPattern(ExpandCollapsePattern.Pattern, out p)) return false;
        ExpandCollapsePattern x = (ExpandCollapsePattern)p;
        if (x.Current.ExpandCollapseState != ExpandCollapseState.Collapsed) return false;
        try { x.Expand(); Thread.Sleep(200); return true; } catch (Exception) { return false; }
    }

    static void CollapseQuietly(AutomationElement e) {
        object p;
        try {
            if (e.TryGetCurrentPattern(ExpandCollapsePattern.Pattern, out p) && ((ExpandCollapsePattern)p).Current.ExpandCollapseState == ExpandCollapseState.Expanded)
                ((ExpandCollapsePattern)p).Collapse();
        } catch (Exception) { }
    }

    static List<object> Summaries(AutomationElementCollection col, int max) {
        List<object> list = new List<object>();
        foreach (AutomationElement c in col) {
            if (list.Count >= max) break;
            Dictionary<string, object> d = new Dictionary<string, object>();
            d["Name"] = Fmt(c.Current.Name); d["ControlType"] = Fmt(c.Current.ControlType);
            d["AutomationId"] = Fmt(c.Current.AutomationId); d["ClassName"] = Fmt(c.Current.ClassName);
            object p; d["Value"] = c.TryGetCurrentPattern(ValuePattern.Pattern, out p) ? (((ValuePattern)p).Current.Value ?? "") : "";
            list.Add(d);
        }
        return list;
    }

    static void SelectItem(AutomationElement e, string item, int index) {
        object p;
        if (item == null && index < 0) { Pattern<SelectionItemPattern>(e, SelectionItemPattern.Pattern, "selected").Select(); return; }
        bool expanded = ExpandIfCollapsed(e);
        try {
            AutomationElementCollection items = e.FindAll(TreeScope.Descendants, ItemCondition);
            AutomationElement target = null;
            if (item != null) {
                foreach (AutomationElement i in items) if (string.Equals(i.Current.Name, item, StringComparison.OrdinalIgnoreCase)) { target = i; break; }
                if (target == null) foreach (AutomationElement i in items) if (string.Equals((i.Current.Name ?? "").Trim(), item.Trim(), StringComparison.OrdinalIgnoreCase)) { target = i; break; }
            } else if (index < items.Count) target = items[index];
            if (target == null) {
                if (item != null && e.TryGetCurrentPattern(ValuePattern.Pattern, out p) && !((ValuePattern)p).Current.IsReadOnly) { ((ValuePattern)p).SetValue(item); return; }
                List<string> names = new List<string>();
                foreach (AutomationElement i in items) { if (names.Count >= 20) { names.Add("..."); break; } names.Add(i.Current.Name); }
                throw new BridgeError("ITEM_NOT_FOUND", (item != null ? "No item named '" + item + "'" : "No item at position " + index) + " among " + items.Count + " item(s): " + string.Join(", ", names.ToArray()));
            }
            ScrollIntoView(target);
            if (target.TryGetCurrentPattern(SelectionItemPattern.Pattern, out p)) ((SelectionItemPattern)p).Select();
            else if (target.TryGetCurrentPattern(InvokePattern.Pattern, out p)) ((InvokePattern)p).Invoke();
            else MouseClick(target, "left", 1, 0, 0);
        } finally { if (expanded) CollapseQuietly(e); }
    }

    static void SetToggle(AutomationElement e, bool on) {
        object p;
        if (e.TryGetCurrentPattern(TogglePattern.Pattern, out p)) {
            TogglePattern t = (TogglePattern)p;
            ToggleState want = on ? ToggleState.On : ToggleState.Off;
            for (int i = 0; i < 3 && t.Current.ToggleState != want; i++) t.Toggle();
            if (t.Current.ToggleState != want) throw new BridgeError("FAILED", "The object did not reach the " + (on ? "checked" : "unchecked") + " state");
            return;
        }
        if (on && e.TryGetCurrentPattern(SelectionItemPattern.Pattern, out p)) { ((SelectionItemPattern)p).Select(); return; }
        throw new BridgeError("NOT_SUPPORTED", "This object cannot be " + (on ? "checked" : "unchecked"));
    }

    static string CellText(AutomationElement c) {
        if (c == null) return "";
        object p;
        if (c.TryGetCurrentPattern(ValuePattern.Pattern, out p)) { string v = ((ValuePattern)p).Current.Value; if (!string.IsNullOrEmpty(v)) return v; }
        string n = c.Current.Name;
        if (!string.IsNullOrEmpty(n)) return n;
        AutomationElement child = TreeWalker.ControlViewWalker.GetFirstChild(c);
        return child == null ? "" : (child.Current.Name ?? "");
    }

    static Dictionary<string, object> Table(AutomationElement e, int maxRows) {
        List<string> cols = new List<string>();
        List<object> rows = new List<object>();
        object p;
        AutomationElement header = e.FindFirst(TreeScope.Children, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Header));
        if (e.TryGetCurrentPattern(GridPattern.Pattern, out p)) {
            GridPattern g = (GridPattern)p;
            int rc = Math.Min(g.Current.RowCount, maxRows), cc = g.Current.ColumnCount;
            object tp;
            if (e.TryGetCurrentPattern(TablePattern.Pattern, out tp)) foreach (AutomationElement h in ((TablePattern)tp).Current.GetColumnHeaders()) cols.Add(h.Current.Name);
            if (cols.Count != cc && header != null) { cols.Clear(); foreach (AutomationElement h in header.FindAll(TreeScope.Children, Condition.TrueCondition)) cols.Add(h.Current.Name); }
            for (int r = 0; r < rc; r++) {
                List<object> row = new List<object>();
                for (int c = 0; c < cc; c++) { AutomationElement cell = null; try { cell = g.GetItem(r, c); } catch (Exception) { } row.Add(CellText(cell)); }
                rows.Add(row);
            }
            while (cols.Count < cc) cols.Add("");
            if (cols.Count > cc) cols.RemoveRange(cc, cols.Count - cc);
        } else {
            if (header != null) foreach (AutomationElement h in header.FindAll(TreeScope.Children, Condition.TrueCondition)) cols.Add(h.Current.Name);
            Condition rowCond = new OrCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.DataItem),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ListItem),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.TreeItem),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Custom));
            int width = cols.Count;
            foreach (AutomationElement r in e.FindAll(TreeScope.Children, rowCond)) {
                if (rows.Count >= maxRows) break;
                List<object> row = new List<object>();
                AutomationElementCollection cells = r.FindAll(TreeScope.Children, Condition.TrueCondition);
                if (cells.Count == 0) row.Add(CellText(r)); else foreach (AutomationElement c in cells) row.Add(CellText(c));
                width = Math.Max(width, row.Count);
                rows.Add(row);
            }
            while (cols.Count < width) cols.Add("");
            foreach (object o in rows) { List<object> row = (List<object>)o; while (row.Count < cols.Count) row.Add(""); }
            HeaderRow(cols, rows);
        }
        Dictionary<string, object> t = new Dictionary<string, object>();
        t["columns"] = cols; t["rows"] = rows;
        return t;
    }

    /**
     * A Windows Forms grid (DataGridView) lists its column headers as the first row ("Top Left Header Cell", then
     * the names) and a row header ("Row 0" ...) before each row: use the names as columns and drop the row headers.
     */
    static void HeaderRow(List<string> cols, List<object> rows) {
        if (rows.Count == 0) return;
        foreach (string c in cols) if (c != "") return;
        List<object> first = (List<object>)rows[0];
        if (first.Count == 0 || Convert.ToString(first[0], CultureInfo.InvariantCulture) != "Top Left Header Cell") return;
        cols.Clear();
        foreach (object x in first) cols.Add(Convert.ToString(x, CultureInfo.InvariantCulture));
        rows.RemoveAt(0);
        bool rowHeaders = true;
        foreach (object o in rows) { List<object> r = (List<object>)o; string h = r.Count == 0 ? "" : Convert.ToString(r[0], CultureInfo.InvariantCulture); if (h != "" && !System.Text.RegularExpressions.Regex.IsMatch(h, "^Row [0-9]+$")) { rowHeaders = false; break; } }
        if (!rowHeaders) { cols[0] = "Row"; return; }
        cols.RemoveAt(0);
        foreach (object o in rows) { List<object> r = (List<object>)o; if (r.Count > 0) r.RemoveAt(0); }
    }

    static object Act(Dictionary<string, object> req) {
        AutomationElement e = Ref(req);
        string action = Str(req, "action", "");
        switch (action) {
            case "click": {
                string button = Str(req, "button", "left"), method = Str(req, "method", "auto");
                int count = Bool(req, "double", false) ? 2 : 1;
                if (method != "mouse" && button == "left" && count == 1) {
                    if (PatternClick(e)) return "pattern";
                    if (method == "pattern") throw new BridgeError("NOT_SUPPORTED", "This object offers no UI Automation pattern for a click; use the mouse method");
                } else if (method == "pattern") throw new BridgeError("NOT_SUPPORTED", "Only a single left click can be done without the mouse");
                MouseClick(e, button, count, Int(req, "offset_x", 0), Int(req, "offset_y", 0));
                return "mouse";
            }
            case "set_text": {
                string text = Str(req, "text", ""), method = Str(req, "method", "auto");
                bool clear = Bool(req, "clear_first", true);
                object p;
                bool hasValue = e.TryGetCurrentPattern(ValuePattern.Pattern, out p);
                if (method == "set_value" || (method == "auto" && hasValue && !((ValuePattern)p).Current.IsReadOnly)) {
                    if (!hasValue) throw new BridgeError("NOT_SUPPORTED", "This object does not accept a value directly; use the type method");
                    ValuePattern v = (ValuePattern)p;
                    try { v.SetValue(clear ? text : (v.Current.Value ?? "") + text); return "set_value"; }
                    catch (InvalidOperationException) { if (method == "set_value") throw; }
                }
                Activate(e);
                try { e.SetFocus(); } catch (InvalidOperationException) { MouseClick(e, "left", 1, 0, 0); }
                Thread.Sleep(50);
                if (clear) Native.SelectAllAndDelete();
                Native.Type(text, Int(req, "delay_ms", 0));
                return "type";
            }
            case "get_text": return Text(e);
            case "properties": return Detail(e, true);
            case "focus": e.SetFocus(); return true;
            case "invoke": Pattern<InvokePattern>(e, InvokePattern.Pattern, "invoked").Invoke(); return true;
            case "expand": Pattern<ExpandCollapsePattern>(e, ExpandCollapsePattern.Pattern, "expanded").Expand(); return true;
            case "collapse": Pattern<ExpandCollapsePattern>(e, ExpandCollapsePattern.Pattern, "collapsed").Collapse(); return true;
            case "toggle": { TogglePattern t = Pattern<TogglePattern>(e, TogglePattern.Pattern, "toggled"); t.Toggle(); return t.Current.ToggleState.ToString(); }
            case "check": SetToggle(e, true); return true;
            case "uncheck": SetToggle(e, false); return true;
            case "select_item": SelectItem(e, req.ContainsKey("item") && req["item"] != null ? Str(req, "item", "") : null, Int(req, "item_index", -1)); return true;
            case "scroll_into_view": {
                object p;
                if (!e.TryGetCurrentPattern(ScrollItemPattern.Pattern, out p)) return false;
                ((ScrollItemPattern)p).ScrollIntoView();
                return true;
            }
            case "coordinates": {
                System.Windows.Rect r = Bounds(e);
                Dictionary<string, object> d = new Dictionary<string, object>();
                d["x"] = (int)r.X; d["y"] = (int)r.Y; d["width"] = (int)r.Width; d["height"] = (int)r.Height;
                d["center_x"] = (int)(r.X + r.Width / 2); d["center_y"] = (int)(r.Y + r.Height / 2);
                return d;
            }
            case "children": return Summaries(e.FindAll(TreeScope.Children, Condition.TrueCondition), Int(req, "max", 2000));
            case "list_items": {
                bool expanded = ExpandIfCollapsed(e);
                try {
                    List<object> names = new List<object>();
                    foreach (AutomationElement i in e.FindAll(TreeScope.Descendants, ItemCondition)) names.Add(i.Current.Name ?? "");
                    return names;
                } finally { if (expanded) CollapseQuietly(e); }
            }
            case "table": return Table(e, Int(req, "max_rows", 10000));
            default: throw new BridgeError("BAD_REQUEST", "Unknown object action '" + action + "'");
        }
    }

    // ---------- capture ----------
    static Dictionary<string, object> Summary(AutomationElement a) {
        Dictionary<string, object> d = new Dictionary<string, object>();
        d["ControlType"] = Fmt(a.Current.ControlType);
        if (!string.IsNullOrEmpty(a.Current.Name)) d["Name"] = a.Current.Name;
        if (!string.IsNullOrEmpty(a.Current.ClassName)) d["ClassName"] = a.Current.ClassName;
        if (!string.IsNullOrEmpty(a.Current.AutomationId)) d["AutomationId"] = a.Current.AutomationId;
        return d;
    }

    static string Screenshot(System.Windows.Rect r) {
        if (r.IsEmpty || r.Width < 1 || r.Height < 1) return "";
        int w = (int)Math.Min(r.Width, 1600), h = (int)Math.Min(r.Height, 1200);
        using (System.Drawing.Bitmap bmp = new System.Drawing.Bitmap(w, h))
        using (System.Drawing.Graphics g = System.Drawing.Graphics.FromImage(bmp))
        using (MemoryStream ms = new MemoryStream()) {
            g.CopyFromScreen((int)r.X, (int)r.Y, 0, 0, new System.Drawing.Size(w, h));
            bmp.Save(ms, System.Drawing.Imaging.ImageFormat.Png);
            return Convert.ToBase64String(ms.ToArray());
        }
    }

    /** The top-level window an element belongs to (the element itself for a window). */
    static AutomationElement TopOf(AutomationElement e) {
        TreeWalker w = TreeWalker.ControlViewWalker;
        AutomationElement root = AutomationElement.RootElement, cur = e, parent;
        while ((parent = w.GetParent(cur)) != null && !Automation.Compare(parent, root)) cur = parent;
        return cur;
    }

    static string Quote(string v) { return v.IndexOf('\'') < 0 ? "'" + v + "'" : "\"" + v.Replace("\"", "") + "\""; }

    /**
     * Every property: the UI Automation ones, states, the text, and where the control sits in its window: Path (child
     * positions from the window, 1 = first), XPath (ControlType[n] steps, the last one with its AutomationId or Name),
     * Index (position among its siblings), Parent, and Left/Top/Width/Height relative to the window.
     */
    static Dictionary<string, object> Detail(AutomationElement e, bool fullText) {
        Dictionary<string, object> d = Live(e, true);
        TreeWalker w = TreeWalker.ControlViewWalker;
        AutomationElement root = AutomationElement.RootElement, cur = e, parent, top = e;
        List<string> idx = new List<string>(), steps = new List<string>();
        bool first = true;
        while ((parent = w.GetParent(cur)) != null && !Automation.Compare(parent, root)) {
            string ct = Fmt(cur.Current.ControlType);
            int n = 0, same = 0, pos = 0, samePos = 0;
            for (AutomationElement c = w.GetFirstChild(parent); c != null; c = w.GetNextSibling(c)) {
                n++;
                if (Fmt(c.Current.ControlType) == ct) same++;
                if (Automation.Compare(c, cur)) { pos = n; samePos = same; break; }
            }
            string step = ct + "[" + samePos + "]";
            if (first) {
                d["Index"] = pos.ToString(CultureInfo.InvariantCulture);
                string pn = parent.Current.Name;
                d["Parent"] = Fmt(parent.Current.ControlType) + (string.IsNullOrEmpty(pn) ? "" : " " + Quote(pn.Length > 60 ? pn.Substring(0, 60) : pn));
                if (!string.IsNullOrEmpty(cur.Current.AutomationId)) step = ct + "[@AutomationId=" + Quote(cur.Current.AutomationId) + "]";
                else if (!string.IsNullOrEmpty(cur.Current.Name) && cur.Current.Name.Length <= 80) step += "[@Name=" + Quote(cur.Current.Name) + "]";
                first = false;
            }
            idx.Insert(0, pos.ToString(CultureInfo.InvariantCulture));
            steps.Insert(0, step);
            cur = parent;
        }
        top = cur;
        d["Path"] = string.Join("/", idx.ToArray());
        d["XPath"] = "/" + string.Join("/", steps.ToArray());
        if (!d.ContainsKey("Index")) { d["Index"] = ""; d["Parent"] = ""; }
        System.Windows.Rect r = e.Current.BoundingRectangle, wr = top.Current.BoundingRectangle;
        if (!r.IsEmpty && !double.IsInfinity(r.X)) {
            double ox = wr.IsEmpty || Automation.Compare(top, e) ? 0 : wr.X, oy = wr.IsEmpty || Automation.Compare(top, e) ? 0 : wr.Y;
            d["Left"] = ((int)(r.X - ox)).ToString(CultureInfo.InvariantCulture); d["Top"] = ((int)(r.Y - oy)).ToString(CultureInfo.InvariantCulture);
            d["Width"] = ((int)r.Width).ToString(CultureInfo.InvariantCulture); d["Height"] = ((int)r.Height).ToString(CultureInfo.InvariantCulture);
        }
        List<string> st = new List<string>();
        if (Convert.ToString(d["IsEnabled"]) == "false") st.Add("Disabled");
        if (Convert.ToString(d["IsKeyboardFocusable"]) == "true") st.Add("Focusable");
        if (Convert.ToString(d["HasKeyboardFocus"]) == "true") st.Add("Focused");
        if (Convert.ToString(d["IsOffscreen"]) == "true") st.Add("Offscreen");
        if (Convert.ToString(d["IsPassword"]) == "true") st.Add("Password");
        object v;
        if (d.TryGetValue("IsReadOnly", out v) && Convert.ToString(v) == "true") st.Add("ReadOnly");
        if (d.TryGetValue("ToggleState", out v) && Convert.ToString(v) == "On") st.Add("Checked");
        if (d.TryGetValue("IsSelected", out v) && Convert.ToString(v) == "true") st.Add("Selected");
        if (d.TryGetValue("ExpandCollapseState", out v) && Convert.ToString(v) == "Expanded") st.Add("Expanded");
        d["States"] = string.Join(",", st.ToArray());
        string t = "";
        if (Convert.ToString(d["IsPassword"]) != "true") { try { t = Text(e); } catch (Exception) { t = ""; } }
        int maxText = fullText ? 100000 : 1000;
        d["Text"] = t.Length > maxText ? t.Substring(0, maxText) : t;
        return d;
    }

    static object Resize(int handle, int width, int height) {
        if (handle == 0 || width <= 0 || height <= 0) throw new BridgeError("BAD_REQUEST", "resize needs a window handle, width and height");
        IntPtr h = new IntPtr(handle);
        System.Windows.Rect r = AutomationElement.FromHandle(h).Current.BoundingRectangle;
        if (Native.IsIconic(h)) Native.ShowWindow(h, 9);
        Native.ShowWindow(h, 1);   // restore from maximized so the size applies
        Native.MoveWindow(h, r.IsEmpty ? 0 : (int)r.X, r.IsEmpty ? 0 : (int)r.Y, width, height, true);
        return true;
    }

    static string ProcessPath(int pid) {
        try { return Process.GetProcessById(pid).MainModule.FileName; } catch (Exception) { return ""; }
    }

    static Dictionary<string, object> CaptureAt(int x, int y, bool shot) {
        AutomationElement e = AutomationElement.FromPoint(new System.Windows.Point(x, y));
        Dictionary<string, object> props = Detail(e, false);
        TreeWalker walker = TreeWalker.ControlViewWalker;
        AutomationElement root = AutomationElement.RootElement;
        List<AutomationElement> ancestors = new List<AutomationElement>();
        AutomationElement cur = walker.GetParent(e);
        while (cur != null && !Automation.Compare(cur, root)) { ancestors.Insert(0, cur); cur = walker.GetParent(cur); }
        AutomationElement top = ancestors.Count > 0 ? ancestors[0] : e;
        Dictionary<string, object> win = new Dictionary<string, object>();
        win["title"] = top.Current.Name ?? "";
        win["class_name"] = top.Current.ClassName ?? "";
        win["process"] = ProcessName(top.Current.ProcessId);
        win["handle"] = top.Current.NativeWindowHandle;
        win["path"] = ProcessPath(top.Current.ProcessId);
        System.Windows.Rect tr = top.Current.BoundingRectangle;
        if (!tr.IsEmpty) { win["width"] = (int)tr.Width; win["height"] = (int)tr.Height; }
        List<object> path = new List<object>();
        foreach (AutomationElement a in ancestors) path.Add(Summary(a));
        Dictionary<string, object> d = new Dictionary<string, object>();
        d["element"] = props; d["window"] = win; d["path"] = path;
        d["screenshot"] = shot ? Screenshot(e.Current.BoundingRectangle) : "";
        d["point"] = new int[] { x, y };
        return d;
    }

    // ---------- desktop recorder ----------
    struct Click { public int X, Y; public string Button; public DateTime At; }
    static readonly Queue<Click> Clicks = new Queue<Click>();
    static volatile bool KeyTyped, KeyFlush;
    static Native.HookProc MouseProc, KeyProc;   // kept alive while hooked

    /**
     * Records clicks and typing in any desktop application until Stop recording is pressed (or the time runs out).
     * A click is captured where the mouse went down (the control under it, before it reacts); typing is captured as
     * the field's final text when the person leaves the field (passwords are never read). Two clicks on the same
     * control within half a second become a double click.
     */
    /** A control whose text the person can type: an edit, document or editable combo box, or anything with a writable value. */
    static bool TakesText(AutomationElement e) {
        ControlType ct = e.Current.ControlType;
        if (ct == ControlType.Edit || ct == ControlType.Document) return true;
        object p;
        return e.TryGetCurrentPattern(ValuePattern.Pattern, out p) && !((ValuePattern)p).Current.IsReadOnly && ct != ControlType.Button;
    }

    static List<object> Record(object reqId, int timeoutMs, bool shot) {
        List<object> events = new List<object>();
        lock (Clicks) Clicks.Clear();
        KeyTyped = false; KeyFlush = false;
        // The hooks and the bar live on their own thread with its own message loop, so they are answered at once
        // while this thread reads the controls (which can take a moment in a busy application).
        RecordBar bar = null; IntPtr mh = IntPtr.Zero, kh = IntPtr.Zero;
        ManualResetEvent shown = new ManualResetEvent(false);
        Thread ui = new Thread(() => {
            bar = new RecordBar();
            IntPtr mod = Native.GetModuleHandle(null);
            MouseProc = (code, w, l) => {
                if (code >= 0) {
                    int msg = w.ToInt32();
                    if (msg == 0x0201 || msg == 0x0204) {   // left or right button down
                        Native.MSLLHOOKSTRUCT m = (Native.MSLLHOOKSTRUCT)Marshal.PtrToStructure(l, typeof(Native.MSLLHOOKSTRUCT));
                        lock (Clicks) Clicks.Enqueue(new Click { X = m.pt.X, Y = m.pt.Y, Button = msg == 0x0201 ? "left" : "right", At = DateTime.UtcNow });
                    }
                }
                return Native.CallNextHookEx(IntPtr.Zero, code, w, l);
            };
            KeyProc = (code, w, l) => {
                if (code >= 0 && (w.ToInt32() == 0x0100 || w.ToInt32() == 0x0104)) {   // key down
                    Native.KBDLLHOOKSTRUCT k = (Native.KBDLLHOOKSTRUCT)Marshal.PtrToStructure(l, typeof(Native.KBDLLHOOKSTRUCT));
                    if (k.vk == 0x0D || k.vk == 0x09) KeyFlush = true;   // Enter or Tab ends a field
                    else KeyTyped = true;
                }
                return Native.CallNextHookEx(IntPtr.Zero, code, w, l);
            };
            mh = Native.SetWindowsHookEx(14, MouseProc, mod, 0);
            kh = Native.SetWindowsHookEx(13, KeyProc, mod, 0);
            bar.Shown += (o, e) => shown.Set();
            System.Windows.Forms.Application.Run(bar);
            Native.UnhookWindowsHookEx(mh); Native.UnhookWindowsHookEx(kh);
        });
        ui.SetApartmentState(ApartmentState.STA);
        ui.IsBackground = true;
        ui.Start();
        shown.WaitOne(10000);
        Action<int> setCount = n => { try { bar.BeginInvoke((Action)(() => bar.SetCount(n))); } catch (Exception) { } };

        AutomationElement typingIn = null; Dictionary<string, object> typingCapture = null;
        Dictionary<string, object> lastClick = null; Click last = default(Click);
        Action<string> progress = kind => { setCount(events.Count); Progress(reqId, new Dictionary<string, object> { { "count", events.Count }, { "kind", kind } }); };
        Action flushTyping = () => {
            if (typingIn == null) return;
            try {
                bool pwd = false; try { pwd = typingIn.Current.IsPassword; } catch (Exception) { }
                Dictionary<string, object> ev = new Dictionary<string, object>();
                ev["kind"] = "type"; ev["capture"] = typingCapture; ev["password"] = pwd;
                ev["text"] = pwd ? null : Text(typingIn);
                events.Add(ev);
                progress("type");
            } catch (ElementNotAvailableException) { }
            typingIn = null; typingCapture = null;
        };
        DateTime end = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        try {
            while (!bar.Stopped && DateTime.UtcNow < end && ui.IsAlive) {
                Click c; bool has;
                lock (Clicks) { has = Clicks.Count > 0; c = has ? Clicks.Dequeue() : default(Click); }
                if (has && !bar.Contains(c.X, c.Y)) {
                    bool dbl = lastClick != null && c.Button == "left" && last.Button == "left"
                            && Math.Abs(c.X - last.X) <= 4 && Math.Abs(c.Y - last.Y) <= 4 && (c.At - last.At).TotalMilliseconds < 500;
                    last = c;
                    if (dbl) { lastClick["kind"] = "double_click"; lastClick = null; continue; }
                    flushTyping();
                    try {
                        Dictionary<string, object> ev = new Dictionary<string, object>();
                        ev["kind"] = c.Button == "right" ? "right_click" : "click"; ev["capture"] = CaptureAt(c.X, c.Y, shot);
                        events.Add(ev); lastClick = ev;
                        progress((string)ev["kind"]);
                    } catch (Exception) { /* the control went away before it could be read */ }
                }
                if (KeyTyped) {
                    KeyTyped = false;
                    try {
                        AutomationElement f = AutomationElement.FocusedElement;
                        if (f != null && f.Current.ProcessId != Process.GetCurrentProcess().Id && TakesText(f) && (typingIn == null || !Automation.Compare(f, typingIn))) {
                            flushTyping();
                            System.Windows.Rect r = f.Current.BoundingRectangle;
                            if (!r.IsEmpty) { typingIn = f; typingCapture = CaptureAt((int)(r.X + r.Width / 2), (int)(r.Y + r.Height / 2), shot); }
                        }
                    } catch (Exception) { }
                }
                if (KeyFlush) { KeyFlush = false; Thread.Sleep(80); flushTyping(); }
                if (!has) Thread.Sleep(20);
            }
            flushTyping();
        } finally {
            try { bar.BeginInvoke((Action)(() => bar.Close())); } catch (Exception) { }
            ui.Join(3000);
        }
        return events;
    }

    static Dictionary<string, object> Pick(int timeoutMs, bool shot, bool highlight) {
        Frame[] frames = new Frame[0];
        if (highlight) {
            frames = new Frame[4];
            for (int i = 0; i < 4; i++) { frames[i] = new Frame(); frames[i].Show(); }
        }
        try {
            DateTime end = DateTime.UtcNow.AddMilliseconds(timeoutMs), nextHover = DateTime.MinValue;
            string lastRect = null;
            while (DateTime.UtcNow < end) {
                System.Windows.Forms.Application.DoEvents();
                Native.POINT pt;
                Native.GetCursorPos(out pt);
                if (Native.Down(0x11) && Native.Down(0x10) && Native.Down(0x43)) {
                    foreach (Frame f in frames) f.Hide();
                    while (Native.Down(0x43)) Thread.Sleep(20);
                    return CaptureAt(pt.X, pt.Y, shot);
                }
                if (highlight && DateTime.UtcNow >= nextHover) {
                    nextHover = DateTime.UtcNow.AddMilliseconds(250);
                    try {
                        System.Windows.Rect r = AutomationElement.FromPoint(new System.Windows.Point(pt.X, pt.Y)).Current.BoundingRectangle;
                        string key = r.ToString();
                        if (key != lastRect && !r.IsEmpty) {
                            lastRect = key;
                            int x = (int)r.X, y = (int)r.Y, w = Math.Max(1, (int)r.Width), h = Math.Max(1, (int)r.Height), t = 2;
                            frames[0].Bounds = new System.Drawing.Rectangle(x - t, y - t, w + 2 * t, t);
                            frames[1].Bounds = new System.Drawing.Rectangle(x - t, y + h, w + 2 * t, t);
                            frames[2].Bounds = new System.Drawing.Rectangle(x - t, y, t, h);
                            frames[3].Bounds = new System.Drawing.Rectangle(x + w, y, t, h);
                        }
                    } catch (Exception) { }
                }
                Thread.Sleep(30);
            }
            throw new BridgeError("TIMEOUT", "No object was captured within " + (timeoutMs / 1000) + " s. Point at the control and press Ctrl+Shift+C");
        } finally {
            foreach (Frame f in frames) { f.Close(); f.Dispose(); }
        }
    }
}
}
'@

Add-Type -TypeDefinition $source -ReferencedAssemblies $refs -Language CSharp
[OoaUia.Bridge]::Run()
