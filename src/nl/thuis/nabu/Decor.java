// SPDX-License-Identifier: Apache-2.0
package nl.thuis.nabu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Calendar;

/**
 * Holiday decorations that stand still, in the robot's design space, painted once per holiday and size into up to
 * four layers and kept: what stands behind the robot, what sits behind its head (bunny ears), what it wears (hats,
 * lights), and what stands on the ground in front. Each layer is a band across the whole width. What moves
 * (fireworks, hearts, butterflies, twinkling bulbs, the lantern, candle flames) is drawn by {@link RobotDrawer}.
 */
final class Decor {
    static final int BACK = 0, EARS = 1, BODY = 2, GROUND = 3;
    /** The width the layers cover, and the top and bottom of each layer's band, in design units. */
    static final float XL = -40, XR = 380;
    static final float[] TOP = {-6, -6, -6, 270}, BOTTOM = {414, 74, 340, 414};

    /** A heart, about 16 units wide, its middle near y 5.7. */
    static final Path HEART = new Path();
    private static final Path BAT = new Path();

    static {
        HEART.moveTo(0, 4);
        HEART.cubicTo(0, -2, -8, -4, -8, 2);
        HEART.cubicTo(-8, 7, 0, 11, 0, 14);
        HEART.cubicTo(0, 11, 8, 7, 8, 2);
        HEART.cubicTo(8, -4, 0, -2, 0, 4);
        HEART.close();
        BAT.moveTo(0, 0);
        float[] q = {-5, -7, -14, -5, -10, -1, -13, 4, -7, 1, -5, 5, -2, 1, 0, 3, 2, 1, 5, 5, 7, 1, 13, 4, 10, -1, 14, -5, 5, -7, 0, 0};
        for (int i = 0; i < q.length; i += 4) BAT.quadTo(q[i], q[i + 1], q[i + 2], q[i + 3]);
        BAT.close();
    }

    private Decor() {}

    /** The layers a holiday has anything in, as bits 1 << layer. */
    static int layers(String h) {
        switch (h) {
            case Holidays.SINTERKLAAS: return 1 << GROUND;
            case Holidays.KERST: return 1 << BACK | 1 << BODY;
            case Holidays.OUDEJAAR: return 1 << GROUND;
            case Holidays.NIEUWJAAR: return 1 << BACK | 1 << GROUND;
            case Holidays.VALENTIJN: return 1 << GROUND;
            case Holidays.PASEN: return 1 << EARS | 1 << GROUND;
            case Holidays.KONINGSDAG: return 1 << BACK | 1 << BODY | 1 << GROUND;
            case Holidays.BEVRIJDING: return 1 << BACK;
            case Holidays.LENTE: return 1 << GROUND;
            case Holidays.HALLOWEEN: return 1 << BACK | 1 << BODY | 1 << GROUND;
            case Holidays.SINTMAARTEN: return 1 << BACK;
            case Holidays.VERJAARDAG: return 1 << BACK | 1 << BODY | 1 << GROUND;
            default: return 0;
        }
    }

    /** The painted layers for one holiday at two sizes (full screen and docked), made on first use. */
    static final class Cache {
        private static String holiday = "";
        private static final float[] scales = new float[2];
        private static final Bitmap[][] made = new Bitmap[2][];
        private static int next;

        private Cache() {}

        static synchronized Bitmap[] get(String h, float s) {
            float key = Math.round(s * 1000) / 1000f;
            if (!h.equals(holiday)) {
                holiday = h;
                made[0] = made[1] = null;
            }
            for (int i = 0; i < made.length; i++) if (made[i] != null && scales[i] == key) return made[i];
            Bitmap[] layers = new Bitmap[4];
            int used = layers(h);
            for (int l = 0; l < 4; l++) if ((used & 1 << l) != 0) layers[l] = paintLayer(h, l, key);
            int slot = next;
            next = (next + 1) % made.length;
            scales[slot] = key;
            made[slot] = layers;
            return layers;
        }

        /** Paint ahead of time, off the main thread, so the first wake of a holiday doesn't wait. */
        static void prepare(String h, float s) {
            if (h.isEmpty() || s <= 0) return;
            new Thread(() -> get(h, s), "nabu-decor").start();
        }

        static synchronized void clear() {
            holiday = "";
            made[0] = made[1] = null;
        }
    }

