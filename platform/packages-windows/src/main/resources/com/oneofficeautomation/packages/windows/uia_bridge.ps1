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

    static object Dispatch(string op, Dictionary<string, object> req) {
        switch (op) {
            case "ping": return "pong";
            case "windows": return Windows();
            case "find": return Find(req);
            case "act": return Act(req);
            case "capture_at": return CaptureAt(Int(req, "x", 0), Int(req, "y", 0), Bool(req, "screenshot", true));
            case "pick": return Pick(Int(req, "timeout_ms", 60000), Bool(req, "screenshot", true), Bool(req, "highlight", true));
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
        foreach (AutomationElement w in col) {
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
            foreach (AutomationElement e in col) {
                if (list.Count >= max) return list;
                Dictionary<string, object> d = Cached(e);
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
        }
        Dictionary<string, object> t = new Dictionary<string, object>();
        t["columns"] = cols; t["rows"] = rows;
        return t;
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
            case "properties": return Live(e, true);
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

    static Dictionary<string, object> CaptureAt(int x, int y, bool shot) {
        AutomationElement e = AutomationElement.FromPoint(new System.Windows.Point(x, y));
        Dictionary<string, object> props = Live(e, false);
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
        List<object> path = new List<object>();
        foreach (AutomationElement a in ancestors) path.Add(Summary(a));
        Dictionary<string, object> d = new Dictionary<string, object>();
        d["element"] = props; d["window"] = win; d["path"] = path;
        d["screenshot"] = shot ? Screenshot(e.Current.BoundingRectangle) : "";
        d["point"] = new int[] { x, y };
        return d;
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
