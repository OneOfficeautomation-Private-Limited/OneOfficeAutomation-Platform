package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;

/**
 * Targeting by sight (gap G57, as UiPath's computer vision and AI targeting): find a control on the screen from a plain
 * description ("the blue Submit button under the address", "the Total field") with a vision model, and click it. For
 * screens that have no usable selectors, such as remote desktops, Citrix and canvas-drawn applications.
 * <p>
 * The screenshot is made smaller (at most 1600 pixels wide) before it goes to the model, and personal-data masking does
 * not apply to pictures, so use a model connection the company allows for screen content. The model answers where the
 * control is as fractions of the picture; the answer is checked (inside the picture, a confidence at least
 * min_confidence) before anything is clicked.
 */
@OoaPackage(id = "ooa.ai_screen", name = "AI screen (vision)", version = "1.0.0",
        description = "Find and click controls on the screen from a plain description, with a vision model.")
public final class AiScreenPackage {
    /** The screen and the mouse; tests replace them. */
    interface Screen {
        BufferedImage capture();
        void click(int x, int y, boolean doubleClick, boolean right);
        default void type(ActionContext ctx, String text) { new DesktopPackage().typeText(ctx, text, "paste", 20, null, false, true, 0); }
    }
    static Screen screen = new Screen() {
        public BufferedImage capture() {
            if (GraphicsEnvironment.isHeadless()) throw new ActionException("SYSTEM.NO_DESKTOP", "There is no screen here", "Run the agent on a runner with a signed-in desktop.");
            return DesktopSupport.robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        }
        public void click(int x, int y, boolean dbl, boolean right) {
            Robot r = DesktopSupport.robot();
            r.mouseMove(x, y);
            int b = right ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
            for (int i = 0; i < (dbl ? 2 : 1); i++) { r.mousePress(b); r.mouseRelease(b); }
        }
    };

    static final String SYSTEM = "You locate user-interface controls in screenshots. Answer with one JSON object only: "
            + "{\"found\": true|false, \"left\": number, \"top\": number, \"width\": number, \"height\": number, \"confidence\": number, \"label\": \"what you found\", "
            + "\"text\": \"the text shown in or on the control, exactly as on screen\"}. "
            + "left, top, width and height are fractions of the picture's width and height (0 to 1); confidence is 0 to 1. "
            + "If the control is not visible, or several match and the description does not say which, answer found false.";

