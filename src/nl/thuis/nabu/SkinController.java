// SPDX-License-Identifier: Apache-2.0
// Based on Hovi's SkinController (Ponderosa, Apache-2.0). Changed for Nabu: the robot is drawn in code by
// RobotDrawer, the holidays, Robot Cat and art pack are gone, there is a Light colour setting, the robot's still
// parts are painted ahead of time and kept, and the robot slows to 30 fps or less while it rests.
package nl.thuis.nabu;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Draws the Voice Satellite turn while this plugin's skin is picked. Follows the voice skin contract: draw only
 * while selected, follow voice.overlay for when to show, and honour the Appearance settings (Background, Text size,
 * Reactive activity bar).
 */
final class SkinController {
    static final String KEY = Brand.KEY;
    /** Where the docked bubble sits above the bottom edge, in dp. */
    static final int DOCK_INSET = 24;
    /** The robot's size in the docked bubble, in dp. */
    static final int DOCK_SIZE = 88;
    static final String[] EVENTS = {"voice.skin", "voice.overlay", "voice.level", "voice.text", "voice.appearance"};

    private PluginHost host;
    private int generation;
    private boolean selected;
    private boolean skinHeard;
    private boolean appearanceHeard;
    private String overlay = "hidden";
    private String drawn;

    // Read by the view on the main thread.
    volatile float level;
    volatile String state = "idle";
    volatile boolean muted;
    volatile String command = "";
    volatile String answer = "";
    volatile String tool = "";
    /** Every action in the turn, a bulleted line each. */
    volatile String tools = "";
    /**
     * The width Kiosk Satellite's result panel takes at the right of a wide screen, as a share of it, or 0 for no
     * panel. Guessed from the tools called: plugins aren't told when it shows (SDK 1).
     */
    volatile float panelShare;
    /** The Name setting, over the turn on a full screen; empty for none. */
    volatile String robotName = Brand.NAME;
    /** The Answer font setting: true for Atkinson Hyperlegible, false for Kiosk Satellite's own. */
    volatile boolean hyperlegible;
    /** The Accent font setting: "Monospace" or "Same as the answer". */
    volatile String accentFont = "Monospace";
    volatile Fonts fonts;
    /** Null for Skin default, which is solid black. */
    volatile Float background;
    volatile float textScale = 1;
    volatile boolean reactive = true;
    /** Milliseconds between frames from the Frame rate setting: 0 for every frame, -1 for Auto. */
    volatile int fixedDelay = -1;
    /** What Auto settled on for this device. It only ever slows down, and lasts until the plugin restarts. */
    volatile int autoDelay;
    /** The Darkness setting, 0 to 1. */
    volatile float darkness;
    /** The Light colour setting. */
    volatile int lightColor = Brand.light(null);
    /** The Arms setting: true for both arms moving as one. */
    volatile boolean armsTogether = true;
    /** The showing view. Touched on the main thread only. */
    private SkinView view;

    synchronized void start(PluginHost host) {
        this.host = host;
        int current = ++generation;
        fonts = new Fonts(host);
        try {
            for (String event : EVENTS) host.subscribe(event);
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            host.status("This Kiosk Satellite version has no voice skins.", true);
            return;
        }
        try {
            host.subscribe("voice.state");
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            // Without it the face guesses the phase from the words.
        }
        try {
            host.subscribe("wakeword.state");
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            // Without it the robot never shows the muted face.
        }
        read(current);
    }

