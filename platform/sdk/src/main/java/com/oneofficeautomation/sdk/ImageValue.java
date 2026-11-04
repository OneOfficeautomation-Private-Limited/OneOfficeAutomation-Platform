package com.oneofficeautomation.sdk;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An Image value: a picture held in memory as PNG bytes, with its width and height in pixels. Pictures read from
 * other formats (JPEG, BMP, GIF) are converted to PNG once, so every Image is the same kind of value. Immutable.
 * Its JSON form is {"$binary": Base64, "mime": "image/png", "width": w, "height": h}; its text form is "Image 640x480".
 */
public final class ImageValue {
    public static final String PNG = "image/png";

    private final byte[] png;
    private final int width;
    private final int height;

    private ImageValue(byte[] png, int width, int height) {
        this.png = png;
        this.width = width;
        this.height = height;
    }

    /** Reads a picture from bytes in any format the JDK reads (PNG, JPEG, BMP, GIF); PNG bytes are kept as they are. */
    public static ImageValue read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("no bytes");
        // a PNG's size is in its header, so it needs no decoding (and no java.awt, which the native executable leaves out)
        if (isPng(bytes) && bytes.length >= 24 && bytes[12] == 'I' && bytes[13] == 'H' && bytes[14] == 'D' && bytes[15] == 'R') {
            int w = readInt(bytes, 16), h = readInt(bytes, 20);
            if (w > 0 && h > 0) return new ImageValue(bytes.clone(), w, h);
        }
        BufferedImage img;
        try { img = ImageIO.read(new ByteArrayInputStream(bytes)); }
        catch (IOException e) { throw new IllegalArgumentException("not a picture: " + e.getMessage()); }
        if (img == null) throw new IllegalArgumentException("not a picture this runner can read (use PNG, JPEG, BMP or GIF)");
        if (isPng(bytes)) return new ImageValue(bytes.clone(), img.getWidth(), img.getHeight());
        return of(img);
    }

    /** A picture drawn or captured in memory, stored as PNG. */
    public static ImageValue of(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { ImageIO.write(img, "png", out); }
        catch (IOException e) { throw new IllegalArgumentException("cannot store the picture as PNG: " + e.getMessage()); }
        return new ImageValue(out.toByteArray(), img.getWidth(), img.getHeight());
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    public byte[] bytes() { return png.clone(); }

    public int length() { return png.length; }

    public int width() { return width; }

    public int height() { return height; }

    public String mime() { return PNG; }

    public String base64() { return Base64.getEncoder().encodeToString(png); }

    public BufferedImage toBufferedImage() {
        try { return ImageIO.read(new ByteArrayInputStream(png)); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** The same bytes as a Binary value (image/png). */
    public BinaryValue toBinary() { return new BinaryValue(png, PNG); }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$binary", base64());
        m.put("mime", PNG);
        m.put("width", java.math.BigDecimal.valueOf(width));
        m.put("height", java.math.BigDecimal.valueOf(height));
        return m;
    }

    @Override public String toString() { return "Image " + width + "x" + height; }

    @Override public boolean equals(Object o) { return o instanceof ImageValue i && Arrays.equals(png, i.png); }

    @Override public int hashCode() { return Arrays.hashCode(png); }
}
