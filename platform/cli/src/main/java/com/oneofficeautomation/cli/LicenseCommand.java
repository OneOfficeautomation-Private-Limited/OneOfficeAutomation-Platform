package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.file.*;
import java.util.*;

/**
 * {@code ooa license show FILE}: the terms of a license file and whether this release accepts its signature. Customers
 * install licenses in Administration > Licenses. Making licenses (keygen, issue) is only in the vendor build, where the
 * vendor-tools module is present (com.oneofficeautomation.vendor.VendorLicenseTools).
 */
final class LicenseCommand {
    private LicenseCommand() { }

    static final String VENDOR_TOOLS = "com.oneofficeautomation.vendor.VendorLicenseTools";

    /** True in the vendor build (the license issuing tools are on the class path). */
    static boolean vendorBuild() {
        try { Class.forName(VENDOR_TOOLS); return true; } catch (ClassNotFoundException e) { return false; }
    }

    /** ui, keygen or issue in the vendor build: the vendor tools read their own options. */
    static int vendor(String verb, List<String> args) throws Exception {
        List<String> a = new ArrayList<>(List.of(verb));
        a.addAll(args);
        return (int) Class.forName(VENDOR_TOOLS).getMethod("run", String[].class).invoke(null, (Object) a.toArray(String[]::new));
    }

    static int show(String file) throws Exception {
        String text = Files.readString(Path.of(file));
        try {
            License l = License.verify(text, License.publicKey(License.VENDOR_PUBLIC_KEY));
            System.out.println("Signature: valid for this release");
            System.out.println("License:   " + l.id() + " (" + l.edition() + ") for " + l.customer());
            System.out.println("Valid:     " + l.issued() + " to " + l.expires() + ", then " + l.graceDays() + " grace days");
            System.out.println("For:       " + (l.installId().isEmpty() ? "any installation" : "install id " + l.installId()));
            System.out.println("Seats:     " + l.seats());
            return 0;
        } catch (OoaException e) { System.err.println(e.getMessage()); return 2; }
    }
}
