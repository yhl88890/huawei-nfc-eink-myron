package org.nfcresearch.qpolllab;

import java.util.Arrays;

/**
 * 清晰锐利 · 边缘抖动（从 INK 工程 {@code dev.inknfclab.SharpQuantizer} 整包移植）。
 *
 * <p>纯色无抖动之所以能刷成功，是因为大片区域取同一个颜色，屏几乎不用搬墨；
 * 一旦全图抖动，几乎每个像素都在翻转，刷新的一瞬间电流把屏内电容拉穿，就报低电压。
 *
 * <p>所以这个算法的做法是反过来：<b>只有真正有细节的地方（边缘、五官、发丝、纹理）才允许抖动</b>，
 * 平坦的皮肤、背景一律取纯色。抖动像素数由预算硬性封顶（{@link #BUDGET}），
 * 于是"层次感"只花在刀刃上，总墨量和纯色无抖动是一个量级。
 *
 * <p>清晰锐利来自两步预处理：unsharp 提升高频边缘，同时在量化前先把色度往亮度收，
 * 避免四色屏上出现脏色块。
 *
 * <p><b>移植说明</b>：这里不再有"肤色只许黑白""黄色必须过色相闸门"这类自加的限制。
 * INK 原版的选色就是 OKLab 最近邻，浅肤色在四色屏上本来就会被判成黄——那是它的正常表现，
 * 加闸门把黄挡掉会让整张图只剩黑白红三色。逐字节一致性由 {@code VerifySharp} 守着。
 */
public final class SharpRaster {
    /** 三个档位在界面上的名字。 */
    public static final String[] TIERS = {"清晰锐利 · 稳", "清晰锐利 · 中", "清晰锐利 · 细"};
    /** unsharp 强度。越大边缘越硬，超过 1.6 会出白边。 */
    static final double SHARPEN = 1.15;
    /** 稳档：允许携带抖动的像素占比上限——这就是能耗闸门。 */
    public static final double DITHER_BUDGET = .25;
    /** 中档：翻转密度约 16%（人像实测）。 */
    public static final double DITHER_BUDGET_MID = .35;
    /** 细档：翻转密度约 20%（人像实测），已验证能刷。 */
    public static final double DITHER_BUDGET_FINE = .45;
    /** 三档对应的预算，按 tier 索引。 */
    public static final double[] BUDGET = {DITHER_BUDGET, DITHER_BUDGET_MID, DITHER_BUDGET_FINE};
    /**
     * 各档的翻转密度上限。阿亮 2026-10-24 实测：19–21% 在他的屏上已经刷不动，
     * 所以全部收紧——稳 10 / 中 13 / 细 16，宁可层次少一点也要保证每一档都刷得上去。
     */
    public static final int[] TARGET = {10, 13, 16};
    /** 「细」的密度上限，与 TARGET[2] 一致。 */
    public static final int FINE_CEILING = TARGET[2];

    /**
     * 选色的两色投影阈值。
     *
     * <p>误差扩散在平缓渐变（大面积皮肤）上会在黄/红/白之间来回跳，撒出一地杂点。
     * 投影的意思是：在最近色和次近色之间按亮度投影选，抖动变成"两种颜色的明暗过渡"，
     * 不再乱蹦色相。只对色度高的像素生效（见下两道护栏），灰背景和奶油色狗毛照原版取最近色。
     */
    public static final double CHOOSE_THRESHOLD = .5;
    /** 护栏一：色度权重低于它的（灰背景、白墙）不走投影。 */
    private static final double THRESHOLD_WEIGHT_FLOOR = .3;
    /** 护栏二：原始色度低于它的（奶油狗毛 50 上下）不走投影——投影会把白毛拽成黄毛。 */
    private static final double THRESHOLD_CHROMA_FLOOR = 60;
    /** 认作"孤岛"的块大小上限（像素数）。只清单像素，多一格就会开始吃掉五官的高光点。 */
    public static final int ISOLATED_MAX = 1;
    /** 边界外同一种颜色要占到这个比例，才认定这块是泡在纯色里的噪点，而不是双色交界处的真细节。 */
    public static final double ISOLATED_CONSENSUS = .8;
    private static final int[][] FS = {{1, 0, 7}, {-1, 1, 3}, {0, 1, 5}, {1, 1, 1}};

