package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The console as an app on phones and tablets (gap G69): an installable web app (PWA). The console at /next/ names
 * this manifest, so a phone's browser offers Add to Home screen / Install; the app opens full screen at the Task inbox,
 * where people approve and fill in tasks, and reaches Apps and starting agents from the menu.
 * <ul>
 * <li>The service worker only answers page loads that fail for want of a connection, with a short notice. It keeps no
 * copies of pages, API answers or files, so nothing of the console's data is stored on the phone.</li>
 * <li>Icons are drawn here (the product's two rings), so every server has them without extra files.</li>
 * </ul>
 */
final class MobileApp {
    private MobileApp() { }

    static final String THEME = "#2f6fde";
    private static final Map<Integer, byte[]> ICONS = new ConcurrentHashMap<>();

    static byte[] manifest() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", "One CommandCenter");
        m.put("short_name", "CommandCenter");
        m.put("description", "Tasks, approvals, apps and agent runs from One CommandCenter.");
        m.put("id", "./");
        m.put("start_url", "./#/inbox");
        m.put("scope", "./");
        m.put("display", "standalone");
        m.put("orientation", "any");
        m.put("background_color", "#ffffff");
        m.put("theme_color", THEME);
        m.put("icons", List.of(
            Map.of("src", "icon-192.png", "sizes", "192x192", "type", "image/png"),
            Map.of("src", "icon-512.png", "sizes", "512x512", "type", "image/png"),
            Map.of("src", "icon-512.png", "sizes", "512x512", "type", "image/png", "purpose", "maskable")));
        m.put("shortcuts", List.of(
            Map.of("name", "Task inbox", "url", "./#/inbox"),
            Map.of("name", "Apps", "url", "./#/apps")));
        return JsonLite.write(m).getBytes(StandardCharsets.UTF_8);
    }

    /** The product mark on white: a blue ring and a violet ring, linked; with room around it for maskable icons. */
    static byte[] icon(int size) {
        return ICONS.computeIfAbsent(size, s -> {
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, s, s);
            double r = s * 0.17, cy = s / 2.0, stroke = s * 0.045;
            g.setStroke(new BasicStroke((float) stroke));
            g.setColor(new Color(0x004F80));
            g.draw(new Ellipse2D.Double(s * 0.39 - r, cy - r, 2 * r, 2 * r));
            g.setColor(new Color(0x6A3FBF));
            g.draw(new Ellipse2D.Double(s * 0.61 - r, cy - r, 2 * r, 2 * r));
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try { ImageIO.write(img, "png", out); } catch (IOException e) { throw new UncheckedIOException(e); }
            return out.toByteArray();
        });
    }

    /** The service worker: page loads go to the network; only when there is none, a short notice instead. */
    static byte[] serviceWorker() {
        return ("""
            // One CommandCenter (G69): nothing is cached; a page load without a connection gets this notice instead.
            const OFFLINE = '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
              + '<title>One CommandCenter</title></head><body style="font-family: system-ui, sans-serif; margin: 32px 20px; color: #17202b">'
              + '<h1 style="font-size: 20px">No connection to One CommandCenter</h1>'
              + '<p>Check the phone is online (and on the company network or VPN if One CommandCenter needs it), then open the app again.</p></body></html>';
            self.addEventListener("install", () => self.skipWaiting());
            self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
            self.addEventListener("fetch", (e) => {
              if (e.request.mode !== "navigate") return;   // API calls and files go to the network as always
              e.respondWith(fetch(e.request).catch(() => new Response(OFFLINE, { headers: { "Content-Type": "text/html; charset=utf-8" } })));
            });
            """).getBytes(StandardCharsets.UTF_8);
    }
}