    /** The screenshot as a PNG of at most 1600 pixels wide, and the factor back to screen pixels. */
    static Object[] picture(BufferedImage full) throws IOException {
        double scale = Math.min(1.0, 1600.0 / full.getWidth());
        BufferedImage img = full;
        if (scale < 1.0) {
            img = new BufferedImage((int) Math.round(full.getWidth() * scale), (int) Math.round(full.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(full, 0, 0, img.getWidth(), img.getHeight(), null);
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new Object[]{Base64.getEncoder().encodeToString(out.toByteArray()), full.getWidth(), full.getHeight()};
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> locate(ActionContext ctx, String description, LlmClient.Config cfg, double minConfidence) {
        if (description == null || description.isBlank()) throw new ActionException("VISION.NO_DESCRIPTION", "Describe the control to find");
        Object[] pic;
        try { pic = picture(screen.capture()); } catch (IOException e) { throw new ActionException("VISION.SCREEN", "Cannot take the screenshot: " + e.getMessage()); }
        int w = (int) pic[1], h = (int) pic[2];
        List<Object> content = List.of(Map.of("type", "text", "text", "Find: " + description.strip()), Map.of("type", "image", "media_type", "image/png", "data", pic[0]));
        LlmClient.Reply r = LlmClient.chat(ctx, cfg, List.of(Map.of("role", "system", "content", SYSTEM), Map.of("role", "user", "content", content)), null, true, "ai_screen");
        Object parsed;
        try { parsed = r.text() == null ? null : LlmClient.parseJson(r.text()); } catch (ActionException e) { parsed = null; }
        if (!(parsed instanceof Map<?, ?> m)) throw new ActionException("VISION.BAD_ANSWER", "The model did not answer with a position", "Try a model that reads pictures, or describe the control differently.");
        Map<String, Object> a = (Map<String, Object>) m;
        double conf = num(a.get("confidence"), 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", String.valueOf(a.getOrDefault("label", "")));
        out.put("text", a.get("text") == null ? "" : String.valueOf(a.get("text")));
        out.put("confidence", BigDecimal.valueOf(conf));
        double left = num(a.get("left"), -1), top = num(a.get("top"), -1), width = num(a.get("width"), 0), height = num(a.get("height"), 0);
        boolean inside = left >= 0 && top >= 0 && width > 0 && height > 0 && left + width <= 1.0001 && top + height <= 1.0001;
        boolean found = Boolean.TRUE.equals(a.get("found")) && inside && conf >= minConfidence;
        out.put("found", found);
        if (Boolean.TRUE.equals(a.get("found")) && inside) {
            out.put("x", (int) Math.round((left + width / 2) * w));
            out.put("y", (int) Math.round((top + height / 2) * h));
            out.put("box", Map.of("left", (int) Math.round(left * w), "top", (int) Math.round(top * h), "width", (int) Math.round(width * w), "height", (int) Math.round(height * h)));
        } else { out.put("x", -1); out.put("y", -1); out.put("box", Map.of()); }
        return out;
    }

    private static double num(Object v, double d) { return v instanceof Number n ? n.doubleValue() : d; }

    private static LlmClient.Config cfg(ActionContext ctx, String connection, String provider, String model, Object key, String base) {
        return ModelConnections.config(ctx, connection, provider, model, key, base, 0, 300, false, null, null);
    }

    @Action(id = "find_on_screen", name = "Find on screen (AI)", category = "AI screen", timeoutSeconds = 180, capabilities = {"desktop", "network", "ai"},
            description = "Find a control on the screen from a plain description, with a vision model; returns where it is.",
            panel = "description, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url) | min_confidence")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when it was found with at least min_confidence.")
    @Output(name = "x", type = OoaType.NUMBER, description = "The middle of the control, in screen pixels from the left (-1 when not found).")
    @Output(name = "y", type = OoaType.NUMBER, description = "The middle of the control, in screen pixels from the top.")
    @Output(name = "box", type = OoaType.DICTIONARY, description = "left, top, width and height in screen pixels.")
    @Output(name = "confidence", type = OoaType.NUMBER, description = "How sure the model is, 0 to 1.")
    @Output(name = "label", type = OoaType.STRING, description = "What the model says it found.")
    @Output(name = "text", type = OoaType.STRING, description = "The text the model reads in or on the control.")
    public Map<String, Object> find(ActionContext ctx,
            @Input(name = "description", type = OoaType.STRING, example = "the Submit button below the address fields", description = "The control in plain words: its text, kind, colour or place.") String description,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "aws_bedrock", "google_vertex"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, required = false, description = "A model that reads pictures.") String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "0.6", min = 0, max = 1, description = "Below this, it counts as not found.") double min) {
        return locate(ctx, description, cfg(ctx, connection, provider, model, key, base), min);
    }

    @Action(id = "click_described", name = "Click described control (AI)", category = "AI screen", timeoutSeconds = 180, capabilities = {"desktop", "network", "ai"},
            description = "Find a control on the screen from a plain description with a vision model, and click it; fails when it is not found.",
            panel = "description, click, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url) | min_confidence")
    @Output(name = "x", type = OoaType.NUMBER, description = "Where it clicked, from the left.")
    @Output(name = "y", type = OoaType.NUMBER, description = "Where it clicked, from the top.")
    @Output(name = "label", type = OoaType.STRING, description = "What the model says it clicked.")
    public Map<String, Object> click(ActionContext ctx,
            @Input(name = "description", type = OoaType.STRING, example = "the Submit button below the address fields", description = "The control in plain words: its text, kind, colour or place.") String description,
            @Input(name = "click", type = OoaType.STRING, required = false, defaultValue = "\"single\"", enumValues = {"single", "double", "right"}, description = "Which click.") String click,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "aws_bedrock", "google_vertex"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, required = false, description = "A model that reads pictures.") String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "0.7", min = 0, max = 1, description = "Below this, the step fails instead of clicking.") double min) {
        Map<String, Object> at = locate(ctx, description, cfg(ctx, connection, provider, model, key, base), min);
        if (!Boolean.TRUE.equals(at.get("found")))
            throw new ActionException("VISION.NOT_FOUND", "Could not find on the screen: " + description + " (confidence " + at.get("confidence") + ")",
                    "Describe it more exactly (its text, its place), make sure it is visible, or lower min_confidence.", true);
        screen.click((int) at.get("x"), (int) at.get("y"), "double".equals(click), "right".equals(click));
        ctx.log("info", "Clicked " + at.get("label") + " at " + at.get("x") + "," + at.get("y"));
        return Map.of("x", at.get("x"), "y", at.get("y"), "label", at.get("label"));
    }

    @Action(id = "type_described", name = "Type into described control (AI)", category = "AI screen", timeoutSeconds = 180, capabilities = {"desktop", "network", "ai"},
            description = "Find a field on the screen from a plain description with a vision model, click into it and type text (pasted, so any language works).",
            panel = "description, text, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url) | min_confidence")
    @Output(name = "label", type = OoaType.STRING, description = "What the model says it typed into.")
    public Map<String, Object> type(ActionContext ctx,
            @Input(name = "description", type = OoaType.STRING, example = "the Invoice number field", description = "The field in plain words.") String description,
            @Input(name = "text", type = OoaType.STRING, description = "What to type.") String text,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "aws_bedrock", "google_vertex"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, required = false, description = "A model that reads pictures.") String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "0.7", min = 0, max = 1, description = "Below this, the step fails instead of typing.") double min) {
        Map<String, Object> at = click(ctx, description, "single", connection, provider, model, key, base, min);
        screen.type(ctx, text == null ? "" : text);
        return Map.of("label", at.get("label"));
    }

    @Action(id = "read_described", name = "Read described control (AI)", category = "AI screen", timeoutSeconds = 180, capabilities = {"desktop", "network", "ai"},
            description = "Read the text of a control on the screen, found from a plain description with a vision model (for screens without selectors or OCR zones).",
            panel = "description, (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url) | min_confidence")
    @Output(name = "text", type = OoaType.STRING, description = "The text read; empty when not found.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when it was found with at least min_confidence.")
    public Map<String, Object> read(ActionContext ctx,
            @Input(name = "description", type = OoaType.STRING, example = "the Total amount at the bottom right", description = "The control in plain words.") String description,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "aws_bedrock", "google_vertex"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "model", type = OoaType.STRING, required = false, description = "A model that reads pictures.") String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "min_confidence", type = OoaType.NUMBER, required = false, defaultValue = "0.6", min = 0, max = 1, description = "Below this, it counts as not found.") double min) {
        Map<String, Object> at = locate(ctx, description, cfg(ctx, connection, provider, model, key, base), min);
        boolean found = Boolean.TRUE.equals(at.get("found"));
        return Map.of("text", found ? at.get("text") : "", "found", found);
    }
}
