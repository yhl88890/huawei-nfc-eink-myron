package org.nfcresearch.qpolllab;

import java.util.Arrays;
import java.util.Random;

/** 网点版画的契约：四色输出、可复现、不改入参、密度不超线、暖肤色不判黄、纯白留纸白。 */
public final class EngravingRasterTest {
    private static final int WIDTH = 276, HEIGHT = 384;

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }

    public static void main(String[] args) {
        // 1. 参数校验
        rejected(() -> EngravingRaster.quantizeToTarget(null, 10, 10, false, 17));
        rejected(() -> EngravingRaster.quantizeToTarget(new int[100], 10, 9, false, 17));

        // 2. 一张"像照片"的测试图：渐变 + 高频边缘 + 彩色块
        Random random = new Random(20261024);
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

        // 3. 只能出四色、必须可复现、不能改入参、密度不能超线
        byte[] codes = EngravingRaster.quantizeToTarget(photo, WIDTH, HEIGHT, false, 17);
        for (byte code : codes) check(code >= 0 && code <= 3, "Palette code out of range");
        check(Arrays.equals(codes, EngravingRaster.quantizeToTarget(photo, WIDTH, HEIGHT, false, 17)),
                "Not deterministic");
        check(Arrays.equals(photo, copy), "Source mutated");
        int density = EngravingRaster.density(codes, WIDTH);
        check(density >= 0 && density <= 17, "Density broke the cap: " + density);

        // 4. 纯白必须留纸白：版画是"白当底"，白底上凭空长点就不叫印刷了
        int[] white = new int[64 * 64];
        Arrays.fill(white, 0xffffffff);
        for (byte code : EngravingRaster.quantizeToTarget(white, 64, 64, false, 17))
            check(code == 1, "Pure white was not left as paper");

        // 5. 暖肤色绝不能被判成黄：版画里黄色要过色相 + 色度双门槛，肤色过不去
        for (int[] skin : new int[][]{{228, 176, 136}, {230, 187, 158}, {213, 159, 125}, {197, 143, 109}}) {
            int[] patch = new int[64 * 64];
            Arrays.fill(patch, 0xff000000 | (skin[0] << 16) | (skin[1] << 8) | skin[2]);
            for (byte code : EngravingRaster.quantizeToTarget(patch, 64, 64, false, 17))
                check(code != 2, "Skin " + Arrays.toString(skin) + " was painted yellow");
        }

        // 6. 真黄必须还是黄，别把闸门做得把黄一起挡掉
        int[] yellow = new int[64 * 64];
        Arrays.fill(yellow, 0xff000000 | (250 << 16) | (215 << 8) | 20);
        boolean kept = false;
        for (byte code : EngravingRaster.quantizeToTarget(yellow, 64, 64, false, 17)) if (code == 2) kept = true;
        check(kept, "True yellow lost its yellow");

        // 7. 黑白模式只出黑白
        for (byte code : EngravingRaster.quantizeToTarget(photo, WIDTH, HEIGHT, true, 17))
            check(code <= 1, "Mono mode produced a color code: " + code);

        System.out.println("EngravingRaster tests passed · 密度 " + density + "% ≤ 17%");
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    private EngravingRasterTest() {}
}
