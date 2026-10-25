package org.nfcresearch.qpolllab;

import java.util.Arrays;
import java.util.Random;

/**
 * 清晰锐利三档的契约：四色输出、可复现、不改入参、档位越高墨越多、平坦区不动。
 *
 * <p>这三档是从 INK 的 {@code SharpQuantizer} 整包移植来的，行为要跟原版一致：
 * <b>浅暖肤色在四色屏上本来就该判成黄色</b>，不许再拿"肤色闸门"把它挡成黑白。
 * 逐字节一致性由桌面端的 {@code VerifySharp} 守着，这里管的是通用契约。
 */
public final class SharpRasterTest {
    private static final int WIDTH = 276, HEIGHT = 384;

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }

    public static void main(String[] args) {
        // 1. 参数校验
        rejected(() -> SharpRaster.quantize(null, 10, 10, 0, false));
        rejected(() -> SharpRaster.quantize(new int[100], 10, 9, 0, false));
        rejected(() -> SharpRaster.quantize(new int[100], 10, 10, 3, false));
        rejected(() -> SharpRaster.quantize(new int[100], 10, 10, -1, false));
        rejected(() -> SharpRaster.quantize(new int[100], 10, 10, 0, 0, 0, 1.5));
        rejected(() -> SharpRaster.density(new byte[100], 9));

        // 2. 一张"像照片"的测试图：渐变 + 高频边缘 + 彩色块
        Random random = new Random(20261023);
        int[] photo = new int[WIDTH * HEIGHT];
        for (int row = 0; row < HEIGHT; row++) {
            for (int column = 0; column < WIDTH; column++) {
                int shade = Math.min(255, column * 255 / WIDTH);
                if (((row / 3) + (column / 3)) % 2 == 0) shade = Math.min(255, shade + 40);
                int red = shade, green = shade, blue = shade;
                if (column > WIDTH / 2) { red = 210; green = 40; blue = 40; }        // 红块
                if (row < 40) { red = 250; green = 225; blue = 60; }                 // 黄块
                int jitter = random.nextInt(17) - 8;
                photo[row * WIDTH + column] = 0xff000000
                        | (clamp(red + jitter) << 16) | (clamp(green + jitter) << 8) | clamp(blue + jitter);
            }
        }
        int[] copy = photo.clone();

        // 3. 只能出四色、不能改入参、必须可复现、细档不许越过实机验证过的上限
        int[] densities = new int[SharpRaster.BUDGET.length];
        for (int tier = 0; tier < SharpRaster.BUDGET.length; tier++) {
            byte[] codes = SharpRaster.quantize(photo, WIDTH, HEIGHT, tier, false);
            for (byte code : codes) check(code >= 0 && code <= 3, "Palette code out of range");
            check(Arrays.equals(codes, SharpRaster.quantize(photo, WIDTH, HEIGHT, tier, false)), "Not deterministic");
            densities[tier] = SharpRaster.density(codes, WIDTH);
            check(densities[tier] > 0 && densities[tier] <= 40, "Density outside usable range: " + densities[tier]);
        }
        check(densities[2] <= SharpRaster.FINE_CEILING,
                "Fine tier broke the verified ceiling: " + densities[2] + " > " + SharpRaster.FINE_CEILING);
        check(Arrays.equals(photo, copy), "Source mutated");

        // 4. 档位越高，墨不会更少。预算越大抖动区越连片，密度单调上升。
        check(densities[1] >= densities[0] && densities[2] >= densities[1],
                "Tiers must be monotonic: " + Arrays.toString(densities));

        // 5. 纯色图必须原样保留：平坦区不抖动，这正是它比全图抖动省电的原因
        for (int color = 0; color < 4; color++) {
            int[] solid = new int[32 * 32];
            Arrays.fill(solid, ImageRaster.color(color));
            for (byte code : SharpRaster.quantize(solid, 32, 32, 2, false))
                check(code == color, "Solid color changed: expected " + color + " got " + code);
        }

        // 6. 黑白模式只出黑/白
        for (byte code : SharpRaster.quantize(photo, WIDTH, HEIGHT, 1, true))
            check(code <= 1, "Mono mode produced a color code");

        // 7. 四色必须都用得上：黄色不能被算法自己吃掉。
        //    这是上一次翻车的回归——加了"肤色只许黑白""黄色色相闸门"以后整张图只剩黑白红，
        //    黄占比从 6.9% 掉到 0.1%，阿亮一眼就看出来了。
        int[] warm = new int[64 * 64];
        for (int row = 0; row < 64; row++)
            for (int column = 0; column < 64; column++) {
                int value = 120 + column;                              // 暖色渐变，模拟皮肤
                warm[row * 64 + column] = 0xff000000 | (clamp(value + 40) << 16)
                        | (clamp(value) << 8) | clamp(value - 40);
            }
        boolean sawYellow = false;
        for (byte code : SharpRaster.quantize(warm, 64, 64, 2, false)) if (code == 2) sawYellow = true;
        check(sawYellow, "Yellow disappeared from the palette — the skin gate came back");

        // 8. 分辨率无关：同样的图在 552×768 上也要落在可刷区间
        int[] panel = new int[552 * 768];
        for (int index = 0; index < panel.length; index++) panel[index] = photo[index % photo.length];
        int panelDensity = SharpRaster.density(SharpRaster.quantize(panel, 552, 768, 2, false), 552);
        check(panelDensity > 0 && panelDensity <= SharpRaster.FINE_CEILING,
                "Panel density broke the ceiling: " + panelDensity);

        System.out.println("SharpRaster tests passed · 棋盘 稳 " + densities[0] + "% / 中 " + densities[1]
                + "% / 细 " + densities[2] + "%");
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    private SharpRasterTest() {}
}
