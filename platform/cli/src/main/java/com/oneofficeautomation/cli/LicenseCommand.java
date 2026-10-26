package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.License;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.file.*;

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

    static int run(String[] a) throws Exception {
        if (a.length == 0) { usage(); return 1; }
        if (a[0].equals("show")) return show(a.length > 1 ? a[1] : null);
        if ((a[0].equals("keygen") || a[0].equals("issue")) && vendorBuild())
            return (int) Class.forName(VENDOR_TOOLS).getMethod("run", String[].class).invoke(null, (Object) a);
        usage();
        return 1;
    }

    static void usage() {
        System.err.println(vendorBuild() ? "usage: ooa license keygen --out DIR | issue --key FILE --customer NAME --expires YYYY-MM-DD [seats] --out FILE | show FILE"
            : "usage: ooa license show FILE   (install licenses in Administration > Licenses)");
    }

    static int show(String file) throws Exception {
        if (file == null) { usage(); return 1; }
        String text = Files.readString(Path.of(file));
        try {
            License l = License.verify(text, License.publicKey(License.VENDOR_PUBLIC_KEY));
            System.out.println("Signature: valid for this release");
            System.out.println("License:   " + l.id() + " (" + l.edition() + ") for " + l.customer());
            System.out.println("Valid:     " + l.issued() + " to " + l.expires() + ", then " + l.graceDays() + " grace days");
            System.out.println("Seats:     " + l.seats());
            return 0;
        } catch (OoaException e) { System.err.println(e.getMessage()); return 2; }
    }
}
