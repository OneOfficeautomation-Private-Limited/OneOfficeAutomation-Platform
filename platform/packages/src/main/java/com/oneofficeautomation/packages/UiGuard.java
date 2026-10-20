package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ui.DesktopEvents;
import com.oneofficeautomation.packages.ui.InterruptRules;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.ChildAgents;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects the run's interrupt rules to the real desktop and to open browser sessions. UI actions call
 * {@link #before(ActionContext)} before they touch the screen, and again when an element is not found.
 * Element toolkits register {@link ElementHooks} per session so rules can look for and click their elements.
 */
public final class UiGuard {
    private UiGuard() {}

    public static final String HOOKS_PREFIX = "ooa.ui_hooks.";
    private static final Pattern XY = Pattern.compile("^\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*$");

    /** What an element toolkit (such as the browser) lets interrupt rules do in one of its sessions. */
    public interface ElementHooks {
        boolean present(String selector);
        void click(String selector);
        void press(String keys);
    }

    public static void register(ActionContext ctx, String session, ElementHooks hooks) { ctx.sessions().put(HOOKS_PREFIX + session, hooks); }
    public static void unregister(ActionContext ctx, String session) { ctx.sessions().remove(HOOKS_PREFIX + session); }

    private static ElementHooks hooks(ActionContext ctx, String session) {
        return ctx.sessions().get(HOOKS_PREFIX + session) instanceof ElementHooks h ? h : null;
    }

    /** Checks the run's interrupt rules and responds to any that match; returns the names of the rules that responded. */
    public static List<String> before(ActionContext ctx) {
        InterruptRules rules = InterruptRules.existing(ctx);
        if (rules == null || rules.isEmpty()) return List.of();
        return rules.check(probe(ctx), responder(ctx), ctx::warn);
    }

    static InterruptRules.Probe probe(ActionContext ctx) {
        return new InterruptRules.Probe() {
            public List<String> windowTitles() { return DesktopEvents.installed().map(DesktopEvents::windowTitles).orElse(List.of()); }
            public Boolean elementPresent(String session, String selector) {
                ElementHooks h = hooks(ctx, session);
                return h == null ? null : h.present(selector);
            }
        };
    }

    static InterruptRules.Responder responder(ActionContext ctx) {
        return new InterruptRules.Responder() {
            public void click(InterruptRules.Rule r, String window) {
                Matcher m = XY.matcher(r.target());
                if (m.matches()) {
                    if (window != null) DesktopEvents.installed().ifPresent(d -> d.activateWindow(r.whenWindow()));
                    Robot robot = DesktopSupport.robot();
                    robot.mouseMove(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    return;
                }
                ElementHooks h = hooks(ctx, r.session());
                if (h == null) throw new ActionException("INTERRUPT.NO_SESSION", "No browser session '" + r.session() + "' is open to click " + r.target(),
                        "Use x,y screen coordinates for desktop windows, or open the browser session first.");
                h.click(r.target());
            }

            public void pressKeys(InterruptRules.Rule r, String window) {
                if (window == null) {
                    ElementHooks h = hooks(ctx, r.session());
                    if (h != null) { h.press(r.target()); return; }
                } else DesktopEvents.installed().ifPresent(d -> d.activateWindow(r.whenWindow()));
                List<Integer> codes = DesktopPackage.parseKeys(r.target());
                Robot robot = DesktopSupport.robot();
                for (int c : codes) robot.keyPress(c);
                for (int i = codes.size() - 1; i >= 0; i--) robot.keyRelease(codes.get(i));
            }

            public void closeWindow(InterruptRules.Rule r, String window) {
                if (window == null) throw new ActionException("INTERRUPT.BAD_RULE", "close_window answers window rules only");
                DesktopEvents d = DesktopEvents.installed().orElseThrow(() -> new ActionException("SYSTEM.WINDOWS_ONLY",
                        "Closing windows needs a Windows runner with the Window toolkit installed"));
                d.closeWindow(r.whenWindow());
            }

            public void runAgent(InterruptRules.Rule r) {
                if (!(ctx.sessions().get(ChildAgents.SESSION) instanceof ChildAgents agents))
                    throw new ActionException("INTERRUPT.NO_REPOSITORY", "This run cannot start child agents", "Run the agent from Agent Studio or the Command Center.");
                agents.run(r.target(), r.inputs());
            }
        };
    }
}
