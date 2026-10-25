package org.nfcresearch.qpolllab;

/**
 * 网点版画 · 把照片画成印刷品。
 *
 * <p>四色屏上直接量化真人照，脸一定变黄脸、灰墙一定变脏块，因为照片里全是连续调。
 * 这个算法反过来，先决定"这张画该怎么画"，只做四件事：
 *
 * <ol>
 *   <li><b>语义配色</b>：皮肤、灰墙、黑衣服一律走黑白，只有真正的暖色（黄墙、木头、灯光）
 *       和高饱和的红才配黄、红。这是它看着像画、而不是照片压成四色的根本原因。</li>
 *   <li><b>四阶调</b>：亮度先压成暗 / 中 / 浅 / 亮四段，每段配一个<b>固定</b>的网点覆盖率
 *       （实黑 / 35% / 15% / 6%）。照片的连续变化被换成四个"印刷调子"，
 *       大片同类区域因此变成同一片网点，而不是一层浮动的灰噪声。</li>
 *   <li><b>轮廓线</b>：只在<b>梯度</b>大的地方提黑线（不是"比周围暗一截"）。这一条是从失败里学来的：
 *       拿局部均值比暗，平滑的皮肤和亮面漆皮上会到处命中，整张图像铅笔素描；
 *       换成梯度以后，线只落在真正的外形边界和褶边上。</li>
 *   <li><b>色块内部不画线</b>：暗色块（红衣服）内部一律平涂，黑线在深红上就是一道黑疤。</li>
 * </ol>
 *
 * <p>历史教训：连续网点（position = 亮度曲线）在真人照上会把皮肤做成一片脏灰；
 * 想靠"多给点细节"补救只会更脏。印刷品好看是因为调子少，不是因为细节多。
 */
public final class EngravingRaster {
    /** 明暗分档的亮度边界：暗部 / 中调 / 浅调 / 亮部。四段 = 四种印刷调子。 */
    static final double[] BAND_EDGES = {55, 115, 170, 212};
    /** 每一档的网点覆盖率：暗部实黑、中调 35%、浅调 15%、高光 6%，超过最亮直接纸白。 */
    static final double[] BAND_COVERS = {1, .35, .15, .06};
    /** 梯度超过这个值才算轮廓线（0–255）。越大线越少越干净。 */
    static final double INK_EDGE = 50;
    /** 局部均值超过这个亮度就不再提线，免得白墙上凭空冒出灰线。 */
    static final double INK_CEILING = 236;
    /** 低于这个亮度算"实黑"，补色时不再往彩色里并。 */
    static final double SOLID_DARK = 24;
    /** 只把全图最艳的这一撮像素配成彩色，其余一律黑白。绝对色相门槛会误伤肤色，排名不会。 */
    static final double COLOR_SHARE = .16;
    /** 色度门槛的上限：再高就不许涨了，否则翻拍照片的低饱和暖色会被一并砍掉。 */
    static final double COLOR_CHROMA_CAP = 46;
    /** 色度门槛的下限：无色差的像素永远不算"有色"。 */
    static final double COLOR_CHROMA_FLOOR = 32;
    /** 配黄色的亮度下限：暗黄的头发、暗黄的木头不该变成亮黄。 */
    static final double COLOR_LIGHT = 96;
    /** 配红色之外，黄色还要更高的色度：米色毛发（色度 50）和真黄（色度 100+）必须分开，
     *  否则白狗会变成黄狗——这是拿豆包那张巴哥犬对出来的。 */
    static final double COLOR_YELLOW_CHROMA = 62;
    /** 配红色的亮度下限：红本来就深，门槛低得多，只要别是纯黑就行。 */
    static final double COLOR_DARK = 40;
    /** 黄色：色相 40–75 度。 */
    static final double YELLOW_HUE_LOW = 40, YELLOW_HUE_HIGH = 75;
    /** 红色：色相 20 度以下或 340 度以上。20–40 度是肤色区，一律不配彩色。 */
    static final double RED_HUE_HIGH = 340, RED_HUE_LOW = 20;
    /**
     * 彩色区域内部还允许画轮廓线的最低亮度。
     *
     * <p>屏上的红（C80000）本身亮度只有 60 出头，再往上面压黑线，一条线就是一道黑疤。
     * 暗色块内部干脆只上色、不画线；亮色块（黄墙、米色桌布）亮度足够，黑线是它唯一的细节来源。
     */
    static final double COLOR_LINE_MIN = 120;
    /** 3×3 邻域里彩色像素占到这么多，就算"彩色区域内部"。 */
    static final double COLOR_SOLID_SHARE = .7;