    /** 黑白二值化模式的 mode 值（INK 里 ImageQuantizer.MONO）。 */
    private static final int MONO = 2;
    private static final int[] COLORS = {0xff000000, 0xffffffff, 0xfff5cd00, 0xffc80000};
    private static final double[] LINEAR = new double[256];
    private static final double[][] PALETTE = new double[4][];

    static {
        for (int value = 0; value < 256; value++) {
            double channel = value / 255.0;
            LINEAR[value] = channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
        }
        for (int index = 0; index < 4; index++) {
            int color = COLORS[index];
            PALETTE[index] = lab((color >>> 16) & 255, (color >>> 8) & 255, color & 255);
        }
    }

    private SharpRaster() {}

    /**
     * 按档位出图。亮度/对比度取中性、强度取满，跟 INK 界面的默认值一致。
     *
     * @param tier 0 = 稳，1 = 中，2 = 细
     * @param mono 黑白二值化模式：只在黑白之间抖动
     */
    public static byte[] quantize(int[] pixels, int width, int height, int tier, boolean mono) {
        if (tier < 0 || tier >= BUDGET.length) throw new IllegalArgumentException("算法档位错误");
        int mode = mono ? MONO : 0;
        byte[] codes;
        if (tier == 2) {
            codes = quantizeFine(pixels, width, height, mode, 0, 0, 1);
        } else {
            codes = quantize(pixels, width, height, mode, 0, 0, 1, BUDGET[tier]);
            if (density(codes, width) > TARGET[tier])
                codes = quantizeToTarget(pixels, width, height, mode, 0, 0, 1, TARGET[tier]);
        }
        // 清孤岛：误差扩散甩在平缓渐变上的孤立散点，既是画面杂点又实打实抬密度。
        // 成串的真细节（发丝、衣褶、五官）碰不到这个条件，所以只省电不糊图。实测细档 19%→9%。
        return removeIsolatedPoints(codes, width, height, ISOLATED_MAX, 1);
    }

    public static byte[] quantize(int[] pixels, int width, int height, int mode,
                                  int brightness, int contrast, double strength) {
        return quantize(pixels, width, height, mode, brightness, contrast, strength, DITHER_BUDGET);
    }