    private static Bitmap paintLayer(String h, int layer, float s) {
        int w = Math.max(1, (int) Math.ceil((XR - XL) * s));
        int hgt = Math.max(1, (int) Math.ceil((BOTTOM[layer] - TOP[layer]) * s));
        Bitmap bitmap = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.scale(s, s);
        c.translate(-XL, -TOP[layer]);
        paint(c, h, layer);
        return bitmap;
    }

    private static void paint(Canvas c, String h, int layer) {
        switch (h) {
            case Holidays.SINTERKLAAS: sinterklaas(c); break;
            case Holidays.KERST: if (layer == BACK) kerstBack(c); else kerstWires(c); break;
            case Holidays.OUDEJAAR: oudejaar(c); break;
            case Holidays.NIEUWJAAR: if (layer == BACK) nieuwjaarBack(c); else nieuwjaarSign(c); break;
            case Holidays.VALENTIJN: valentijn(c); break;
            case Holidays.PASEN: if (layer == EARS) bunnyEars(c); else eggs(c); break;
            case Holidays.KONINGSDAG: if (layer == BACK) bunting(c); else if (layer == BODY) crown(c); else tompouce(c); break;
            case Holidays.BEVRIJDING: bevrijding(c); break;
            case Holidays.LENTE: tulips(c); break;
            case Holidays.HALLOWEEN: if (layer == BACK) night(c); else if (layer == BODY) witchHat(c); else pumpkins(c); break;
            case Holidays.SINTMAARTEN: stars(c); break;
            case Holidays.VERJAARDAG: if (layer == BACK) balloons(c); else if (layer == BODY) partyHat(c); else cake(c); break;
            default: break;
        }
    }

    // Shapes

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    private static Paint line(int color, float width) {
        Paint p = fill(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private static void rect(Canvas c, float x, float y, float w, float h, float r, int color) {
        c.drawRoundRect(new RectF(x, y, x + w, y + h), r, r, fill(color));
    }

    private static void oval(Canvas c, float cx, float cy, float rx, float ry, int color) {
        c.drawOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), fill(color));
    }

    private static void oval(Canvas c, float cx, float cy, float rx, float ry, float degrees, int color) {
        c.save();
        c.rotate(degrees, cx, cy);
        oval(c, cx, cy, rx, ry, color);
        c.restore();
    }

    private static void dots(Canvas c, float r, int color, float... xy) {
        Paint p = fill(color);
        for (int i = 0; i < xy.length; i += 2) c.drawCircle(xy[i], xy[i + 1], r, p);
    }

    private static Path poly(float... xy) {
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.close();
        return p;
    }

    static void heart(Canvas c, float x, float y, float k, Paint p) {
        c.save();
        c.translate(x, y);
        c.scale(k, k);
        c.drawPath(HEART, p);
        c.restore();
    }

    // Sinterklaas: three presents and pepernoten

    private static void sinterklaas(Canvas c) {
        present(c, 18, 326, 52, 58, 0xFFD9363E, 0xFFF2C14E, 8);
        present(c, 74, 352, 34, 32, 0xFF3B7DD8, 0xFFFFFFFF, 7);
        present(c, 236, 336, 58, 48, 0xFF2E9E5B, 0xFFF2C14E, 8);
        dots(c, 4, 0xFF9C5B2E, 118, 392, 132, 397, 222, 394, 306, 393, 318, 388);
    }

    private static void present(Canvas c, float x, float y, float w, float h, int box, int ribbon, float rw) {
        rect(c, x, y, w, h, 4, box);
        rect(c, x + w / 2 - rw / 2, y, rw, h, 0, ribbon);
        rect(c, x, y + h * 0.42f - rw / 2, w, rw, 0, ribbon);
        oval(c, x + w / 2 - 6, y - 4, rw + 1, rw * 0.6f, -20, ribbon);
        oval(c, x + w / 2 + 6, y - 4, rw + 1, rw * 0.6f, 20, ribbon);
    }

    // Kerst: a small tree and snow behind; the light string's wire over the body (the bulbs twinkle)