    /** 黑白二值化模式的编号，跟 INK 工程里的 ImageQuantizer.MONO 对齐。 */
    private static final int MONO = 2;

    /**
     * QPollLab 的入口：只要一张四色（或黑白）成品，亮度/对比度/抖动强度都用固定值。
     *
     * @param mono          黑白二值化模式：只出黑白，不配黄红
     * @param targetDensity 翻转密度上限（%）。超了就一级一级收紧轮廓线和网点
     */
    public static byte[] quantizeToTarget(int[] pixels, int width, int height, boolean mono, int targetDensity) {
        return quantizeToTarget(pixels, width, height, mono ? MONO : 0, 0, 0, .85, targetDensity);
    }

    /** 翻转密度（%）：相邻像素颜色不同的比例，就是"屏要翻多少墨"的账。 */
    public static int density(byte[] codes, int width) {
        int flips = 0;
        for (int index = 0; index < codes.length; index++) {
            if (index + 1 < codes.length && (index + 1) % width != 0 && codes[index] != codes[index + 1]) flips++;
            if (index + width < codes.length && codes[index] != codes[index + width]) flips++;
        }
        return (int) Math.round(flips * 100.0 / (codes.length * 2));
    }

    /** 8×8 有序网点矩阵。用 row/col 直接取模，不要拿 row±col 当索引——那样每行只会命中一条对角线，网点全退化。 */
    private static final int[][] SCREEN = {
            {0, 32, 8, 40, 2, 34, 10, 42},
            {48, 16, 56, 24, 50, 18, 58, 26},
            {12, 44, 4, 36, 14, 46, 6, 38},
            {60, 28, 52, 20, 62, 30, 54, 22},
            {3, 35, 11, 43, 1, 33, 9, 41},
            {51, 19, 59, 27, 49, 17, 57, 25},
            {15, 47, 7, 39, 13, 45, 5, 37},
            {63, 31, 55, 23, 61, 29, 53, 21}};

    public static byte[] quantize(int[] pixels, int width, int height, int mode,
                                  int brightness, int contrast, double strength) {
        return quantize(pixels, width, height, mode, brightness, contrast, strength, INK_EDGE, 1);
    }

    /**
     * 按目标翻转密度出图。
     *
     * <p>密度跟着画面内容跑：干净人像 5–8%，细节碎的翻拍照片能到 20% 以上。超线就一级一级地收：
     * 先少提些轮廓线（梯度门槛抬高），再整体调薄网点（覆盖比例下调）。收的是"墨量"，风格不变。
     */
    public static byte[] quantizeToTarget(int[] pixels, int width, int height, int mode,
                                         int brightness, int contrast, double strength, int targetDensity) {
        double[] edges = {INK_EDGE, 72, 96, 130, 170};
        double[] scales = {1, .85, .7, .55, .4};
        byte[] last = null;
        for (int index = 0; index < edges.length; index++) {
            last = quantize(pixels, width, height, mode, brightness, contrast, strength, edges[index], scales[index]);
            if (density(last, width) <= targetDensity) return last;
        }
        return last;
    }