    private void read(int current) {
        // Events win over these reads: they are newer.
        host.executeCommand("getVoiceState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current != generation || !ok || !(data instanceof Map)) return;
                Map<?, ?> voice = (Map<?, ?>) data;
                if (!skinHeard) selected = Boolean.TRUE.equals(voice.get("skinSelected"));
                if ("hidden".equals(overlay) && voice.get("overlay") instanceof String) overlay = (String) voice.get("overlay");
                if (voice.get("state") instanceof String) state = (String) voice.get("state");
                update();
            }
        });
        host.executeCommand("getVoiceAppearance", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current == generation && ok && data instanceof Map && !appearanceHeard) appearance((Map<?, ?>) data);
            }
        });
        host.executeCommand("getVoiceText", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current == generation && ok && data instanceof Map && command.isEmpty() && answer.isEmpty()) words((Map<?, ?>) data);
            }
        });
    }

    synchronized void configure(Map<String, Object> settings) {
        Object rate = settings.get("frameRate");
        fixedDelay = "Full".equals(rate) ? 0 : "30 fps".equals(rate) ? 33 : "20 fps".equals(rate) ? 50 : -1;
        Object dark = settings.get("darkness");
        darkness = dark instanceof Number ? Math.max(0, Math.min(1, ((Number) dark).floatValue() / 100)) : 0;
        lightColor = Brand.light(settings.get("lightColor"));
        armsTogether = !"Each its own".equals(settings.get("arms"));
        robotName = settings.get("name") instanceof String ? ((String) settings.get("name")).trim() : Brand.NAME;
        hyperlegible = "Atkinson Hyperlegible".equals(settings.get("answerFont"));
        accentFont = settings.get("accentFont") instanceof String ? (String) settings.get("accentFont") : "Monospace";
        if (host != null) prepare();
        restyleLater();
    }

    /**
     * Paint the robot's still parts for the sizes it will be shown at, off the plugin's thread, so the first wake
     * after a start or a settings change doesn't wait for them. The sizes follow SkinView's layout.
     */
    private void prepare() {
        DisplayMetrics screen = Resources.getSystem().getDisplayMetrics();
        int w = screen.widthPixels, h = screen.heightPixels;
        RobotDrawer.Shell.prepare(RobotDrawer.scaleFor(fullSize(w, h), fullSize(w, h)), darkness);
        int docked = Math.round(DOCK_SIZE * screen.density);
        RobotDrawer.Shell.prepare(RobotDrawer.scaleFor(docked, docked), darkness);
    }

    /** The full screen robot's square size in pixels: beside the words on a wide screen, above them on a tall one. */
    static int fullSize(int width, int height) {
        boolean wide = width > height * 1.2f;
        return wide ? Math.round(Math.min(height * 0.96f, width * 0.58f)) : Math.round(Math.min(width * 0.96f, height * 0.6f));
    }

    synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if ("ks.voice.skin".equals(event)) {
            selected = Boolean.TRUE.equals(payload.get("selected"));
            skinHeard = true;
        } else if ("ks.voice.overlay".equals(event)) {
            overlay = String.valueOf(payload.get("overlay"));
        } else if ("ks.voice.level".equals(event)) {
            Object value = payload.get("level");
            level = value instanceof Number ? ((Number) value).floatValue() : 0;
            return;
        } else if ("ks.voice.state".equals(event)) {
            Object value = payload.get("state");
            state = value instanceof String ? (String) value : "idle";
            return;
        } else if ("ks.voice.text".equals(event)) {
            words(payload);
            return;
        } else if ("ks.voice.appearance".equals(event)) {
            appearanceHeard = true;
            appearance(payload);
            return;
        } else if ("ks.wakeword.state".equals(event)) {
            muted = Boolean.TRUE.equals(payload.get("muted"));
            return;
        } else {
            return;
        }
        update();
    }

    /** Which part of the turn the face is in: listening, thinking, answering or resting. */
    String phase() {
        String now = state;
        if ("listening".equals(now)) return "listening";
        if ("processing".equals(now)) return "thinking";
        if ("responding".equals(now)) return "answering";
        // No voice.state, or the answer stays up after it is spoken: guess from the words.
        if (!command.isEmpty() && answer.isEmpty() && tool.isEmpty()) return "thinking";
        return "resting";
    }

    private void appearance(Map<?, ?> settings) {
        background = settings.get("background") instanceof Number ? ((Number) settings.get("background")).floatValue() : null;
        textScale = settings.get("textScale") instanceof Number ? ((Number) settings.get("textScale")).floatValue() : 1;
        reactive = !Boolean.FALSE.equals(settings.get("reactive"));
        restyleLater();
    }

    private void words(Map<?, ?> text) {
        command = text.get("command") instanceof String ? (String) text.get("command") : "";
        answer = text.get("answer") instanceof String ? (String) text.get("answer") : "";
        Object tools = text.get("tools");
        tool = tools instanceof List && !((List<?>) tools).isEmpty() ? String.valueOf(((List<?>) tools).get(((List<?>) tools).size() - 1)) : "";
        StringBuilder lines = new StringBuilder();
        float share = 0;
        if (tools instanceof List) {
            // A bullet (U+2022) before each action, one to a line
            for (Object each : (List<?>) tools) {
                if (lines.length() > 0) lines.append((char) 10);
                lines.append((char) 0x2022).append(' ').append(each);
                share = Math.max(share, panelShare(String.valueOf(each)));
            }
        }
        this.tools = lines.toString();
        panelShare = share;
        SkinView current = view;
        if (current != null) current.post(current::refresh);
    }

    /**
     * The panel one action brings up, from its name as KS shows it ("Get weather forecast"). KS's own chat keeps
     * 32.5% of the width plus 40 dp clear for weather and quotes and 37.5% for images and videos.
     */
    private static float panelShare(String action) {
        String a = action.toLowerCase(java.util.Locale.ROOT);
        if (a.contains("image") || a.contains("video")) return 0.375f;
        if (a.contains("weather forecast") || a.contains("financial")) return 0.325f;
        return 0;
    }

    private void restyleLater() {
        SkinView current = view;
        // Refresh too: a new Name shows without waiting for the next turn
        if (current != null) current.post(() -> {
            current.restyle();
            current.refresh();
        });
    }

    /** Shows the skin while it is picked and the overlay is up, in the look that matches it. */
    private void update() {
        String want = selected && ("full".equals(overlay) || "docked".equals(overlay)) ? overlay : null;
        if (want == null ? drawn == null : want.equals(drawn)) return;
        drawn = want;
        if (want == null) {
            host.hideOverlay(KEY);
            level = 0;
        } else if ("full".equals(want)) {
            host.showOverlay(KEY, animated(OverlaySpec.fullScreen().onTop(true).touchable(false).closeOnBack(false), OverlaySpec.FADE, 300), new Skin(false));
        } else {
            // A bubble at the bottom. Touches reach the dashboard around and under it.
            host.showOverlay(KEY, animated(OverlaySpec.at(OverlaySpec.BOTTOM).inset(DOCK_INSET).onTop(true).touchable(false).closeOnBack(false), OverlaySpec.SLIDE, 250), new Skin(true));
        }
    }

    /** Transitions, or none on Kiosk Satellite versions without them. */
    private static OverlaySpec animated(OverlaySpec spec, String transition, int ms) {
        try {
            return spec.enter(transition).exit(transition).transitionDuration(ms);
        } catch (LinkageError olderHost) {
            return spec;
        }
    }

    synchronized void stop() {
        host = null;
        generation++;
        selected = false;
        skinHeard = false;
        appearanceHeard = false;
        overlay = "hidden";
        drawn = null;
        level = 0;
        state = "idle";
        muted = false;
        command = answer = tool = tools = "";
        panelShare = 0;
        background = null;
        textScale = 1;
        reactive = true;
        autoDelay = 0;
        RobotDrawer.Shell.clear();
    }

    /** Auto frame rate: one step slower when the frames that come back are well behind what was asked for. */
    private synchronized void slowDown(int from, float averageMs) {
        if (autoDelay != from || from >= 50) return;
        autoDelay = from == 0 ? 33 : 50;
        if (host != null) host.log(String.format(java.util.Locale.ROOT, "Frames every %.0f ms; Auto frame rate drops to %d fps.", averageMs, 1000 / autoDelay));
    }

    private final class Skin implements OverlayFactory {
        private final boolean docked;

        Skin(boolean docked) { this.docked = docked; }

        @Override public View create(Context context, KsTheme theme) {
            SkinView skin = new SkinView(context, theme, docked);
            view = skin;
            return skin;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((SkinView) view).restyle(theme);
        }

        @Override public void onDestroy(View destroyed) {
            if (view == destroyed) view = null;
        }
    }

    /** Full screen: the robot on black and the conversation beside or under it. Docked: a card with a small robot beside the words. */
    private final class SkinView extends FrameLayout {
        private final boolean docked;
        private final RobotView robot;
        private final TextView said;
        private final TextView answered;
        /** Full screen only: the assistant's name over the turn, and the actions under the answer. */
        private final TextView name;
        private final TextView actions;
        private final LinearLayout card;
        /** Full screen on a wide screen only: the robot and words side by side, which make room for a result panel. */
        private LinearLayout row;
        private int robotSize;
        private int screenWidth;
        private int screenHeight;
        private KsTheme ksTheme;

        SkinView(Context context, KsTheme theme, boolean docked) {
            super(context);
            this.ksTheme = theme;
            this.docked = docked;
            robot = new RobotView(context);
            said = new TextView(context);
            answered = new TextView(context);
            name = new TextView(context);
            actions = new TextView(context);
            setClipChildren(false);
            setClipToPadding(false);
            screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            if (docked) {
                card = new LinearLayout(context);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                int faceSize = theme.px(DOCK_SIZE);
                card.addView(robot, new LinearLayout.LayoutParams(faceSize, faceSize));
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                answered.setMaxLines(3);
                answered.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(said);
                words.addView(answered);
                LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                wordsParams.leftMargin = theme.px(12);
                card.addView(words, wordsParams);
                int width = Math.min(theme.px(560), screenWidth - theme.px(48));
                addView(card, new FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT));
            } else {
                card = null;
                int side = theme.px(48);
                // Wide screens put the words beside the robot so it can take the full height; tall ones stack them.
                boolean wide = screenWidth > screenHeight * 1.2f;
                int size = fullSize(screenWidth, screenHeight);
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                words.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
                for (TextView line : new TextView[] {name, said, answered, actions}) {
                    line.setGravity(wide ? Gravity.START : Gravity.CENTER);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    params.topMargin = theme.px(line == name ? 0 : line == said ? 6 : line == answered ? 8 : 10);
                    words.addView(line, params);
                }
                LinearLayout layout = new LinearLayout(context);
                layout.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                layout.setGravity(Gravity.CENTER);
                layout.addView(robot, new LinearLayout.LayoutParams(size, size));
                if (wide) {
                    LinearLayout.LayoutParams beside = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                    beside.leftMargin = theme.px(16);
                    layout.addView(words, beside);
                    layout.setPadding(0, 0, side, 0);
                    row = layout;
                    robotSize = size;
                } else {
                    LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    below.topMargin = theme.px(8);
                    layout.addView(words, below);
                    layout.setPadding(side, 0, side, 0);
                }
                addView(layout, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            restyle();
            refresh();
        }

        void restyle(KsTheme next) {
            ksTheme = next;
            restyle();
        }

        void restyle() {
            // The glowing face and lights need the dark, whatever the theme.
            float scale = textScale;
            int light = lightColor;
            if (docked) {
                GradientDrawable bubble = new GradientDrawable();
                bubble.setColor(0xFF000000);
                bubble.setCornerRadius(ksTheme.px(ksTheme.radiusCard()));
                bubble.setStroke(Math.max(1, ksTheme.px(1) / 2), (light & 0x00FFFFFF) | 0x66000000);
                card.setBackground(bubble);
                int pad = ksTheme.px(10);
                card.setPadding(pad, pad, ksTheme.px(20), pad);
                ksTheme.styleText(said, 14 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(answered, 17 * scale, 500, "onSurface");
            } else {
                Float chosen = background;
                float opacity = chosen != null ? chosen : 1f;
                setBackgroundColor(Color.argb(Math.round(255 * opacity), 0, 0, 0));
                ksTheme.styleText(name, 15 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(said, 20 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(answered, 28 * scale, 500, "onSurface");
                ksTheme.styleText(actions, 16 * scale, 400, "onSurfaceVariant");
            }
            // The answer in one readable font; the short lines around it in the light colour, monospace or the same font
            Fonts loaded = fonts;
            Typeface answerFace = hyperlegible && loaded != null ? loaded.get(Fonts.HYPERLEGIBLE, 0) : null;
            if (answerFace != null) answered.setTypeface(answerFace);
            boolean mono = !"Same as the answer".equals(accentFont);
            Typeface accentFace = mono ? (loaded != null ? loaded.get(Fonts.MONO, 400) : null) : answered.getTypeface();
            int color = readable(light);
            for (TextView line : docked ? new TextView[] {said} : new TextView[] {name, said, actions}) {
                if (accentFace != null) line.setTypeface(accentFace);
                line.setTextColor(color);
            }
            name.setAllCaps(mono);
            name.setLetterSpacing(mono ? 0.14f : 0.04f);
            name.setAlpha(0.85f);
            answered.setTextColor(0xFFF2F5F8);
            answered.setShadowLayer(ksTheme.px(7), 0, 0, (color & 0x00FFFFFF) | 0x59000000);
            robot.invalidate();
        }

        /** A light colour as text: lifted towards white until it reads on black. */
        private int readable(int color) {
            float r = Color.red(color), g = Color.green(color), b = Color.blue(color);
            for (int i = 0; i < 20 && luminance(r, g, b) < 0.33f; i++) {
                r += (255 - r) * 0.1f;
                g += (255 - g) * 0.1f;
                b += (255 - b) * 0.1f;
            }
            return Color.rgb(Math.round(r), Math.round(g), Math.round(b));
        }

        private float luminance(float r, float g, float b) {
            return 0.2126f * linear(r) + 0.7152f * linear(g) + 0.0722f * linear(b);
        }

        private float linear(float channel) {
            float c = channel / 255;
            return c <= 0.03928f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }

        void refresh() {
            said.setText(command.isEmpty() ? "" : "> " + command);
            said.setVisibility(command.isEmpty() ? GONE : VISIBLE);
            String called = robotName;
            name.setText(called);
            name.setVisibility(docked || called.isEmpty() || (command.isEmpty() && answer.isEmpty() && tools.isEmpty()) ? GONE : VISIBLE);
            if (docked) {
                // No room for a list in the bubble: the latest action stands in until the answer comes
                answered.setText(answer.isEmpty() ? tool : answer);
                answered.setVisibility(answer.isEmpty() && tool.isEmpty() ? GONE : VISIBLE);
            } else {
                answered.setText(answer);
                answered.setVisibility(answer.isEmpty() ? GONE : VISIBLE);
                actions.setText(tools);
                actions.setVisibility(tools.isEmpty() ? GONE : VISIBLE);
                makeRoomForPanel();
            }
        }

        /**
         * On a wide screen KS puts a result panel at the right, over the words beside the robot. While one shows, the
         * words end where it starts and the robot shrinks to leave them half of what's left.
         */
        private void makeRoomForPanel() {
            if (row == null) return;
            float share = panelShare;
            int right = share > 0 ? Math.round(screenWidth * share) + ksTheme.px(40) : ksTheme.px(48);
            int size = share > 0 ? Math.round(Math.min(robotSize, (screenWidth - right) * 0.5f)) : robotSize;
            if (row.getPaddingRight() != right) row.setPadding(0, 0, right, 0);
            ViewGroup.LayoutParams params = robot.getLayoutParams();
            if (params != null && params.width != size) {
                params.width = params.height = size;
                robot.setLayoutParams(params);
            }
        }

        /**
         * The robot, redrawn at a set rate, or as fast as Auto finds the device keeps up. While it rests it never
         * goes faster than 30 fps: a slow bob and a blink don't need more.
         */
        private final class RobotView extends View {
            private RobotDrawer drawer;
            private boolean attached;
            // Auto frame rate: gaps between frames, measured after the first few at each rate
            private long lastFrame;
            private int watchedDelay = -1;
            private int seen;
            private int frames;
            private long gaps;

            RobotView(Context context) {
                super(context);
            }

            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                attached = true;
            }

            @Override protected void onDetachedFromWindow() {
                attached = false;
                super.onDetachedFromWindow();
            }

            @Override protected void onDraw(Canvas canvas) {
                if (drawer == null) drawer = new RobotDrawer(lightColor);
                drawer.setLight(lightColor);
                drawer.setArmsTogether(armsTogether);
                String phase = phase();
                // With Reactive activity bar off nothing follows the voice. KS sends 0 then too.
                drawer.draw(canvas, getWidth(), getHeight(), reactive ? level : 0, phase, muted, darkness, SystemClock.uptimeMillis());
                if (!attached) return;
                int delay = fixedDelay >= 0 ? fixedDelay : autoDelay;
                // Auto only judges the device on the frames it asked to come fast
                if ("resting".equals(phase)) delay = Math.max(delay, 33);
                else if (fixedDelay < 0) watch(delay);
                if (delay > 0) postInvalidateDelayed(delay);
                else postInvalidateOnAnimation();
            }

            /**
             * Every frame should come about 17 ms apart, and 33 ms asks for 33 to 50 once it waits for the screen.
             * Averaging well past that over 60 frames means the device can't keep up, so ask for fewer.
             */
            private void watch(int delay) {
                long now = SystemClock.uptimeMillis();
                long gap = now - lastFrame;
                lastFrame = now;
                if (delay != watchedDelay) {
                    watchedDelay = delay;
                    seen = frames = 0;
                    gaps = 0;
                }
                // Skip the fade in and any frame after a pause
                if (++seen <= 15 || gap > 500) return;
                gaps += gap;
                if (++frames < 60) return;
                float average = gaps / (float) frames;
                frames = 0;
                gaps = 0;
                if (average > (delay == 0 ? 22 : 70)) slowDown(delay, average);
            }
        }
    }
}