    private static void kerstBack(Canvas c) {
        c.drawPath(poly(312, 292, 286, 330, 338, 330), fill(0xFF2E8B57));
        c.drawPath(poly(312, 312, 280, 356, 344, 356), fill(0xFF2E8B57));
        c.drawPath(poly(312, 336, 274, 384, 350, 384), fill(0xFF257A4B));
        rect(c, 306, 384, 12, 10, 0, 0xFF7A4A26);
        c.drawPath(poly(312, 280, 315, 288, 323, 288, 317, 293, 319, 301, 312, 296, 305, 301, 307, 293, 301, 288, 309, 288), fill(0xFFFFD75A));
        dots(c, 3.5f, 0xFFFF5A5A, 300, 340, 330, 376);
        dots(c, 3.5f, 0xFF5AA9FF, 322, 350);
        dots(c, 3.5f, 0xFFFFD75A, 296, 372);
        dots(c, 3.5f, 0xFF6BE07A, 312, 320);
        dots(c, 2.2f, 0x80FFFFFF, -24, 40, 30, 14, 250, 4, 330, 24, 362, 96, -30, 170, 360, 210, -18, 270, 10, 110, 100, 0);
    }

    /** The bulbs on the light string, and their colours, for the twinkle. */
    static final float[] BULBS = {126, 245, 148, 252, 170, 253, 192, 249, 214, 239, 132, 290, 151, 296, 170, 297, 189, 293, 208, 284};
    static final int[] BULB_COLORS = {0xFFFF5A5A, 0xFFFFD75A, 0xFF6BE07A, 0xFF5AA9FF, 0xFFFF5A5A, 0xFFFFD75A, 0xFF6BE07A, 0xFF5AA9FF, 0xFFFF5A5A, 0xFFFFD75A};

    private static void kerstWires(Canvas c) {
        Path wire = new Path();
        wire.moveTo(115, 240);
        wire.quadTo(170, 270, 225, 232);
        wire.moveTo(122, 285);
        wire.quadTo(170, 312, 218, 278);
        c.drawPath(wire, line(0xFF2A3A2F, 2));
    }

    // Oudejaarsavond: oliebollen, champagne (the fireworks move)

    private static void oudejaar(Canvas c) {
        oval(c, 58, 384, 44, 9, 0xFFE8ECF0);
        dots(c, 14, 0xFFA8652A, 38, 370, 70, 371);
        dots(c, 13, 0xFFB8742F, 54, 356);
        oval(c, 54, 346, 8, 3, 0xD9FFFFFF);
        oval(c, 35, 360, 6, 2.5f, 0xD9FFFFFF);
        oval(c, 72, 361, 6, 2.5f, 0xD9FFFFFF);
        dots(c, 1.3f, 0xD9FFFFFF, 46, 366, 62, 352);
        rect(c, 278, 300, 12, 26, 0, 0xFF1E4D2B);
        rect(c, 276, 294, 16, 16, 3, 0xFFE0B84C);
        rect(c, 268, 322, 32, 64, 9, 0xFF1E4D2B);
        rect(c, 271, 348, 26, 18, 2, 0xFFF3E9C6);
        c.drawLine(274, 330, 274, 378, line(0x40FFFFFF, 2));
        Path glass = poly(318, 334, 334, 334, 331, 364, 321, 364);
        c.drawPath(glass, fill(0xE6F6D36B));
        c.drawPath(glass, line(0x80FFFFFF, 1));
        c.drawLine(326, 364, 326, 382, line(0xFFDDE3EA, 2));
        oval(c, 326, 384, 8, 2.5f, 0xFFDDE3EA);
        dots(c, 1.5f, 0xFFFFFFFF, 324, 350, 328, 342, 325, 357);
    }

    // Nieuwjaarsdag: confetti and streamers, a sign with the year

    private static void nieuwjaarBack(Canvas c) {
        float[] bits = {30, 40, 20, 60, 90, -30, 20, 150, 45, 55, 220, 10, 300, 30, 35, 325, 95, -20, 290, 160, 60, 330, 220, -40, 40, 300, 70, 310, 290, 15, 100, 20, -15, 245, 20, 25};
        int[] colors = {0xFFFFD75A, 0xFFFF7AA8, 0xFF5AA9FF, 0xFF6BE07A};
        for (int i = 0; i < bits.length; i += 3) {
            c.save();
            c.rotate(bits[i + 2], bits[i], bits[i + 1]);
            rect(c, bits[i] - 5, bits[i + 1] - 2.5f, 10, 5, 1, colors[(i / 3) % 4]);
            c.restore();
        }
        streamer(c, 12, 60, 12, 0xFFFFD75A);
        streamer(c, 338, 120, -12, 0xFFFF7AA8);
    }

    private static void streamer(Canvas c, float x, float y, float swing, int color) {
        Path p = new Path();
        p.moveTo(x, y);
        for (int i = 0; i < 3; i++) p.rQuadTo(i % 2 == 0 ? swing : -swing, 10, 0, 20);
        c.drawPath(p, line(color, 3));
    }