    private static byte[] quantize(int[] pixels, int width, int height, int mode,
                                   int brightness, int contrast, double strength, double inkEdge, double coverScale) {
        if (pixels == null || width < 1 || height < 1 || (long) width * height != pixels.length
                || mode < 0 || mode > 2 || brightness < -40 || brightness > 40 || contrast < -30 || contrast > 50
                || !Double.isFinite(strength) || strength < 0 || strength > 1)
            throw new IllegalArgumentException("无效的版画算法参数");
        int count = pixels.length;
        double[] luminance = new double[count];
        double[] chroma = new double[count];
        double[] hue = new double[count];
        boolean[] skin = new boolean[count];
        double gain = 1 + contrast / 100.0;
        boolean photo = mode != MONO;
        for (int index = 0; index < count; index++) {
            int pixel = pixels[index];
            double alpha = (pixel >>> 24) / 255.0;
            double red = channel((pixel >>> 16) & 255, alpha, gain, brightness);
            double green = channel((pixel >>> 8) & 255, alpha, gain, brightness);
            double blue = channel(pixel & 255, alpha, gain, brightness);
            double maximum = Math.max(red, Math.max(green, blue));
            double minimum = Math.min(red, Math.min(green, blue));
            luminance[index] = red * .299 + green * .587 + blue * .114;
            chroma[index] = photo ? maximum - minimum : 0;
            hue[index] = photo ? hueOf(red, green, blue, maximum, minimum) : 0;
            // 肤色判断只当兜底：色相已经把它夹在红黄之间了，这里再按 YCbCr 挡一道，
            // 免得某些偏暖的照片把整张脸配成红。限定色相区间，别让它把黄墙一起吃进去。
            skin[index] = photo && hue[index] >= 14 && hue[index] <= 40 && isSkin(red, green, blue);
        }
        // 自动色阶：先把这张照片自己的亮度范围铺满，再谈"白当底"。
        autoLevels(luminance);
        double[] local = new double[count];
        java.lang.System.arraycopy(luminance, 0, local, 0, count);
        boxBlur(local, width, height);

        // 百分位负责把肤色挡在外面，绝对上限负责别把米黄、浅红这类低饱和暖色也一起挡掉。
        // 下限 32 是安全阀：整幅只有一种颜色时百分位会算出 0，那会让纯黑纯白都被判成"有色"。
        double colorFloor = photo
                ? Math.max(COLOR_CHROMA_FLOOR, Math.min(chromaPercentile(chroma, COLOR_SHARE), COLOR_CHROMA_CAP))
                : Double.MAX_VALUE;
        byte[] colorCode = new byte[count];
        if (photo) {
            for (int index = 0; index < count; index++) {
                if (skin[index] || chroma[index] < colorFloor) continue;
                double angle = hue[index];
                if (angle >= YELLOW_HUE_LOW && angle <= YELLOW_HUE_HIGH && luminance[index] >= COLOR_LIGHT
                        && chroma[index] >= COLOR_YELLOW_CHROMA)
                    colorCode[index] = (byte) 2;
                // 红用更低的亮度门槛：屏上的红本来就深，拿黄的标准会把真正的红全掐掉
                else if ((angle <= RED_HUE_LOW || angle >= RED_HUE_HIGH) && luminance[index] >= COLOR_DARK)
                    colorCode[index] = (byte) 3;
            }
        }
        double[] colorShare = new double[count];
        for (int index = 0; index < count; index++) colorShare[index] = colorCode[index] == 0 ? 0 : 1;
        boxBlur(colorShare, width, height);
        // 补红：COLOR_DARK 这道门槛是为了挡住深棕头发，但它也把红衣服的阴影一并踢出了红色。
        // 被判成"红色区域内部"（四周几乎都是红）就说明它确实是那件红衣服，补回红色。
        if (photo) {
            for (int index = 0; index < count; index++) {
                if (colorCode[index] != 0 || colorShare[index] < COLOR_SOLID_SHARE) continue;
                if (skin[index] || chroma[index] < colorFloor || luminance[index] <= SOLID_DARK) continue;
                double angle = hue[index];
                if (angle <= RED_HUE_LOW || angle >= RED_HUE_HIGH) colorCode[index] = (byte) 3;
            }
            fillDarkBlockGaps(colorCode, luminance, colorShare, width, height);
            for (int index = 0; index < count; index++) colorShare[index] = colorCode[index] == 0 ? 0 : 1;
            boxBlur(colorShare, width, height);
        }

        byte[] codes = new byte[count];
        for (int row = 0; row < height; row++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                double value = luminance[index];
                // 暗色块内部（红衣服）一律只上色：黑线在深红上就是黑疤。
                if (colorCode[index] != 0 && value < COLOR_LINE_MIN && colorShare[index] >= COLOR_SOLID_SHARE) {
                    codes[index] = colorCode[index];
                    continue;
                }
                if (isContour(luminance, local, row, column, inkEdge, width, height)) {
                    codes[index] = (byte) 0;
                    continue;
                }
                if (colorCode[index] != 0) {
                    codes[index] = colorCode[index];
                    continue;
                }
                codes[index] = (byte) toneStep(value, row, column, coverScale);
            }
        }
        dissolveBlackInRed(codes, width, height);
        return codes;
    }

    /**
     * 梯度判据：四邻域亮度差够大、且自己在暗侧，才算一条轮廓线。
     *
     * <p>老做法是"比局部均值暗一截"，那在平滑的皮肤、亮面漆皮上到处命中，
     * 整张图变成铅笔素描——阿亮那张红皮衣照片就是这么被画满黑线的。
     * 梯度只认真正的明暗突变，平滑渐变再陡也不会触发。
     */
    private static boolean isContour(double[] luminance, double[] local, int row, int column,
                                     double inkEdge, int width, int height) {
        if (row < 1 || column < 1 || row >= height - 1 || column >= width - 1) return false;
        int index = row * width + column;
        if (local[index] >= INK_CEILING) return false;
        double gx = luminance[index + 1] - luminance[index - 1];
        double gy = luminance[index + width] - luminance[index - width];
        if (gx * gx + gy * gy < inkEdge * inkEdge) return false;
        return luminance[index] < local[index];
    }

    /**
     * 印刷四阶调：亮度落在哪一段，就按那一段固定的覆盖率铺网点。
     *
     * <p>覆盖率是"这一段就是这么多墨"，不再随亮度连续变化——这就是海报和照片的区别。
     */
    private static int toneStep(double value, int row, int column, double coverScale) {
        // 网点按 2×2 一块来排：点变大、间距变明显，观感是"用更少的点重建画面"，块内不翻转也省电。
        double threshold = SCREEN[(row >> 1) & 7][(column >> 1) & 7] / 64.0;
        for (int index = 0; index < BAND_EDGES.length; index++)
            if (value < BAND_EDGES[index]) return threshold < BAND_COVERS[index] * coverScale ? 0 : 1;
        return 1;
    }

    /**
     * 暗色块内部的"空隙"并进色块。
     *
     * <p>红衣服的暗褶、阴影里色度被压没了，前面的配色判定认不出来，它们就会掉到网点/黑线那条路上去。
     * 判据不看像素自己，看它周围：5×5 里绝大多数都是同一种颜色、而自己又暗 → 那就是这件衣服的一部分。
     */
    private static void fillDarkBlockGaps(byte[] colorCode, double[] luminance, double[] colorShare, int width, int height) {
        byte[] source = colorCode.clone();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                if (source[index] != 0 || colorShare[index] < COLOR_SOLID_SHARE) continue;
                if (luminance[index] >= COLOR_LINE_MIN) continue;
                int[] votes = new int[4];
                int counted = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    int y = row + dy;
                    if (y < 0 || y >= height) continue;
                    for (int dx = -2; dx <= 2; dx++) {
                        int x = column + dx;
                        if (x < 0 || x >= width) continue;
                        int code = source[y * width + x] & 3;
                        if (code == 0) continue;
                        votes[code]++;
                        counted++;
                    }
                }
                int winner = 0;
                for (int code = 1; code < 4; code++) if (votes[code] > votes[winner]) winner = code;
                if (winner != 0 && votes[winner] >= counted * .6) colorCode[index] = (byte) winner;
            }
        }
    }

    /**
     * 红区里残留的黑线，直接溶进红色。
     *
     * <p>红衣服的深褶本身就很暗，从轮廓线、网点、边界任何一条路都能漏进来，一条一条去堵不现实。
     * 换到结果层面看：一个黑像素，5×5 里六成以上都是红——那就是这件红衣服上的黑疤，涂回红色。
     * 只处理红：黄墙上的黑线条是细节，正是版画要的东西，不能一起抹掉。
     */
    private static void dissolveBlackInRed(byte[] codes, int width, int height) {
        byte[] source = codes.clone();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                if ((source[index] & 3) != 0) continue;
                int seen = 0, red = 0;
                for (int dy = -2; dy <= 2; dy++) {
                    int y = row + dy;
                    if (y < 0 || y >= height) continue;
                    for (int dx = -2; dx <= 2; dx++) {
                        int x = column + dx;
                        if (x < 0 || x >= width) continue;
                        seen++;
                        if ((source[y * width + x] & 3) == 3) red++;
                    }
                }
                if (red >= seen * .6) codes[index] = (byte) 3;
            }
        }
    }

    /** 色度的分位值：只留最艳的那一撮，肤色自动被排除在外，不用写死任何色相范围。 */
    private static double chromaPercentile(double[] chroma, double share) {
        int[] histogram = new int[256];
        int total = 0;
        for (double value : chroma) {
            int bucket = (int) Math.min(255, Math.max(0, Math.round(value)));
            histogram[bucket]++;
            total++;
        }
        int target = (int) Math.round(total * (1 - share));
        int seen = 0;
        for (int bucket = 0; bucket < 256; bucket++) {
            seen += histogram[bucket];
            if (seen >= target) return bucket;
        }
        return 255;
    }

    /** 把亮度的 2%–98% 分位拉到 0–255。原本就铺满的图几乎不变，偏暗的图会明显亮起来。 */
    private static void autoLevels(double[] luminance) {
        int[] histogram = new int[256];
        for (double value : luminance)
            histogram[(int) Math.max(0, Math.min(255, Math.round(value)))]++;
        int total = luminance.length;
        int low = 0, high = 255, seen = 0;
        int lowTarget = (int) (total * .02);
        for (int bucket = 0; bucket < 256; bucket++) {
            seen += histogram[bucket];
            if (seen >= lowTarget) { low = bucket; break; }
        }
        seen = 0;
        int highTarget = (int) (total * .98);
        for (int bucket = 0; bucket < 256; bucket++) {
            seen += histogram[bucket];
            if (seen >= highTarget) { high = bucket; break; }
        }
        if (high - low < 40) return;                    // 本来就是极端的图（纯色/线稿），别硬拉
        double scale = 255.0 / (high - low);
        for (int index = 0; index < luminance.length; index++)
            luminance[index] = Math.max(0, Math.min(255, (luminance[index] - low) * scale));
    }

    /** 肤色判定（YCbCr 经验区间）。上下限放宽到覆盖偏白和偏深两种肤色，仍然挡得住橙红。 */
    private static boolean isSkin(double red, double green, double blue) {
        double cb = 128 - 0.168736 * red - 0.331264 * green + 0.5 * blue;
        double cr = 128 + 0.5 * red - 0.418688 * green - 0.081312 * blue;
        return cr >= 133 && cr <= 178 && cb >= 77 && cb <= 127;
    }

    private static double hueOf(double red, double green, double blue, double maximum, double minimum) {
        double span = maximum - minimum;
        if (span <= 0) return 0;
        double angle;
        if (maximum == red) angle = (green - blue) / span;
        else if (maximum == green) angle = 2 + (blue - red) / span;
        else angle = 4 + (red - green) / span;
        angle *= 60;
        return angle < 0 ? angle + 360 : angle;
    }

    private static double channel(int value, double alpha, double gain, double brightness) {
        double blended = value * alpha + 255 * (1 - alpha);
        return Math.max(0, Math.min(255, (blended - 128) * gain + 128 + brightness));
    }

    private static void boxBlur(double[] values, int width, int height) {
        double[] source = values.clone();
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                double total = 0;
                int samples = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int y = row + dy;
                    if (y < 0 || y >= height) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = column + dx;
                        if (x < 0 || x >= width) continue;
                        total += source[y * width + x];
                        samples++;
                    }
                }
                values[row * width + column] = total / samples;
            }
        }
    }

    private EngravingRaster() {}
}