    /**
     * @param budget 允许携带抖动的像素占比上限（0–1）。这就是"要多少细节 / 花多少电"的旋钮：
     *               越大细节越多，翻转密度越高，越容易在刷新时报低电压。
     */
    public static byte[] quantize(int[] pixels, int width, int height, int mode,
                                  int brightness, int contrast, double strength, double budget) {
        if (pixels == null || width < 1 || height < 1 || (long) width * height != pixels.length
                || mode < 0 || mode > 2 || brightness < -40 || brightness > 40 || contrast < -30 || contrast > 50
                || !Double.isFinite(strength) || strength < 0 || strength > 1
                || !Double.isFinite(budget) || budget < 0 || budget > 1)
            throw new IllegalArgumentException("无效的锐利算法参数");
        int count = pixels.length;
        double[] red = new double[count];
        double[] green = new double[count];
        double[] blue = new double[count];
        double[] weight = new double[count];
        double[] chromaMap = new double[count];
        double gain = 1 + contrast / 100.0;
        for (int index = 0; index < count; index++) {
            int pixel = pixels[index];
            double alpha = (pixel >>> 24) / 255.0;
            double channelRed = clamp(((((pixel >>> 16) & 255) * alpha + 255 * (1 - alpha)) - 128) * gain + 128 + brightness);
            double channelGreen = clamp(((((pixel >>> 8) & 255) * alpha + 255 * (1 - alpha)) - 128) * gain + 128 + brightness);
            double channelBlue = clamp((((pixel & 255) * alpha + 255 * (1 - alpha)) - 128) * gain + 128 + brightness);
            double chroma = Math.max(channelRed, Math.max(channelGreen, channelBlue))
                    - Math.min(channelRed, Math.min(channelGreen, channelBlue));
            double chromaWeight = mode == MONO ? 0 : smooth(8, 48, chroma)
                    * (1 - smooth(0, 36, channelBlue - channelRed - 6)
                    * smooth(-16, 24, channelBlue - channelGreen * .65));
            double luminance = channelRed * .2126 + channelGreen * .7152 + channelBlue * .0722;
            red[index] = luminance + (channelRed - luminance) * chromaWeight;
            green[index] = luminance + (channelGreen - luminance) * chromaWeight;
            blue[index] = luminance + (channelBlue - luminance) * chromaWeight;
            weight[index] = chromaWeight;
            chromaMap[index] = chroma;
        }
        // 模糊一次拿到局部均值，既用来做 unsharp，也用来算稳定的梯度（原图直接算梯度全是噪点）
        double[] smoothLuminance = new double[count];
        double[] scratch = new double[count];
        sharpen(red, scratch, smoothLuminance, width, height, .2126);
        sharpen(green, scratch, smoothLuminance, width, height, .7152);
        sharpen(blue, scratch, smoothLuminance, width, height, .0722);

        int[] gradientMap = gradientMap(smoothLuminance, width, height);
        int[] histogram = new int[257];
        for (int value : gradientMap) histogram[value]++;
        int allowed = (int) Math.round(count * budget * strength);
        int cutoff = cutoffFor(histogram, allowed);

        double[][] errors = new double[3][(width + 4) * 3];
        byte[] codes = new byte[count];
        for (int row = 0; row < height; row++) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int direction = (row & 1) == 0 ? 1 : -1;
            for (int column = direction == 1 ? 0 : width - 1; column >= 0 && column < width; column += direction) {
                int index = row * width + column;
                int offset = (column + 2) * 3;
                // 误差只在允许抖动的像素之间传递；平坦区既不接收也不外溢，所以永远是纯色
                boolean dither = cutoff > 0 && gradientMap[index] >= cutoff;
                double sampleRed = clamp(red[index] + errors[0][offset]);
                double sampleGreen = clamp(green[index] + errors[0][offset + 1]);
                double sampleBlue = clamp(blue[index] + errors[0][offset + 2]);
                int code = choose(sampleRed, sampleGreen, sampleBlue, weight[index],
                        weight[index] >= THRESHOLD_WEIGHT_FLOOR && chromaMap[index] >= THRESHOLD_CHROMA_FLOOR
                                ? CHOOSE_THRESHOLD : -1);
                codes[index] = (byte) code;
                if (!dither) continue;
                int color = COLORS[code];
                for (int[] step : FS) {
                    int targetRow = row + step[1];
                    int targetColumn = column + step[0] * direction;
                    if (targetRow >= height || targetColumn < 0 || targetColumn >= width) continue;
                    if (gradientMap[targetRow * width + targetColumn] < cutoff) continue;
                    int target = offset + step[0] * direction * 3;
                    double factor = strength * step[2] / 16.0;
                    errors[step[1]][target] += (sampleRed - ((color >>> 16) & 255)) * factor;
                    errors[step[1]][target + 1] += (sampleGreen - ((color >>> 8) & 255)) * factor;
                    errors[step[1]][target + 2] += (sampleBlue - (color & 255)) * factor;
                }
            }
            double[] first = errors[0];
            errors[0] = errors[1];
            errors[1] = errors[2];
            errors[2] = first;
            Arrays.fill(first, 0);
        }
        return codes;
    }

    /**
     * 把被同色包围的小碎块并进周围的颜色。
     *
     * <p>边缘抖动会甩出一堆 1~3 像素的孤岛——它们既是画面上的脏点，又实打实抬翻转密度，
     * 纯粹是白费电。真正的细节（发丝、衣褶、文字笔画）是成串的，碰不到这个条件。
     * 所以这是"用减法把加法的浪费清掉"：密度降下来，边缘反而因为颜色更实而更硬。
     */
    public static byte[] removeIsolatedPoints(byte[] codes, int width, int height, int maxSize) {
        return removeIsolatedPoints(codes, width, height, maxSize, 1);
    }

    /**
     * 同上，但清理力度可调。
     *
     * <p>{@code cleanShare} 是"清掉几成的孤岛"，它<b>直接决定最后落在哪个翻转密度</b>：
     * 先用大预算把细节全捞出来，再用它把密度收回到实机能刷的那条线。
     *
     * <p>选哪部分清由确定性的坐标散列决定，同一张图永远清同一批，结果可复现。
     *
     * @param cleanShare 清掉多少比例的孤岛（0–1，0 等于不清）
     */
    public static byte[] removeIsolatedPoints(byte[] codes, int width, int height, int maxSize, double cleanShare) {
        if (codes == null || width < 1 || height < 1 || codes.length != width * height || maxSize < 1
                || !Double.isFinite(cleanShare) || cleanShare < 0 || cleanShare > 1)
            throw new IllegalArgumentException("无效的去碎块参数");
        byte[] source = codes;
        byte[] result = codes.clone();
        boolean[] visited = new boolean[result.length];
        int[] block = new int[result.length];
        int[] border = new int[4];
        for (int seed = 0; seed < result.length; seed++) {
            if (visited[seed]) continue;
            int color = source[seed];
            int head = 0;
            int tail = 0;
            block[tail++] = seed;
            visited[seed] = true;
            while (head < tail) {
                int index = block[head++];
                int row = index / width;
                int column = index % width;
                if (column > 0 && !visited[index - 1] && source[index - 1] == color) {
                    visited[index - 1] = true;
                    block[tail++] = index - 1;
                }
                if (column + 1 < width && !visited[index + 1] && source[index + 1] == color) {
                    visited[index + 1] = true;
                    block[tail++] = index + 1;
                }
                if (row > 0 && !visited[index - width] && source[index - width] == color) {
                    visited[index - width] = true;
                    block[tail++] = index - width;
                }
                if (row + 1 < height && !visited[index + width] && source[index + width] == color) {
                    visited[index + width] = true;
                    block[tail++] = index + width;
                }
            }
            if (tail > maxSize) continue;
            Arrays.fill(border, 0);
            int total = 0;
            for (int step = 0; step < tail; step++) {
                int index = block[step];
                int row = index / width;
                int column = index % width;
                if (column > 0 && source[index - 1] != color) { border[source[index - 1]]++; total++; }
                if (column + 1 < width && source[index + 1] != color) { border[source[index + 1]]++; total++; }
                if (row > 0 && source[index - width] != color) { border[source[index - width]]++; total++; }
                if (row + 1 < height && source[index + width] != color) { border[source[index + width]]++; total++; }
            }
            if (total == 0) continue;                                   // 整幅只有一个颜色，没得并
            int winner = 0;
            for (int code = 1; code < 4; code++) if (border[code] > border[winner]) winner = code;
            if (border[winner] < total * ISOLATED_CONSENSUS) continue;   // 泡在多色交界里，是真细节
            if (((seed * 73 + seed / width * 31) & 1023) >= cleanShare * 1024) continue;   // 这批不清，留到下一个密度
            for (int step = 0; step < tail; step++) result[block[step]] = (byte) winner;
        }
        return result;
    }

    /**
     * 「细」档：固定预算出图，只在超过已验证上限时才自动往回收。
     *
     * <p>干净人像上它本来就只有 20%，什么都不用做；细节碎成渣的翻拍照片上它能跑到 26%，
     * 那就是刷不动的区间。这里只在越线时才动，所以平常看到的还是原来那个「细」。
     */
    public static byte[] quantizeFine(int[] pixels, int width, int height, int mode,
                                      int brightness, int contrast, double strength) {
        byte[] codes = quantize(pixels, width, height, mode, brightness, contrast, strength, DITHER_BUDGET_FINE);
        if (density(codes, width) <= FINE_CEILING) return codes;
        return quantizeToTarget(pixels, width, height, mode, brightness, contrast, strength, FINE_CEILING);
    }

    /**
     * 按目标翻转密度出图：二分找抖动预算，让密度自己停在线上。
     *
     * <p>这条路是试出来的，前两版都错了，记下来别再走：
     *
     * <ol>
     *   <li><b>写死一个大预算再清孤岛把密度收回去</b>：清孤岛会在笔触上打洞，一条线被啃成好几截，
     *       翻转团数从 1485 涨到 2201、平均团块从 102 掉到 36。密度数字是收下来了，
     *       可点变得更碎，屏反而更难带。</li>
     *   <li><b>干脆把目标密度往下压</b>：治不了碎，只是让画面更差，还比不上「细」。</li>
     * </ol>
     *
     * <p>真正决定"能不能刷"的是翻转点的<b>成团程度</b>，不是数量：同样翻 21% 的点，
     * 「细」是 1454 团 / 每团 77 像素，老「净」是 5181 团 / 每团 24 像素。
     * 而团块大小由抖动预算单调决定（预算越大，抖动区越连片），所以只要二分预算就能
     * 直接落在目标密度上，而且拿到那个密度下最连片的分布。不再清孤岛。
     *
     * @param targetDensity 目标翻转密度（%）
     */
    public static byte[] quantizeToTarget(int[] pixels, int width, int height, int mode,
                                          int brightness, int contrast, double strength, int targetDensity) {
        // 五步二分：预算每变 .03 大约动 1.5 个百分点，够停到 ±1 了
        double low = 0, high = 1;
        byte[] best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int step = 0; step < 5; step++) {
            double budget = (low + high) / 2;
            byte[] codes = quantize(pixels, width, height, mode, brightness, contrast, strength, budget);
            int density = density(codes, width);
            int gap = Math.abs(density - targetDensity);
            if (gap < bestGap) {
                bestGap = gap;
                best = codes;
            }
            if (density > targetDensity) high = budget;
            else low = budget;
        }
        // 目标低到连最小的预算都压不住时，退一步用清孤岛兜底（极端保守设置才走这里）
        if (best != null && density(best, width) > targetDensity)
            return removeIsolatedPoints(best, width, height, ISOLATED_MAX, 1);
        return best;
    }

    /**
     * 翻转密度（%）：相邻像素目标色不同的比例。相邻格数按实际存在的邻居算，
     * 跟 INK 的 PanelEnergy.switchDensity 完全一致。
     */
    public static int density(byte[] codes, int width) {
        if (codes == null || codes.length == 0) throw new IllegalArgumentException("点阵为空");
        if (width < 1 || codes.length % width != 0) throw new IllegalArgumentException("点阵宽度不匹配");
        int height = codes.length / width;
        long different = 0, pairs = 0;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = row * width + column;
                if (column + 1 < width) {
                    pairs++;
                    if (codes[index] != codes[index + 1]) different++;
                }
                if (row + 1 < height) {
                    pairs++;
                    if (codes[index] != codes[index + width]) different++;
                }
            }
        }
        return pairs == 0 ? 0 : (int) Math.round(different * 100.0 / pairs);
    }

    /** 3×3 盒式模糊 → 累加进亮度（给梯度用），再把通道本身做 unsharp 提升。 */
    private static void sharpen(double[] channel, double[] scratch, double[] luminance, int width, int height, double share) {
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
                        total += channel[y * width + x];
                        samples++;
                    }
                }
                scratch[row * width + column] = total / samples;
            }
        }
        for (int index = 0; index < channel.length; index++) {
            luminance[index] += scratch[index] * share;
            channel[index] = clamp(channel[index] + SHARPEN * (channel[index] - scratch[index]));
        }
    }

    private static int[] gradientMap(double[] luminance, int width, int height) {
        int[] result = new int[luminance.length];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int center = row * width + column;
                int left = column > 0 ? center - 1 : center;
                int right = column + 1 < width ? center + 1 : center;
                int up = row > 0 ? center - width : center;
                int down = row + 1 < height ? center + width : center;
                double horizontal = Math.abs(luminance[right] - luminance[left]);
                double vertical = Math.abs(luminance[down] - luminance[up]);
                result[center] = (int) Math.min(255, Math.round(horizontal + vertical));
            }
        }
        return result;
    }

    /** 从高梯度往低梯度收，收到数量不超过预算为止；返回 0 表示整张图都不抖动。 */
    private static int cutoffFor(int[] histogram, int budget) {
        if (budget <= 0) return 0;
        int total = 0;
        for (int value = 255; value >= 1; value--) {
            total += histogram[value];
            if (total > budget) return value + 1;
        }
        return 1;
    }

    /** OKLab 最近邻选色。threshold < 0 表示不做两色投影，直接取最近的那个。 */
    static int choose(double red, double green, double blue, double weight, double threshold) {
        if (weight < .000001)
            return (red * .2126 + green * .7152 + blue * .0722) / 255 >= (threshold < 0 ? .5 : threshold) ? 1 : 0;
        double[] sample = lab((int) Math.round(red), (int) Math.round(green), (int) Math.round(blue));
        int best = 0, second = 1;
        double bestDistance = Double.MAX_VALUE, secondDistance = Double.MAX_VALUE;
        for (int index = 0; index < 4; index++) {
            double lightness = sample[0] - PALETTE[index][0], first = sample[1] - PALETTE[index][1], last = sample[2] - PALETTE[index][2];
            double distance = lightness * lightness + first * first + last * last + (index >= 2 ? (1 - weight) * .25 : 0);
            if (distance < bestDistance) {
                secondDistance = bestDistance;
                second = best;
                bestDistance = distance;
                best = index;
            } else if (distance < secondDistance) {
                secondDistance = distance;
                second = index;
            }
        }
        if (threshold < 0 || bestDistance < .000000001) return best;
        int firstColor = COLORS[best], secondColor = COLORS[second];
        double firstRed = (firstColor >>> 16) & 255, firstGreen = (firstColor >>> 8) & 255, firstBlue = firstColor & 255;
        double deltaRed = ((secondColor >>> 16) & 255) - firstRed;
        double deltaGreen = ((secondColor >>> 8) & 255) - firstGreen;
        double deltaBlue = (secondColor & 255) - firstBlue;
        double projection = ((red - firstRed) * deltaRed * .2126 + (green - firstGreen) * deltaGreen * .7152
                + (blue - firstBlue) * deltaBlue * .0722)
                / (deltaRed * deltaRed * .2126 + deltaGreen * deltaGreen * .7152 + deltaBlue * deltaBlue * .0722);
        return projection > threshold ? second : best;
    }

    static double[] lab(int red, int green, int blue) {
        double first = Math.cbrt(.4122214708 * LINEAR[red] + .5363325363 * LINEAR[green] + .0514459929 * LINEAR[blue]);
        double second = Math.cbrt(.2119034982 * LINEAR[red] + .6806995451 * LINEAR[green] + .1073969566 * LINEAR[blue]);
        double third = Math.cbrt(.0883024619 * LINEAR[red] + .2817188376 * LINEAR[green] + .6299787005 * LINEAR[blue]);
        return new double[]{.2104542553 * first + .793617785 * second - .0040720468 * third,
                1.9779984951 * first - 2.428592205 * second + .4505937099 * third,
                .0259040371 * first + .7827717662 * second - .808675766 * third};
    }

    static double smooth(double low, double high, double value) {
        double position = Math.max(0, Math.min(1, (value - low) / (high - low)));
        return position * position * (3 - 2 * position);
    }

    private static double clamp(double value) { return Math.max(0, Math.min(255, value)); }
}
