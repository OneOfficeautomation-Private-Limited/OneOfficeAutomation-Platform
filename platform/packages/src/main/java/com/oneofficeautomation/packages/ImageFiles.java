package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Saves images with the options other RPA tools offer on their image steps: JPEG quality, TIFF compression and colour depth.
 * Used by screen capture and the PDF image actions so every image step writes files the same way.
 */
public final class ImageFiles {
    private ImageFiles() {}

    /** TIFF compressions offered to flows, mapped to the names the JDK TIFF writer uses. ccitt_g4 needs black-and-white images. */
    public static final List<String> TIFF_COMPRESSIONS = List.of("lzw", "deflate", "packbits", "jpeg", "ccitt_g4", "none");

    /**
     * Writes {@code img} as png, jpg, bmp, gif or tiff.
     *
     * @param jpegQuality     1-100 for JPEG (and TIFF with jpeg compression); null keeps the writer's default (75)
     * @param tiffCompression one of {@link #TIFF_COMPRESSIONS}; null means lzw
     */
    public static void write(BufferedImage img, String format, File file, Integer jpegQuality, String tiffCompression) throws IOException {
        String fmt = format.toLowerCase(Locale.ROOT);
        if (fmt.equals("jpeg")) fmt = "jpg";
        if (fmt.equals("tif")) fmt = "tiff";
        if (jpegQuality != null && (jpegQuality < 1 || jpegQuality > 100))
            throw new ActionException("IMAGE.BAD_QUALITY", "JPEG quality must be between 1 and 100, not " + jpegQuality, "Use 90 for good quality, or lower for smaller files.");
        String tiff = tiffCompression == null || tiffCompression.isBlank() ? "lzw" : tiffCompression.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (fmt.equals("tiff") && !TIFF_COMPRESSIONS.contains(tiff))
            throw new ActionException("IMAGE.BAD_COMPRESSION", "Unknown TIFF compression '" + tiffCompression + "'", "Use one of " + String.join(", ", TIFF_COMPRESSIONS) + ".");
        BufferedImage out = img;
        if (fmt.equals("jpg") || fmt.equals("bmp") || (fmt.equals("tiff") && tiff.equals("jpeg"))) out = opaque(img);
        if (fmt.equals("tiff") && tiff.equals("ccitt_g4")) out = binary(img);
        if (file.getParentFile() != null) Files.createDirectories(file.getParentFile().toPath());
        Files.deleteIfExists(file.toPath());  // ImageIO streams do not truncate a longer existing file
        String writerName = fmt.equals("jpg") ? "jpeg" : fmt;
        boolean custom = (fmt.equals("jpg") && jpegQuality != null) || fmt.equals("tiff");
        if (!custom) {
            if (!ImageIO.write(out, writerName, file)) throw new IOException("No " + fmt + " writer available");
            return;
        }
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName(writerName);
        if (!it.hasNext()) throw new IOException("No " + fmt + " writer available");
        ImageWriter w = it.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(file)) {
            w.setOutput(ios);
            ImageWriteParam p = w.getDefaultWriteParam();
            if (p.canWriteCompressed()) {
                if (fmt.equals("jpg")) {
                    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionQuality(jpegQuality / 100f);
                } else if (!tiff.equals("none")) {
                    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionType(switch (tiff) {
                        case "deflate" -> "Deflate";
                        case "packbits" -> "PackBits";
                        case "jpeg" -> "JPEG";
                        case "ccitt_g4" -> "CCITT T.6";
                        default -> "LZW";
                    });
                    if (tiff.equals("jpeg") && jpegQuality != null) p.setCompressionQuality(jpegQuality / 100f);
                } else {
                    p.setCompressionMode(ImageWriteParam.MODE_DISABLED);
                }
            }
            w.write(null, new IIOImage(out, null, null), p);
        } finally {
            w.dispose();
        }
    }

    /** RGB copy on white, for formats without an alpha channel. Grey and 1-bit images are kept as they are. */
    public static BufferedImage opaque(BufferedImage img) {
        int t = img.getType();
        if (t == BufferedImage.TYPE_INT_RGB || t == BufferedImage.TYPE_BYTE_GRAY || t == BufferedImage.TYPE_BYTE_BINARY || t == BufferedImage.TYPE_3BYTE_BGR) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Greyscale (8-bit) copy. */
    public static BufferedImage gray(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) return img;
        BufferedImage g = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gr = g.createGraphics();
        gr.setColor(Color.WHITE);
        gr.fillRect(0, 0, img.getWidth(), img.getHeight());
        gr.drawImage(img, 0, 0, null);
        gr.dispose();
        return g;
    }

    /** Black-and-white (1-bit) copy, thresholded at mid grey. */
    public static BufferedImage binary(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_BYTE_BINARY && img.getColorModel().getPixelSize() == 1) return img;
        BufferedImage g = gray(img);
        BufferedImage b = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        var src = g.getRaster();
        var dst = b.getRaster();
        for (int y = 0; y < g.getHeight(); y++)
            for (int x = 0; x < g.getWidth(); x++)
                dst.setSample(x, y, 0, src.getSample(x, y, 0) >= 128 ? 1 : 0);
        return b;
    }
}
