// SPDX-License-Identifier: Apache-2.0
package nl.thuis.nabu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.Random;

/**
 * Draws Nabu, a small hovering robot, entirely in code: no pictures are shipped.
 *
 * <p>Cheap by design. Everything that never moves (shell, ears, face screen) is painted once into one bitmap at
 * the exact pixel size it is shown at, and each arm into a small one of its own, all kept in {@link Shell} so a
 * new overlay reuses them. Each frame then draws the body once, turns each arm about its shoulder, adds a few soft
 * glows from one small sprite, and draws the face (eyes, mouth) and lights as a handful of simple shapes.
 *
 * <p>All drawing is in a design space of 300 by 370 units; the robot's own coordinates run from x 20 and y 26.
 */
final class RobotDrawer {
    /** The design space: width and height, and where the robot's coordinates start inside it. */
    static final float DW = 300, DH = 370, OX = 20, OY = 26;
    /** The mouth opens by 1.5 times the voice level: real speech rarely reaches full level. */
    private static final float GAIN = 1.5f;
    static final int MUTED = 0xFFE5484D;

    private final Random random = new Random();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int light;
    private int lightTint;
    private int lightDim;
    private Bitmap glow;
    private Bitmap redGlow;

    // The pose, eased towards its targets each frame
    private float last = -1;
    private float eyeR = 14, gazeX, gazeY, halo = 0.25f, ear = 0.55f, chest = 0.3f, mouth;
    private float gazeTX, gazeTY, nextGaze = 2;
    /** How far each arm is lifted outward from hanging, in degrees: the robot's left (screen left) and right. */
    private float armL, armR;
    /** The Arms setting: true moves both arms as one, mirrored. */
    private boolean armsTogether = true;
    private float blinkStart = -10, nextBlink = 3;