    private static void nieuwjaarSign(Canvas c) {
        rect(c, 112, 378, 116, 28, 7, 0xFFE0B84C);
        Paint text = fill(0xFF3A2A06);
        text.setTextSize(22);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText(String.valueOf(Calendar.getInstance().get(Calendar.YEAR)), 170, 399, text);
    }

    // Valentijnsdag: hearts on the ground (heart eyes and rising hearts move)

    private static void valentijn(Canvas c) {
        heart(c, 300, 328, 2.4f, fill(0xFFE5484D));
        heart(c, 52, 352, 1.5f, fill(0xFFFF7AA8));
    }

    // Pasen: bunny ears behind the head, painted eggs

    private static void bunnyEars(Canvas c) {
        for (int side = -1; side <= 1; side += 2) {
            float x = 170 + side * 22;
            c.save();
            c.rotate(side * 14, x, 40);
            oval(c, x, 36, 12, 26, 0xFFF1F3F6);
            oval(c, x, 38, 6, 18, 0xFFFFB3C7);
            c.restore();
        }
    }

    private static void eggs(Canvas c) {
        Paint white = line(0xFFFFFFFF, 2);
        oval(c, 36, 370, 11, 14, 0xFFFFB3C7);
        zigzag(c, 26, 368, 3.5f, white);
        oval(c, 64, 378, 10, 13, 0xFF9FD8FF);
        c.drawLine(55, 376, 73, 376, line(0xFFFFFFFF, 3));
        dots(c, 1.6f, 0xFFFFFFFF, 60, 386, 68, 386);
        oval(c, 98, 388, 9, 11, 0xFFFFE27A);
        dots(c, 1.5f, 0xFFFFFFFF, 95, 385, 101, 391, 100, 382);
        oval(c, 262, 384, 10, 13, 0xFFB9F0A8);
        zigzag(c, 253, 382, 3, white);
        oval(c, 292, 372, 12, 15, 0xFFD7B8FF);
        Paint band = line(0xFFFFFFFF, 2.5f);
        c.drawLine(281, 368, 303, 368, band);
        c.drawLine(281, 376, 303, 376, band);
        oval(c, 322, 386, 9, 11, 0xFFFFB38A);
        dots(c, 1.5f, 0xFFFFFFFF, 319, 383, 325, 389, 324, 380);
    }

    private static void zigzag(Canvas c, float x, float y, float step, Paint p) {
        Path z = new Path();
        z.moveTo(x, y);
        for (int i = 0; i < 6; i++) z.rLineTo(step, i % 2 == 0 ? -4 : 4);
        c.drawPath(z, p);
    }

    // Koningsdag: orange bunting, a crown, a tompouce

    private static void bunting(Canvas c) {
        Path string = new Path();
        string.moveTo(-12, 22);
        string.quadTo(170, 72, 352, 22);
        c.drawPath(string, line(0x99E8ECF0, 1.5f));
        float[] flags = {26, 31, 62, 38, 98, 43, 242, 43, 278, 38, 314, 31};
        for (int i = 0; i < flags.length; i += 2) {
            float x = flags[i], y = flags[i + 1];
            c.drawPath(poly(x - 9, y, x + 9, y, x, y + 18), fill(i % 4 == 0 ? 0xFFFF8A1F : 0xFFFFB25C));
        }
    }

    private static void crown(Canvas c) {
        Path crown = poly(136, 54, 134, 20, 152, 36, 170, 12, 188, 36, 206, 20, 204, 54);
        c.drawPath(crown, fill(0xFFFF8A1F));
        c.drawPath(crown, line(0xFFFFD08A, 3));
        dots(c, 4, 0xFFFFF1C2, 170, 12);
        dots(c, 3.5f, 0xFFFFF1C2, 134, 20, 206, 20);
        dots(c, 3, 0xFFFFF1C2, 152, 46, 170, 46, 188, 46);
    }

    private static void tompouce(Canvas c) {
        oval(c, 290, 386, 34, 7, 0xFFE8ECF0);
        rect(c, 266, 366, 48, 12, 2, 0xFFE9C27A);
        rect(c, 266, 356, 48, 11, 0, 0xFFFFF4D6);
        rect(c, 266, 350, 48, 7, 0, 0xFFE9C27A);
        rect(c, 265, 343, 50, 9, 3, 0xFFFF8A1F);
    }

