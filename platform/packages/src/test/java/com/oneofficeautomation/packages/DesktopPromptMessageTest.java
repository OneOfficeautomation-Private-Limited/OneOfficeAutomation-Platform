package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTextArea;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** prompt_file and prompt_folder: the optional message shown in the dialog. */
class DesktopPromptMessageTest {
    @TempDir Path tmp;

    @Test void messageTextForThePicker() {
        assertNull(DesktopPackage.pickerMessage(null));
        assertNull(DesktopPackage.pickerMessage("   "));
        JTextArea a = DesktopPackage.pickerMessage("  Pick this month's bank statement.\nOnly PDF files.  ");
        assertNotNull(a);
        assertEquals("Pick this month's bank statement.\nOnly PDF files.", a.getText());
        assertFalse(a.isEditable());
        assertTrue(a.getLineWrap());
        assertTrue(a.getWrapStyleWord());
    }

    @Test void inputsAreCheckedAndHeadlessRunsStopClearly() {
        ActionContext c = PackagesTestSupport.ctx(tmp, Map.of());
        DesktopPackage d = new DesktopPackage();
        assertEquals("DESKTOP.BAD_FILE_TYPE", assertThrows(ActionException.class,
                () -> d.promptFile(c, "t", null, "a/b", false, 1, true, "Pick a file")).code());
        if (!java.awt.GraphicsEnvironment.isHeadless()) { System.out.println("    (desktop session present; headless checks skipped)"); return; }
        assertEquals("DESKTOP.NO_SESSION", assertThrows(ActionException.class,
                () -> d.promptFile(c, "t", null, "pdf", false, 1, true, "Pick a file")).code());
        assertEquals("DESKTOP.NO_SESSION", assertThrows(ActionException.class,
                () -> d.promptFolder(c, "t", null, 1, true, "Pick a folder")).code());
    }
}
