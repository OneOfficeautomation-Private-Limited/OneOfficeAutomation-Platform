package com.oneofficeautomation.cli;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real Windows "Open" and "Select folder" dialog (Studio defects 21 Nov 2026, item 4), for Browse… in
 * OneAgentStudio: Studio's server runs on the person's own computer, so it shows Windows' own dialog (File Explorer's
 * look, Quick access, network places) on top of the browser instead of a folder list drawn in the page. It uses the
 * shell's IFileOpenDialog through COM; the in-page list stays for every other case (not Windows, no desktop, Studio
 * used from another computer).
 */
final class NativeFileDialog {
    private static final Guid.GUID CLSID_FILE_OPEN_DIALOG = new Guid.GUID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}");
    private static final Guid.GUID IID_FILE_OPEN_DIALOG = new Guid.GUID("{d57c7288-d4ad-4768-be02-9d969532d960}");
    private static final Guid.GUID IID_SHELL_ITEM = new Guid.GUID("{43826d1e-e718-42ee-bc55-a1e261c37bfe}");
    private static final int CLSCTX_INPROC_SERVER = 1, COINIT_APARTMENTTHREADED = 2;
    private static final int FOS_PICKFOLDERS = 0x20, FOS_FORCEFILESYSTEM = 0x40, FOS_PATHMUSTEXIST = 0x800, FOS_FILEMUSTEXIST = 0x1000;
    private static final int SIGDN_FILESYSPATH = 0x80058000;
    private static final int ERROR_CANCELLED = 0x800704C7;
    // IFileOpenDialog vtable slots (IUnknown 0-2, IModalWindow.Show 3, then IFileDialog)
    private static final int SHOW = 3, SET_OPTIONS = 9, GET_OPTIONS = 10, SET_FOLDER = 12, SET_TITLE = 17, GET_RESULT = 20, RELEASE = 2;
    private static final int ITEM_GET_DISPLAY_NAME = 5;

    interface Shell32Ex extends StdCallLibrary {
        int SHCreateItemFromParsingName(WString path, Pointer bindContext, Guid.GUID riid, PointerByReference item);
    }

    private NativeFileDialog() {}

    /** True on Windows with a desktop (not a headless service). */
    static boolean available() {
        return Platform.isWindows() && !GraphicsEnvironment.isHeadless() && System.getenv("OOA_NO_NATIVE_DIALOG") == null;
    }

    /**
     * Shows the dialog and waits: the chosen path, or null when the person cancelled. {@code start} is the folder to
     * open in (or a file in it); {@code title} the dialog's title.
     */
    static String pick(boolean folder, String start, String title) throws InterruptedException {
        AtomicReference<Object> out = new AtomicReference<>();
        // COM dialogs need a single-threaded apartment of their own
        Thread t = new Thread(() -> {
            try { out.set(show(folder, start, title)); }
            catch (Throwable e) { out.set(e); }
        }, "ooa-native-file-dialog");
        t.setDaemon(true);
        t.start();
        t.join();
        Object r = out.get();
        if (r instanceof RuntimeException e) throw e;
        if (r instanceof Throwable e) throw new IllegalStateException(e.getMessage(), e);
        return (String) r;
    }

    private static String show(boolean folder, String start, String title) {
        Ole32 ole = Ole32.INSTANCE;
        int init = ole.CoInitializeEx(Pointer.NULL, COINIT_APARTMENTTHREADED).intValue();
        try {
            PointerByReference ppv = new PointerByReference();
            check(ole.CoCreateInstance(CLSID_FILE_OPEN_DIALOG, Pointer.NULL, CLSCTX_INPROC_SERVER, IID_FILE_OPEN_DIALOG, ppv).intValue(), "create the dialog");
            Pointer dlg = ppv.getValue();
            try {
                com.sun.jna.ptr.IntByReference opts = new com.sun.jna.ptr.IntByReference();
                call(dlg, GET_OPTIONS, opts);
                int flags = opts.getValue() | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST | (folder ? FOS_PICKFOLDERS : FOS_FILEMUSTEXIST);
                call(dlg, SET_OPTIONS, flags);
                if (title != null && !title.isBlank()) call(dlg, SET_TITLE, new WString(title));
                Pointer startItem = startFolder(start);
                if (startItem != null) { call(dlg, SET_FOLDER, startItem); release(startItem); }
                WinDef.HWND owner = User32.INSTANCE.GetForegroundWindow();   // the browser: the dialog sits on top of it
                int hr = call(dlg, SHOW, owner == null ? Pointer.NULL : owner.getPointer());
                if (hr == ERROR_CANCELLED) return null;
                check(hr, "show the dialog");
                PointerByReference item = new PointerByReference();
                check(call(dlg, GET_RESULT, item), "read the choice");
                Pointer it = item.getValue();
                try {
                    PointerByReference name = new PointerByReference();
                    check(call(it, ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name), "read the path");
                    String path = name.getValue().getWideString(0);
                    ole.CoTaskMemFree(name.getValue());
                    return path;
                } finally { release(it); }
            } finally { release(dlg); }
        } finally {
            if (init >= 0) ole.CoUninitialize();
        }
    }

    /** The shell item of the folder to open in (the folder of a file path); null when there is none. */
    private static Pointer startFolder(String start) {
        if (start == null || start.isBlank() || start.strip().startsWith("$")) return null;
        try {
            Path p = Path.of(start.strip());
            if (!Files.isDirectory(p)) p = p.getParent();
            if (p == null || !Files.isDirectory(p)) return null;
            Shell32Ex shell = Native.load("shell32", Shell32Ex.class);
            PointerByReference out = new PointerByReference();
            return shell.SHCreateItemFromParsingName(new WString(p.toAbsolutePath().toString()), Pointer.NULL, IID_SHELL_ITEM, out) >= 0 ? out.getValue() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int call(Pointer obj, int slot, Object... args) {
        Pointer vtbl = obj.getPointer(0);
        Function f = Function.getFunction(vtbl.getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
        Object[] all = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    private static void release(Pointer obj) { if (obj != null) call(obj, RELEASE); }

    private static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException("Windows could not " + what + " (0x" + Integer.toHexString(hr) + ")");
    }
}