    // Bevrijdingsdag: a dove and the Dutch flag

    private static void bevrijding(Canvas c) {
        dots(c, 1.5f, 0x8CFFFFFF, 30, 230, 330, 4, 260, 0, 350, 260);
        c.save();
        c.translate(42, 60);
        c.scale(1.3f, 1.3f);
        c.drawPath(poly(-16, 0, -30, -7, -28, 6), fill(0xFFE1E6EC));
        oval(c, 0, 0, 16, 9, 0xFFF4F6F8);
        dots(c, 6.5f, 0xFFF4F6F8, 15, -5);
        c.drawPath(poly(20, -6, 28, -4, 20, -2), fill(0xFFE0B84C));
        dots(c, 1.2f, 0xFF2A313B, 17, -6.5f);
        Path wing = new Path();
        wing.moveTo(-6, -4);
        wing.quadTo(2, -30, 18, -24);
        wing.quadTo(8, -14, 6, -2);
        wing.close();
        c.drawPath(wing, fill(0xFFFFFFFF));
        c.drawPath(wing, line(0xFFC9CED6, 1));
        Path branch = new Path();
        branch.moveTo(26, -3);
        branch.quadTo(32, 4, 36, 10);
        c.drawPath(branch, line(0xFF6BA35A, 2));
        oval(c, 31, 1, 3.5f, 1.8f, 40, 0xFF6BA35A);
        oval(c, 35, 7, 3.5f, 1.8f, 50, 0xFF6BA35A);
        c.restore();
        c.drawLine(300, 200, 300, 392, line(0xFFC9CED6, 4));
        dots(c, 5, 0xFFE0B84C, 300, 198);
        int[] bands = {0xFFAE1C28, 0xFFF4F6F8, 0xFF21468B};
        for (int i = 0; i < 3; i++) {
            float y = 206 + 16 * i;
            Path band = new Path();
            band.moveTo(302, y);
            band.quadTo(322, y - 8, 342, y);
            band.lineTo(342, y + 16);
            band.quadTo(322, y + 8, 302, y + 16);
            band.close();
            c.drawPath(band, fill(bands[i]));
        }
    }

    // Hemelvaart en Pinksteren: tulips (the butterflies move)

    private static void tulips(Canvas c) {
        float[] xs = {28, 54, 80, 268, 296, 324};
        int[] colors = {0xFFFF5A5A, 0xFFFFD75A, 0xFFFF7AA8, 0xFFFF8A1F, 0xFFFF5A5A, 0xFFD7B8FF};
        Paint stem = line(0xFF4E9E5B, 3);
        for (int i = 0; i < xs.length; i++) {
            float x = xs[i];
            c.drawLine(x, 392, x, 354, stem);
            oval(c, x + 6, 374, 4, 11, 30, 0xFF4E9E5B);
            Path cup = new Path();
            cup.moveTo(x - 9, 336);
            cup.quadTo(x - 10, 354, x, 356);
            cup.quadTo(x + 10, 354, x + 9, 336);
            cup.lineTo(x + 4, 343);
            cup.lineTo(x, 334);
            cup.lineTo(x - 4, 343);
            cup.close();
            c.drawPath(cup, fill(colors[i]));
        }
    }

    // Halloween: moon and bats, a witch hat, pumpkins

    private static void night(Canvas c) {
        dots(c, 30, 0xE6FFF1C2, 308, 52);
        bat(c, 304, 52, 1.7f, 0xFF2A1A33);
        bat(c, 46, 62, 1.5f, 0xFF6E4A93);
        bat(c, 84, 28, 1.1f, 0xFF6E4A93);
        bat(c, 36, 200, 1.2f, 0xFF6E4A93);
    }

    private static void bat(Canvas c, float x, float y, float k, int color) {
        c.save();
        c.translate(x, y);
        c.scale(k, k);
        c.drawPath(BAT, fill(color));
        c.restore();
    }

    private static void witchHat(Canvas c) {
        Path cone = new Path();
        cone.moveTo(140, 49);
        cone.quadTo(156, 30, 166, 14);
        cone.quadTo(174, 4, 190, 10);
        cone.quadTo(180, 14, 180, 26);
        cone.lineTo(200, 49);
        cone.close();
        c.drawPath(cone, fill(0xFF5B347E));
        rect(c, 145, 38, 52, 8, 0, 0xFFFF8A1F);
        oval(c, 170, 50, 58, 10, -6, 0xFF4B2A6B);
    }

