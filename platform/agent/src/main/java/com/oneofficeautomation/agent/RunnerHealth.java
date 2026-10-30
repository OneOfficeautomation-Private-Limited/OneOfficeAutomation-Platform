package com.oneofficeautomation.agent;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * What the Command Center can ask a runner about its own state (Runners page): its CPU and memory use (a moving
 * average of the last samples, sent with each heartbeat), its recent log lines and newest run logs, and a picture of
 * its screen. Nothing is sent unless asked, except the two percentages.
 */
final class RunnerHealth {
    /** Samples kept for the moving average: with a 10-second heartbeat, about the last 30 seconds. */
    static final int SAMPLES = 3;
    static final int LOG_LINES = 2000, RUN_LOGS = 3, RUN_LOG_CHARS = 64 * 1024, PICTURE_WIDTH = 1600;

    private final Deque<double[]> samples = new ArrayDeque<>();
    private final Deque<String> lines = new ArrayDeque<>();

    /** Remembers a line the runner logged, for logs sent on request. */
    synchronized void remember(String line) {
        lines.addLast(line);
        while (lines.size() > LOG_LINES) lines.removeFirst();
    }

    /** {cpu, mem} in % of the whole machine, averaged; empty when the platform does not tell. */
    synchronized Map<String, Object> usage() {
        try {
            if (!(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)) return Map.of();
            double cpu = os.getCpuLoad();
            long total = os.getTotalMemorySize(), free = os.getFreeMemorySize();
            if (cpu < 0 || total <= 0) return Map.of();
            samples.addLast(new double[] { cpu * 100, 100.0 * (total - free) / total });
            while (samples.size() > SAMPLES) samples.removeFirst();
            double c = 0, m = 0;
            for (double[] s : samples) { c += s[0]; m += s[1]; }
            return Map.of("cpu", Math.round(c / samples.size()), "mem", Math.round(m / samples.size()));
        } catch (RuntimeException | LinkageError e) { return Map.of(); }
    }

    /** The runner's recent log lines, then the end of its newest run logs. */
    String logs(Path workspace) {
        StringBuilder b = new StringBuilder("== Runner log (last " + LOG_LINES + " lines) ==\n");
        synchronized (this) { for (String l : lines) b.append(l).append('\n'); }
        Path dir = workspace.resolve("logs");
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                List<Path> newest = s.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed()).limit(RUN_LOGS).toList();
                for (Path p : newest) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    b.append("\n== ").append(p.getFileName()).append(text.length() > RUN_LOG_CHARS ? " (end)" : "").append(" ==\n")
                        .append(text.length() > RUN_LOG_CHARS ? text.substring(text.length() - RUN_LOG_CHARS) : text);
                }
            } catch (IOException | RuntimeException e) { b.append("\n(run logs could not be read: ").append(e.getMessage()).append(")\n"); }
        }
        return b.toString();
    }

    /**
     * A JPEG picture of all screens as Base64, at most {@link #PICTURE_WIDTH} wide; or throws with the reason (no
     * desktop, the screen is locked).
     */
    static String screen(String session) throws IOException {
        if (GraphicsEnvironment.isHeadless() || "no_desktop".equals(session)) throw new IOException("This runner has no desktop (it runs as a service)");
        if ("locked".equals(session)) throw new IOException("The screen is locked");
        BufferedImage shot;
        try {
            Rectangle all = new Rectangle();
            for (GraphicsDevice g : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) all = all.union(g.getDefaultConfiguration().getBounds());
            shot = new Robot().createScreenCapture(all);
        } catch (AWTException | SecurityException e) { throw new IOException("The screen could not be read: " + e.getMessage()); }
        int w = Math.min(PICTURE_WIDTH, shot.getWidth()), h = (int) Math.round(shot.getHeight() * (double) w / shot.getWidth());
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(shot, 0, 0, w, h, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream io = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(io);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.6f);
            writer.write(null, new IIOImage(small, null, null), p);
        } finally { writer.dispose(); }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