    RobotDrawer(int lightColor) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setLight(lightColor);
        redGlow = glowSprite(MUTED);
    }

    void setArmsTogether(boolean together) {
        armsTogether = together;
    }

    /** The colour of the eyes and lights. */
    void setLight(int color) {
        if (glow != null && color == light) return;
        light = color;
        lightTint = mix(color, Color.WHITE, 0.35f);
        lightDim = mix(color, Color.BLACK, 0.45f);
        glow = glowSprite(color);
    }

    /** A soft round glow in one colour: 64 pixels, drawn stretched wherever a light shines. */
    private static Bitmap glowSprite(int color) {
        Bitmap sprite = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(sprite);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int clear = color & 0x00FFFFFF;
        p.setShader(new RadialGradient(32, 32, 32,
                new int[] {(color & 0x00FFFFFF) | 0xC0000000, (color & 0x00FFFFFF) | 0x50000000, clear},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(32, 32, 32, p);
        return sprite;
    }

    /** The scale, in pixels per design unit, that fits the robot in a w by h area. */
    static float scaleFor(int w, int h) {
        return Math.min(w / DW, h / DH);
    }

    /**
     * One frame into a w by h area. phase is resting, listening, thinking or answering. level is the voice level,
     * 0 to 1.
     */
    void draw(Canvas c, int w, int h, float level, String phase, boolean muted, float darkness, long nowMs) {
        float s = scaleFor(w, h);
        if (s <= 0) return;
        Shell.Parts parts = Shell.get(s, darkness);
        float now = nowMs / 1000f;
        float dt = last < 0 ? 0 : Math.min(0.1f, now - last);
        last = now;
        boolean answering = "answering".equals(phase);
        boolean thinking = "thinking".equals(phase);
        boolean listening = "listening".equals(phase);
        ease(now, dt, level, listening, thinking, answering, muted);

        float left = (w - DW * s) / 2, top = (h - DH * s) / 2;
        // A slow hover: the robot rises and sinks, the ring under it stays put
        float bob = (float) Math.sin(now * 2 * Math.PI / 3.2f) * 6;

        c.save();
        c.translate(left, top);
        c.scale(s, s);
        c.translate(-OX, -OY);
        hoverRing(c, bob, muted);
        c.translate(0, bob);
        earGlows(c, muted);
        arms(c, parts);
        c.restore();

        // The still parts, at their own pixel size: no scaling, so this is one plain copy
        c.drawBitmap(parts.body, left, top + bob * s, bitmapPaint);

        c.save();
        c.translate(left, top + bob * s);
        c.scale(s, s);
        c.translate(-OX, -OY);
        earCores(c, muted);
        chestRing(c, now, thinking, muted);
        eyes(c, now, answering, muted);
        mouth(c, now, level, listening, thinking, answering, muted);
        c.restore();
    }

    private void ease(float now, float dt, float level, boolean listening, boolean thinking, boolean answering, boolean muted) {
        float targetEyeR = listening ? 16 : thinking ? 11 : 14;
        float tx, ty;
        if (muted) {
            tx = 0;
            ty = 4;
        } else if (thinking) {
            tx = 5;
            ty = -5;
        } else if (listening || answering) {
            tx = 0;
            ty = 0;
        } else {
            // Resting: glance somewhere new every few seconds
            if (now > nextGaze) {
                gazeTX = (random.nextFloat() * 2 - 1) * 5;
                gazeTY = (random.nextFloat() * 2 - 1) * 3;
                nextGaze = now + 2 + random.nextFloat() * 3;
            }
            tx = gazeTX;
            ty = gazeTY;
        }
        float targetHalo = listening ? 0.55f + 0.35f * level : answering ? 0.4f : 0.2f;
        float pulse = (float) (0.5 + 0.5 * Math.sin(now * 5));
        float targetEar = muted ? 0.7f : listening ? 0.7f + 0.15f * pulse + 0.25f * level : thinking ? 0.35f : answering ? 0.55f + 0.4f * level : 0.5f;
        float targetChest = answering ? 0.35f + 0.65f * level : listening ? 0.45f : 0.3f;
        float k = 1 - (float) Math.exp(-dt * 10);
        float fast = 1 - (float) Math.exp(-dt * 25);
        eyeR += (targetEyeR - eyeR) * k;
        gazeX += (tx - gazeX) * k;
        gazeY += (ty - gazeY) * k;
        halo += (targetHalo - halo) * k;
        ear += (Math.min(1, targetEar) - ear) * fast;
        chest += (Math.min(1, targetChest) - chest) * fast;
        mouth += (Math.min(1, level * GAIN) - mouth) * fast;
        // Arms: a slow sway at rest, lifted to listen, moving with the voice while it talks, one raised in thought,
        // drooping when muted
        float targetL, targetR;
        // With arms together both follow the left arm's wave; otherwise the right one runs a little behind
        float lag = armsTogether ? 0 : 1;
        if (muted) {
            targetL = targetR = -4;
        } else if (answering) {
            targetL = 8 + 22 * mouth * (0.6f + 0.4f * (float) Math.sin(now * 7));
            targetR = 8 + 22 * mouth * (0.6f + 0.4f * (float) Math.sin(now * 7 + 2 * lag));
        } else if (listening) {
            targetL = 14 + 2 * (float) Math.sin(now * 2);
            targetR = 14 + 2 * (float) Math.sin(now * 2 + 1.5f * lag);
        } else if (thinking) {
            if (armsTogether) {
                targetL = targetR = 10 + 3 * (float) Math.sin(now * 6);
            } else {
                targetL = 2;
                targetR = 22 + 3 * (float) Math.sin(now * 6);
            }
        } else {
            targetL = 3 * (float) Math.sin(now * 1.6f);
            targetR = 3 * (float) Math.sin(now * 1.6f + 2.2f * lag);
        }
        float arm = answering ? fast : 1 - (float) Math.exp(-dt * 6);
        armL += (targetL - armL) * arm;
        armR += (targetR - armR) * arm;
        if (now > nextBlink) {
            blinkStart = now;
            nextBlink = now + 2.5f + random.nextFloat() * 4;
        }
    }

    /** How open the eyes are, 0 to 1: a quick blink every few seconds. */
    private float openness(float now) {
        float t = (now - blinkStart) / 0.16f;
        if (t < 0 || t > 1) return 1;
        return 0.1f + 0.9f * Math.abs(1 - 2 * t);
    }

    private void hoverRing(Canvas c, float bob, boolean muted) {
        // Brighter as the robot sinks towards it
        float near = 0.75f - bob / 24;
        sprite(c, muted ? redGlow : glow, 170, 370, 84, 20, 0.55f * near);
        stroke.setStrokeWidth(3);
        stroke.setColor(withAlpha(muted ? lightDim : light, 0.85f));
        rect.set(124, 360, 216, 376);
        c.drawOval(rect, stroke);
    }

    /** Each arm turned about its shoulder; drawn before the body, so the shoulder joint covers where it turns. */
    private void arms(Canvas c, Shell.Parts parts) {
        c.save();
        c.rotate(armL, Shell.SHOULDER_L[0], Shell.SHOULDER_L[1]);
        rect.set(Shell.ARM_L[0], Shell.ARM_L[1], Shell.ARM_L[2], Shell.ARM_L[3]);
        c.drawBitmap(parts.armL, null, rect, bitmapPaint);
        c.restore();
        c.save();
        c.rotate(-armR, Shell.SHOULDER_R[0], Shell.SHOULDER_R[1]);
        rect.set(Shell.ARM_R[0], Shell.ARM_R[1], Shell.ARM_R[2], Shell.ARM_R[3]);
        c.drawBitmap(parts.armR, null, rect, bitmapPaint);
        c.restore();
    }

    private void earGlows(Canvas c, boolean muted) {
        Bitmap g = muted ? redGlow : glow;
        sprite(c, g, 72, 120, 34, 34, ear);
        sprite(c, g, 268, 120, 34, 34, ear);
    }

    private void earCores(Canvas c, boolean muted) {
        fill.setShader(null);
        fill.setColor(muted ? 0xFFFFB3B5 : lightTint);
        c.drawCircle(70, 120, 5, fill);
        c.drawCircle(270, 120, 5, fill);
    }

    private void chestRing(Canvas c, float now, boolean thinking, boolean muted) {
        int color = muted ? lightDim : lightTint;
        sprite(c, glow, 170, 275, 22, 22, muted ? 0.1f : chest);
        stroke.setStrokeWidth(3);
        stroke.setColor(color);
        rect.set(160, 265, 180, 285);
        if (thinking) {
            // A ring that chases round while it thinks
            c.drawArc(rect, (now * 360) % 360, 260, false, stroke);
        } else {
            c.drawOval(rect, stroke);
        }
    }

    private void eyes(Canvas c, float now, boolean answering, boolean muted) {
        float ey = 116 + gazeY;
        float[] xs = {140 + gazeX, 200 + gazeX};
        if (muted) {
            // Low, dim slits
            fill.setShader(null);
            fill.setColor(lightDim);
            for (float ex : xs) {
                rect.set(ex - 12, ey + 2, ex + 12, ey + 10);
                c.drawRoundRect(rect, 4, 4, fill);
            }
            return;
        }
        for (float ex : xs) sprite(c, glow, ex, ey, eyeR + 10, eyeR + 10, halo);
        if (answering) {
            // Happy arcs
            stroke.setStrokeWidth(7);
            stroke.setColor(lightTint);
            for (float ex : xs) {
                path.reset();
                path.moveTo(ex - 14, ey + 4);
                path.quadTo(ex, ey - 18, ex + 14, ey + 4);
                c.drawPath(path, stroke);
            }
            return;
        }
        float open = openness(now);
        float r = eyeR;
        fill.setShader(null);
        for (float ex : xs) {
            fill.setColor(light);
            rect.set(ex - r, ey - r * open, ex + r, ey + r * open);
            c.drawOval(rect, fill);
            if (open > 0.6f) {
                fill.setColor(0xFFE9FFF9);
                c.drawCircle(ex + r * 0.35f, ey - r * 0.35f * open, r * 0.28f, fill);
            }
        }
    }

    private void mouth(Canvas c, float now, float level, boolean listening, boolean thinking, boolean answering, boolean muted) {
        stroke.setStrokeWidth(3);
        fill.setShader(null);
        if (muted) {
            stroke.setColor(lightDim);
            c.drawLine(165, 148, 175, 148, stroke);
        } else if (thinking) {
            // Three dots, lit one after another
            for (int i = 0; i < 3; i++) {
                float a = 0.3f + 0.7f * Math.max(0, (float) Math.sin(now * 4 - i * 0.9f));
                fill.setColor(withAlpha(light, a));
                c.drawCircle(161 + i * 9, 146, 3, fill);
            }
        } else if (answering) {
            float open = Math.max(0.15f, mouth);
            sprite(c, glow, 170, 146, 18, 12, 0.3f + 0.5f * open);
            fill.setColor(lightTint);
            rect.set(170 - (8 + 3 * open), 146 - (2.5f + 6 * open), 170 + (8 + 3 * open), 146 + (2.5f + 6 * open));
            c.drawOval(rect, fill);
        } else {
            stroke.setColor(light);
            path.reset();
            if (listening) {
                path.moveTo(164, 146);
                path.quadTo(170, 151, 176, 146);
            } else {
                path.moveTo(162, 145);
                path.quadTo(170, 152, 178, 145);
            }
            c.drawPath(path, stroke);
        }
    }

    /** The glow sprite, centred at x, y with half sizes rx and ry, at a strength of 0 to 1. */
    private void sprite(Canvas c, Bitmap g, float x, float y, float rx, float ry, float strength) {
        if (strength <= 0.01f) return;
        glowPaint.setAlpha(Math.round(255 * Math.min(1, strength)));
        rect.set(x - rx, y - ry, x + rx, y + ry);
        c.drawBitmap(g, null, rect, glowPaint);
    }

    static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0, Math.min(1, alpha))) << 24);
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /**
     * The still parts of the robot, painted once per size and darkness and kept, so opening the overlay again costs
     * nothing. Two sizes are kept: the full screen robot and the docked one.
     */
    static final class Shell {
        /** Where each arm's picture sits in the design space when it hangs: left, top, right, bottom. */
        static final float[] ARM_L = {70, 224, 120, 310}, ARM_R = {220, 224, 270, 310};
        /** The point each arm turns about: the middle of its shoulder joint. */
        static final float[] SHOULDER_L = {111, 230}, SHOULDER_R = {229, 230};

        /** The body, and each arm on its own. */
        static final class Parts {
            final Bitmap body, armL, armR;

            Parts(Bitmap body, Bitmap armL, Bitmap armR) {
                this.body = body;
                this.armL = armL;
                this.armR = armR;
            }
        }

        private static float[] scales = new float[2];
        private static float[] darks = new float[2];
        private static Parts[] made = new Parts[2];
        private static int next;

        private Shell() {}

        static synchronized Parts get(float s, float darkness) {
            float key = Math.round(s * 1000) / 1000f;
            for (int i = 0; i < made.length; i++) {
                if (made[i] != null && scales[i] == key && darks[i] == darkness) return made[i];
            }
            Paint filter = new Paint(Paint.ANTI_ALIAS_FLAG);
            filter.setColorFilter(darken(darkness));
            Parts parts = new Parts(paint(key, darkness), arm(key, -1, filter), arm(key, 1, filter));
            int slot = next;
            next = (next + 1) % made.length;
            scales[slot] = key;
            darks[slot] = darkness;
            made[slot] = parts;
            return parts;
        }

        /** The Darkness setting as a filter: highlights stay bright while the rest drops; null at 0. */
        private static ColorMatrixColorFilter darken(float darkness) {
            if (darkness <= 0) return null;
            float k = 1 - 0.25f * darkness, off = -0.18f * darkness * 255;
            return new ColorMatrixColorFilter(new float[] {
                k, 0, 0, 0, off,
                0, k, 0, 0, off,
                0, 0, k, 0, off,
                0, 0, 0, 1, 0,
            });
        }

        /** One arm and its hand, hanging, in a small picture of its own: side -1 is the left, 1 the right. */
        private static Bitmap arm(float s, int side, Paint filter) {
            float[] box = side < 0 ? ARM_L : ARM_R;
            int w = Math.max(1, (int) Math.ceil((box[2] - box[0]) * s));
            int h = Math.max(1, (int) Math.ceil((box[3] - box[1]) * s));
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bitmap);
            c.scale(w / (box[2] - box[0]), h / (box[3] - box[1]));
            c.translate(-box[0], -box[1]);
            RectF r = new RectF();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColorFilter(filter.getColorFilter());
            float ax = 170 + side * 74;
            c.save();
            c.rotate(-side * 16, ax, 262);
            r.set(ax - 14, 232, ax + 14, 292);
            p.setShader(shellShader(ax, 262, 14, 30));
            c.drawOval(r, p);
            c.restore();
            Paint joint = new Paint(Paint.ANTI_ALIAS_FLAG);
            float hx = 170 + side * 83;
            r.set(hx - 8, 284, hx + 8, 304);
            joint.setShader(jointShader(hx, 294, 10));
            c.drawOval(r, joint);
            return bitmap;
        }

        /** Paint ahead of time, off the main thread, so the first wake doesn't pay for it. */
        static void prepare(float s, float darkness) {
            if (s <= 0) return;
            new Thread(() -> get(s, darkness), "nabu-shell").start();
        }

        static synchronized void clear() {
            for (int i = 0; i < made.length; i++) {
                made[i] = null;
                scales[i] = 0;
            }
        }

        private static Bitmap paint(float s, float darkness) {
            int w = Math.max(1, (int) Math.ceil(DW * s));
            int h = Math.max(1, (int) Math.ceil(DH * s));
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bitmap);
            c.scale(s, s);
            c.translate(-OX, -OY);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColorFilter(darken(darkness));
            Paint joint = new Paint(Paint.ANTI_ALIAS_FLAG);
            RectF r = new RectF();
            Path path = new Path();

            // Shoulder joints; the arms are drawn under them each frame, so they can move
            for (int side = -1; side <= 1; side += 2) {
                float jx = 170 + side * 59;
                joint.setShader(jointShader(jx, 230, 11));
                c.drawCircle(jx, 230, 11, joint);
            }
            // Neck
            joint.setShader(jointShader(170, 199, 20));
            r.set(150, 192, 190, 206);
            c.drawRoundRect(r, 4, 4, joint);
            // Body: an egg that tapers to the hover base
            path.reset();
            path.moveTo(118, 250);
            path.quadTo(118, 202, 170, 202);
            path.quadTo(222, 202, 222, 250);
            path.quadTo(222, 300, 170, 334);
            path.quadTo(118, 300, 118, 250);
            path.close();
            p.setShader(shellShader(170, 268, 52, 66));
            c.drawPath(path, p);
            joint.setShader(jointShader(170, 334, 18));
            r.set(152, 328, 188, 340);
            c.drawOval(r, joint);
            // Chest panel
            Paint flat = new Paint(Paint.ANTI_ALIAS_FLAG);
            flat.setColorFilter(p.getColorFilter());
            flat.setColor(0xFFDCE2E8);
            r.set(160, 220, 180, 244);
            c.drawRoundRect(r, 5, 5, flat);
            flat.setColor(0xFF9AA4AF);
            flat.setStrokeWidth(2);
            c.drawLine(165, 228, 175, 228, flat);
            c.drawLine(165, 235, 175, 235, flat);
            // Ear pods, behind the head
            for (int side = -1; side <= 1; side += 2) {
                float ex = 170 + side * 94;
                r.set(ex - 15, 88, ex + 15, 152);
                Paint ear = new Paint(Paint.ANTI_ALIAS_FLAG);
                ear.setColorFilter(p.getColorFilter());
                ear.setShader(new RadialGradient(ex - 4, 108, 40, new int[] {0xFFF4F7FA, 0xFFA9B4BF}, null, Shader.TileMode.CLAMP));
                c.drawOval(r, ear);
            }
            // Head
            r.set(75, 40, 265, 196);
            p.setShader(shellShader(170, 118, 95, 78));
            c.drawOval(r, p);
            Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
            shine.setStyle(Paint.Style.STROKE);
            shine.setStrokeCap(Paint.Cap.ROUND);
            shine.setStrokeWidth(6);
            shine.setColor(0xCCFFFFFF);
            path.reset();
            path.moveTo(110, 70);
            path.quadTo(150, 46, 200, 52);
            c.drawPath(path, shine);
            // Face screen, glossy black
            Paint screen = new Paint(Paint.ANTI_ALIAS_FLAG);
            screen.setShader(new LinearGradient(0, 78, 0, 162, 0xFF1C2633, 0xFF05070A, Shader.TileMode.CLAMP));
            r.set(100, 78, 240, 162);
            c.drawRoundRect(r, 36, 36, screen);
            shine.setStrokeWidth(4);
            shine.setColor(0x1FFFFFFF);
            path.reset();
            path.moveTo(114, 92);
            path.quadTo(150, 82, 196, 86);
            c.drawPath(path, shine);
            return bitmap;
        }

        /** White shell, lit from the top left. */
        private static Shader shellShader(float cx, float cy, float rx, float ry) {
            float radius = Math.max(rx, ry) * 1.6f;
            return new RadialGradient(cx - rx * 0.24f, cy - ry * 0.44f, radius,
                    new int[] {0xFFFFFFFF, 0xFFECF0F4, 0xFFB9C3CD}, new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        }

        /** Dark joints and the hover base. */
        private static Shader jointShader(float cx, float cy, float radius) {
            return new RadialGradient(cx - radius * 0.2f, cy - radius * 0.3f, radius * 1.5f,
                    new int[] {0xFF4A5462, 0xFF171B21}, null, Shader.TileMode.CLAMP);
        }
    }
}
