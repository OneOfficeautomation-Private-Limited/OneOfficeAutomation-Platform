package com.oneofficeautomation.sdk;

import java.nio.file.Path;

/**
 * A workbook open in a run's session (the Excel package's), as other packages see it without depending on that package:
 * the Excel desktop steps (G3, AA parity, 22 Nov 2026) use it to run a macro on, or export, the open workbook including
 * changes that are not saved yet.
 */
public interface OpenWorkbook {
    /** The workbook's own file (what Save writes to). */
    Path file();

    /** The name of the active sheet. */
    String activeSheet();

    /** Writes the workbook as it is now, unsaved changes included and without a password, to the target. */
    void writeCopy(Path target);

    /** Replaces the open workbook with the one in the file (e.g. after a macro changed a copy); the session's own file is kept. */
    void replaceWith(Path source);

    /** Saves the workbook to its own file, as the Save step does. */
    void save();
}