    private static void pumpkins(Canvas c) {
        pumpkin(c, 52, 362, 1, true);
        pumpkin(c, 300, 376, 0.7f, false);
    }

    private static void pumpkin(Canvas c, float x, float y, float k, boolean face) {
        c.save();
        c.translate(x, y);
        c.scale(k, k);
        oval(c, -14, 0, 14, 20, 0xFFE8761E);
        oval(c, 14, 0, 14, 20, 0xFFE8761E);
        oval(c, 0, 0, 15, 21, 0xFFFF8A1F);
        rect(c, -3, -27, 6, 9, 2, 0xFF4E7A2E);
        if (face) {
            Paint glow = fill(0xFFFFD75A);
            c.drawPath(poly(-12, -5, -4, -5, -8, -12), glow);
            c.drawPath(poly(4, -5, 12, -5, 8, -12), glow);
            Path mouth = new Path();
            mouth.moveTo(-11, 4);
            mouth.quadTo(0, 13, 11, 4);
            mouth.quadTo(5, 7, 3, 6);
            mouth.lineTo(0, 9);
            mouth.lineTo(-3, 6);
            mouth.quadTo(-5, 7, -11, 4);
            mouth.close();
            c.drawPath(mouth, glow);
        }
        c.restore();
    }

    // Sint-Maarten: stars (the lantern moves)

    private static void stars(Canvas c) {
        dots(c, 2, 0x8CFFFFFF, 30, 60, 100, 10, 260, 6, 340, 50, 360, 140, -20, 150, 20, 250, -25, 330);
    }

    // Verjaardag: balloons, a party hat, a cake (the flames move)

    private static void balloons(Canvas c) {
        Paint string = line(0xFFC9CED6, 1.5f);
        Path left = new Path();
        left.moveTo(34, 112);
        left.quadTo(28, 170, 44, 230);
        left.quadTo(50, 270, 40, 300);
        c.drawPath(left, string);
        oval(c, 34, 88, 18, 22, 0xFFFF5A7E);
        c.drawPath(poly(31, 110, 37, 110, 34, 105), fill(0xFFFF5A7E));
        oval(c, 28, 80, 4, 7, 0x59FFFFFF);
        Path right = new Path();
        right.moveTo(318, 92);
        right.quadTo(326, 150, 312, 220);
        right.quadTo(306, 260, 314, 300);
        c.drawPath(right, string);
        oval(c, 318, 70, 16, 20, 0xFFFFD75A);
        c.drawPath(poly(315, 90, 321, 90, 318, 85), fill(0xFFFFD75A));
        oval(c, 313, 63, 3.5f, 6, 0x59FFFFFF);
        float[] bits = {60, 200, 25, 295, 170, -30, 35, 270, 50};
        int[] colors = {0xFF6BE07A, 0xFFFF7AA8, 0xFF5AA9FF};
        for (int i = 0; i < bits.length; i += 3) {
            c.save();
            c.rotate(bits[i + 2], bits[i], bits[i + 1]);
            rect(c, bits[i] - 5, bits[i + 1] - 2.5f, 10, 5, 1, colors[i / 3]);
            c.restore();
        }
    }

    private static void partyHat(Canvas c) {
        c.save();
        c.rotate(10, 172, 40);
        c.drawPath(poly(148, 54, 172, 14, 196, 54), fill(0xFF5AA9FF));
        dots(c, 3, 0xFFFFD75A, 165, 34, 160, 47);
        dots(c, 3, 0xFFFF7AA8, 178, 42);
        dots(c, 3, 0xFFFFFFFF, 184, 50);
        dots(c, 6, 0xFFFF7AA8, 172, 14);
        c.restore();
    }

    /** Where the candle flames are, for the flicker. */
    static final float[] FLAMES = {287.5f, 325, 305.5f, 325};

    private static void cake(Canvas c) {
        oval(c, 296, 388, 34, 6, 0xFFE8ECF0);
        rect(c, 270, 354, 52, 32, 4, 0xFFFFF1F5);
        rect(c, 268, 348, 56, 11, 5, 0xFFFF7AA8);
        dots(c, 3, 0xFFFF7AA8, 276, 360, 310, 360);
        dots(c, 3.5f, 0xFFFF7AA8, 292, 361);
        rect(c, 285, 330, 5, 18, 0, 0xFF9FD8FF);
        rect(c, 303, 330, 5, 18, 0, 0xFFFFD75A);
    }
}
