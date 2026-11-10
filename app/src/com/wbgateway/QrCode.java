package com.wbgateway;

/**
 * 二维码编码器（纯 Java，零 Android 依赖）。
 *
 * <p>为什么自己写：构建走「SDK 工具链直编」，不引 Gradle，所以拿不到 ZXing
 * 那种 AAR；而 Raccoon 的扫码登录必须把二维码**画在屏幕上**让手机扫
 * （它的 `scan_path` 是要被扫的那张图，不是服务端渲染好的图片）。
 *
 * <p>实现范围刻意收窄到「够用且可验证」：
 * <ul>
 *   <li>只做 <b>字节模式</b>（UTF-8）与 <b>纠错等级 L/M/Q/H</b>；</li>
 *   <li>自动选版本 1–40 中**最小可用**的那个；</li>
 *   <li>掩码按规范算出 8 个候选并取**罚分最低**的（不是固定掩码）；</li>
 *   <li>不写结构化追加、不写 ECI —— 登录二维码是纯 ASCII URL，用不到。</li>
 * </ul>
 *
 * <p>正确性验证：`verify/VerifyQr.java` 把本类输出的模块矩阵与参考实现
 * （Node 的 `qrcode` 包）逐位比对，覆盖多个长度的输入与全部纠错等级。
 */
public final class QrCode {

    /** 纠错等级。 */
    /**
     * 纠错等级。
     *
     * <p>⚠ 这里刻意**不用 `enum`**：javac 21 会为每个枚举生成一个空的合成类
     * （`QrCode$1.class`），而 build-tools 34 的 d8 处理它会直接崩
     * （`NullPointerException: String.length()`）。那个报错与「源码里有匿名类」
     * 一模一样，实际根因却是「用了 enum」—— 查一次要花很久。
     * 用 static 常量 + 私有构造的普通类，行为一样，且没有任何合成产物。
     */
    public static final class Ecc {
        /** 约 7% 可恢复。 */
        public static final Ecc L = new Ecc("L", 1, 0);
        /** 约 15%。 */
        public static final Ecc M = new Ecc("M", 0, 1);
        /** 约 25%。 */
        public static final Ecc Q = new Ecc("Q", 3, 2);
        /** 约 30%。 */
        public static final Ecc H = new Ecc("H", 2, 3);

        /** 名称（L / M / Q / H）。 */
        public final String name;
        /**
         * 纠错等级在**格式信息**里的 2 位编码值（L=01 / M=00 / Q=11 / H=10）。
         *
         * <p>⚠ 与下面 `tableRow` 的顺序**不同**：格式位按 L,M,Q,H = 01,00,11,10 编，
         * 而纠错表按 L,M,Q,H 的行序 0,1,2,3 排。两个值必须分开记 —— 曾经把
         * M 的格式位当成行号用，结果 v1-L 查出 16 个数据码字（正确是 19），
         * 二维码看着正常但扫出来是乱码。
         */
        public final int formatBits;
        /** 该等级在「每块纠错码字数」表里的行号（L=0 / M=1 / Q=2 / H=3）。 */
        public final int tableRow;

        private Ecc(String name, int formatBits, int tableRow) {
            this.name = name;
            this.formatBits = formatBits;
            this.tableRow = tableRow;
        }

        /** 按名称取等级（L / M / Q / H，大小写不敏感）。 */
        public static Ecc valueOf(String name) {
            if (name == null) return M;
            String n = name.trim();
            if ("L".equalsIgnoreCase(n)) return L;
            if ("M".equalsIgnoreCase(n)) return M;
            if ("Q".equalsIgnoreCase(n)) return Q;
            if ("H".equalsIgnoreCase(n)) return H;
            throw new IllegalArgumentException("未知纠错等级：" + name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** 结果矩阵：`modules[y][x]` 为 true 表示该模块是黑的。 */
    private final boolean[][] modules;
    private final int size;
    private final int version;
    /** 选中的掩码号（0–7），仅用于诊断与对账。 */
    private final int mask;

    private QrCode(boolean[][] modules, int version, int mask) {
        this.modules = modules;
        this.size = modules.length;
        this.version = version;
        this.mask = mask;
    }

    /** 选中的掩码号（0–7）。 */
    public int mask() {
        return mask;
    }

    /** 边长（模块数）。 */
    public int size() {
        return size;
    }

    /** 版本号（1–40）。 */
    public int version() {
        return version;
    }

    /** 该坐标是否为黑模块。 */
    public boolean isDark(int x, int y) {
        return modules[y][x];
    }

    /**
     * 编码一段文本。
     *
     * @param text 要编码的内容（按 UTF-8 取字节）
     * @param ecc  纠错等级
     * @return 编码结果
     * @throws IllegalArgumentException 内容过长（超出 40 版容量）
     */
    public static QrCode encode(String text, Ecc ecc) {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return encodeBytes(data, ecc);
    }

    /** 编码一段字节。 */
    public static QrCode encodeBytes(byte[] data, Ecc ecc) {
        int version = pickVersion(data.length, ecc);
        if (version < 0) throw new IllegalArgumentException("内容过长，无法编码进二维码（" + data.length + " 字节）");

        BitBuffer bits = new BitBuffer();
        // 字节模式指示符 = 0100
        bits.append(0b0100, 4);
        // 字符计数：版本 1–9 用 8 位，10–26 用 16 位，27–40 用 16 位
        bits.append(data.length, version <= 9 ? 8 : 16);
        for (byte b : data) bits.append(b & 0xFF, 8);

        // ⚠ 容量必须用「**数据码字数**」而不是「总码字数」。
        //
        // 总码字数包含纠错区（约占 1/4 到 1/3），拿它当填充上界会多填几十个字节，
        // 而多出来的部分会被 `addEccAndInterleave` 当成数据参与分块 —— 结果矩阵
        // 依然「像二维码」，但数据段整体错位、扫出来是乱码。
        // 这个错（和交织那两个坑一样）没有任何运行时反馈，只能靠参考实现对账发现。
        int dataCapacityBits = dataCodewords(version, ecc) * 8;
        // 终止符最多 4 个 0
        bits.append(0, Math.min(4, Math.max(0, dataCapacityBits - bits.length())));
        // 补到字节边界
        bits.append(0, (8 - bits.length() % 8) % 8);
        // 填充字节 0xEC / 0x11 交替
        for (int pad = 0xEC; bits.length() < dataCapacityBits; pad ^= 0xEC ^ 0x11) {
            bits.append(pad, 8);
        }

        boolean[][] matrix = buildMatrix(version, ecc, bits.toBytes());
        return new QrCode(matrix, version, lastPickedMask);
    }

    /** `buildMatrix` 选定的掩码号（内部传递用）。 */
    private static int lastPickedMask = 0;

    // -----------------------------------------------------------------------
    // 容量与版本选择
    // -----------------------------------------------------------------------

    /**
     * 版本 1–40 各纠错等级的「**每块**纠错码字数」；下标 0 是占位（无版本 0）。
     *
     * <p>⚠ 这张表容易抄错成「**总**纠错码字数」—— 上游参考实现（Node 的 `qrcode`）
     * 里那张 `EC_CODEWORDS_TABLE` 存的就是**总量**，直接照抄会让每个版本多减
     * 纠错区，v3 起容量全错（v1 恰好因为块数=1 而看不出问题）。
     * 这里存的是「总量 ÷ 块数」，两表配合才等于原始总量。
     */
    private static final int[][] ECC_CODEWORDS_PER_BLOCK = {
            {-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},
            {-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
            {-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},
    };

    /** 版本 1–40 各纠错等级的「纠错块个数」。 */
    private static final int[][] NUM_ERROR_CORRECTION_BLOCKS = {
            {-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},
            {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},
            {-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},
            {-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},
    };

    /** 该版本除功能图形外可放数据的模块数。 */
    static int numRawDataModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int numAlign = version / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (version >= 7) result -= 36;
        }
        return result;
    }

    /** 该版本 + 等级下，「数据码字数」上限。 */
    static int dataCodewords(int version, Ecc ecc) {
        int eccPerBlock = ECC_CODEWORDS_PER_BLOCK[ecc.tableRow][version];
        int blocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.tableRow][version];
        return numRawDataModules(version) / 8 - eccPerBlock * blocks;
    }

    /** 找到能放下 `byteLen` 字节的最小版本；放不下返回 -1。
     *
     * <p>判据是「总位数放得下」，不能用整除取整后再比 —— 版本 10 起字符计数
     * 从 8 位变成 16 位，多出的 8 位会把临界值整体的容量压掉一个字节，
     * 用整除写就会挑小一档（矩阵照出来是二维码，但内容被截断）。
     */
    private static int pickVersion(int byteLen, Ecc ecc) {
        for (int version = 1; version <= 40; version += 1) {
            int capacityBits = dataCodewords(version, ecc) * 8;
            // 模式指示符 4 位 + 字符计数（版本 ≤9 为 8 位，否则 16 位）
            int headerBits = 4 + (version <= 9 ? 8 : 16);
            if (headerBits + byteLen * 8 <= capacityBits) return version;
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // 矩阵构建
    // -----------------------------------------------------------------------

    private static boolean[][] buildMatrix(int version, Ecc ecc, byte[] dataCodewords) {
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] isFunction = new boolean[size][size];

        drawFunctionPatterns(modules, isFunction, version);

        // 加入纠错码并交织
        byte[] allCodewords = addEccAndInterleave(dataCodewords, version, ecc);
        drawCodewords(modules, isFunction, allCodewords);

        // 选掩码：8 个候选里罚分最低的
        int bestMask = 0;
        int minPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask += 1) {
            applyMask(modules, isFunction, mask);
            drawFormatBits(modules, isFunction, ecc, mask);
            int penalty = penaltyScore(modules);
            if (penalty < minPenalty) {
                minPenalty = penalty;
                bestMask = mask;
            }
            applyMask(modules, isFunction, mask); // 异或两次 = 复位
        }
        applyMask(modules, isFunction, bestMask);
        drawFormatBits(modules, isFunction, ecc, bestMask);
        lastPickedMask = bestMask;

        return modules;
    }

    /** 画功能图形：定位、分隔、定时、对齐、暗模块。 */
    private static void drawFunctionPatterns(boolean[][] m, boolean[][] fn, int version) {
        int size = m.length;

        // 定时图形（第 6 行 / 第 6 列）
        for (int i = 0; i < size; i += 1) {
            setFunction(m, fn, 6, i, i % 2 == 0);
            setFunction(m, fn, i, 6, i % 2 == 0);
        }

        // 三个定位图形（含分隔带）
        drawFinder(m, fn, 3, 3);
        drawFinder(m, fn, size - 4, 3);
        drawFinder(m, fn, 3, size - 4);

        // 对齐图形
        int[] alignPos = alignmentPatternPositions(version);
        int n = alignPos.length;
        for (int i = 0; i < n; i += 1) {
            for (int j = 0; j < n; j += 1) {
                // 跳过与定位图形重叠的三个角
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                drawAlignment(m, fn, alignPos[i], alignPos[j]);
            }
        }

        // 预留格式信息区（内容稍后按掩码写入）
        drawFormatBits(m, fn, Ecc.L, 0);

        // 版本信息（版本 ≥7）
        if (version >= 7) drawVersionBits(m, fn, version);

        // 右下暗模块
        setFunction(m, fn, 8, size - 8, true);
    }

    /** 定位图形：7×7 靶心 + 一圈白色分隔带。 */
    private static void drawFinder(boolean[][] m, boolean[][] fn, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy += 1) {
            for (int dx = -4; dx <= 4; dx += 1) {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || x >= m.length || y < 0 || y >= m.length) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunction(m, fn, x, y, dist != 2 && dist != 4);
            }
        }
    }

    /** 对齐图形：5×5 靶心。 */
    private static void drawAlignment(boolean[][] m, boolean[][] fn, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy += 1) {
            for (int dx = -2; dx <= 2; dx += 1) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFunction(m, fn, cx + dx, cy + dy, dist != 1);
            }
        }
    }

    /** 对齐图形中心坐标表。 */
    static int[] alignmentPatternPositions(int version) {
        if (version == 1) return new int[0];
        int numAlign = version / 7 + 2;
        int step = (version == 32) ? 26 : (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = numAlign - 1, pos = version * 4 + 10; i >= 1; i -= 1, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    /** 版本信息（18 位，版本 ≥7 时写在右上与左下）。 */
    private static void drawVersionBits(boolean[][] m, boolean[][] fn, int version) {
        int rem = version;
        for (int i = 0; i < 12; i += 1) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;

        for (int i = 0; i < 18; i += 1) {
            boolean bit = ((bits >>> i) & 1) != 0;
            int a = m.length - 11 + i % 3;
            int b = i / 3;
            setFunction(m, fn, a, b, bit);
            setFunction(m, fn, b, a, bit);
        }
    }

    /** 格式信息：15 位 BCH，写在左上、右上、左下三处。 */
    private static void drawFormatBits(boolean[][] m, boolean[][] fn, Ecc ecc, int mask) {
        int data = ecc.formatBits << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i += 1) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;

        int size = m.length;

        // 左上横向
        for (int i = 0; i <= 5; i += 1) setFunction(m, fn, 8, i, bit(bits, i));
        setFunction(m, fn, 8, 7, bit(bits, 6));
        setFunction(m, fn, 8, 8, bit(bits, 7));
        setFunction(m, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i += 1) setFunction(m, fn, 14 - i, 8, bit(bits, i));

        // 右上纵向
        for (int i = 0; i < 8; i += 1) setFunction(m, fn, size - 1 - i, 8, bit(bits, i));
        // 左下纵向
        for (int i = 8; i < 15; i += 1) setFunction(m, fn, 8, size - 15 + i, bit(bits, i));

        // 暗模块
        setFunction(m, fn, 8, size - 8, true);
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    private static void setFunction(boolean[][] m, boolean[][] fn, int x, int y, boolean dark) {
        m[y][x] = dark;
        fn[y][x] = true;
    }

    /** 把数据码字按之字形填进非功能区。 */
    private static void drawCodewords(boolean[][] m, boolean[][] fn, byte[] data) {
        int size = m.length;
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5; // 跳过定时列
            for (int vert = 0; vert < size; vert += 1) {
                for (int j = 0; j < 2; j += 1) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (fn[y][x] || i >= data.length * 8) continue;
                    m[y][x] = bitAt(data, i);
                    i += 1;
                }
            }
        }
    }

    private static boolean bitAt(byte[] data, int index) {
        return ((data[index >>> 3] >>> (7 - (index & 7))) & 1) != 0;
    }

    /** 应用 / 取消掩码（异或，作用是自反的）。 */
    private static void applyMask(boolean[][] m, boolean[][] fn, int mask) {
        int size = m.length;
        for (int y = 0; y < size; y += 1) {
            for (int x = 0; x < size; x += 1) {
                if (fn[y][x]) continue;
                // ⚠ 这里刻意用 if-else 而不是 switch：
                // 对 int 做 switch 时 javac 会生成 `QrCode$1` 这类**合成类**，
                // 而 build-tools 34 的 d8 遇到它直接崩（NPE: String.length()）——
                // 就是 README 里记的那个「源码里不能有匿名/合成类」的老坑。
                boolean invert;
                if (mask == 0) invert = (x + y) % 2 == 0;
                else if (mask == 1) invert = y % 2 == 0;
                else if (mask == 2) invert = x % 3 == 0;
                else if (mask == 3) invert = (x + y) % 3 == 0;
                else if (mask == 4) invert = (x / 3 + y / 2) % 2 == 0;
                else if (mask == 5) invert = x * y % 2 + x * y % 3 == 0;
                else if (mask == 6) invert = (x * y % 2 + x * y % 3) % 2 == 0;
                else if (mask == 7) invert = ((x + y) % 2 + x * y % 3) % 2 == 0;
                else invert = false;
                if (invert) m[y][x] = !m[y][x];
            }
        }
    }

    // -----------------------------------------------------------------------
    // 罚分
    // -----------------------------------------------------------------------

    /** 按规范算 4 项罚分，用来挑最不「像二维码特征」的掩码。 */
    private static int penaltyScore(boolean[][] m) {
        int size = m.length;
        int result = 0;

        // 规则 1：同色连续 ≥5
        for (int y = 0; y < size; y += 1) {
            int run = 1;
            for (int x = 1; x < size; x += 1) {
                if (m[y][x] == m[y][x - 1]) {
                    run += 1;
                    if (run == 5) result += 3;
                    else if (run > 5) result += 1;
                } else {
                    run = 1;
                }
            }
        }
        for (int x = 0; x < size; x += 1) {
            int run = 1;
            for (int y = 1; y < size; y += 1) {
                if (m[y][x] == m[y - 1][x]) {
                    run += 1;
                    if (run == 5) result += 3;
                    else if (run > 5) result += 1;
                } else {
                    run = 1;
                }
            }
        }

        // 规则 2：2×2 同色块
        for (int y = 0; y < size - 1; y += 1) {
            for (int x = 0; x < size - 1; x += 1) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3;
            }
        }

        // 规则 3：类似定位图形的 1:1:3:1:1 图案（前后各允许 4 个浅色）
        //
        // 规范写的是「后面跟 4 个浅色」或「前面有 4 个浅色」，两种朝向都算。
        // 实现上用 11 位窗口直接比对两种固定位型（0x5D0 / 0x05D），
        // 与参考实现同一判据 —— 用「7 位图案 + 边界特判」很容易漏掉贴边的情况。
        for (int y = 0; y < size; y += 1) {
            int bitsCol = 0;
            int bitsRow = 0;
            for (int x = 0; x < size; x += 1) {
                bitsCol = ((bitsCol << 1) & 0x7FF) | (m[y][x] ? 1 : 0);
                if (x >= 10 && (bitsCol == 0x5D0 || bitsCol == 0x05D)) result += 40;

                bitsRow = ((bitsRow << 1) & 0x7FF) | (m[x][y] ? 1 : 0);
                if (x >= 10 && (bitsRow == 0x5D0 || bitsRow == 0x05D)) result += 40;
            }
        }

        // 规则 4：黑模块占比偏离 50%（按 5% 一档计）
        int dark = 0;
        for (boolean[] row : m) {
            for (boolean cell : row) if (cell) dark += 1;
        }
        int total = size * size;
        int k = Math.abs((int) Math.ceil(dark * 100.0 / total / 5.0) - 10);
        result += k * 10;

        return result;
    }

    // -----------------------------------------------------------------------
    // 纠错（Reed–Solomon）
    // -----------------------------------------------------------------------

    /** 加入纠错码并按规范交织，返回最终码字序列。 */
    private static byte[] addEccAndInterleave(byte[] data, int version, Ecc ecc) {
        int numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.tableRow][version];
        int blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecc.tableRow][version];
        int rawCodewords = numRawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] divisor = reedSolomonDivisor(blockEccLen);
        int dataOffset = 0;
        for (int i = 0; i < numBlocks; i += 1) {
            int dataLen = shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1);
            byte[] block = new byte[dataLen + blockEccLen];
            System.arraycopy(data, dataOffset, block, 0, dataLen);
            dataOffset += dataLen;
            byte[] eccBytes = reedSolomonRemainder(block, divisor, dataLen);
            System.arraycopy(eccBytes, 0, block, dataLen, eccBytes.length);
            blocks[i] = block;
        }

        // 交织：先按「数据码字」逐列取，再按「纠错码字」逐列取。
        //
        // ⚠ 两个坑都在这几行里，改之前先看清楚：
        //   ① 短块比长块少**一个数据码字**，所以数据段最后一列只有长块参与；
        //   ② 长块的纠错码从 `D + 1` 开始（D = 短块数据长度），不是 `D` ——
        //      写错会整体错位一个字节，矩阵仍然「像二维码」但扫不出来。
        //
        // 这些都没有中间反馈：编错了只会得到一张扫不出来的图，肉眼无法分辨。
        // 所以 `verify/VerifyQr.java` 才会拿参考实现逐模块比对。
        byte[] result = new byte[rawCodewords];
        int out = 0;
        int shortDataLen = shortBlockLen - blockEccLen;

        // 数据段：0 .. shortDataLen（含），最后一列只有长块有
        for (int i = 0; i <= shortDataLen; i += 1) {
            for (int j = 0; j < numBlocks; j += 1) {
                boolean isShort = j < numShortBlocks;
                if (isShort && i == shortDataLen) continue;
                result[out] = blocks[j][i];
                out += 1;
            }
        }
        // 纠错段：每块 blockEccLen 个，长块的起点偏移 1
        for (int i = 0; i < blockEccLen; i += 1) {
            for (int j = 0; j < numBlocks; j += 1) {
                boolean isShort = j < numShortBlocks;
                int eccStart = shortDataLen + (isShort ? 0 : 1);
                result[out] = blocks[j][eccStart + i];
                out += 1;
            }
        }
        return result;
    }

    /** 生成 Reed–Solomon 生成多项式的系数（最高次项省略）。 */
    private static byte[] reedSolomonDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i += 1) {
            for (int j = 0; j < result.length; j += 1) {
                result[j] = (byte) gfMultiply(result[j] & 0xFF, root);
                if (j + 1 < result.length) result[j] ^= result[j + 1];
            }
            root = gfMultiply(root, 0x02);
        }
        return result;
    }

    /** 算一个块的纠错码字（多项式除法取余）。 */
    private static byte[] reedSolomonRemainder(byte[] block, byte[] divisor, int dataLen) {
        byte[] result = new byte[divisor.length];
        for (int i = 0; i < dataLen; i += 1) {
            int factor = (block[i] ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int j = 0; j < result.length; j += 1) {
                result[j] ^= (byte) gfMultiply(divisor[j] & 0xFF, factor);
            }
        }
        return result;
    }

    /** GF(256) 乘法，本原多项式 0x11D。 */
    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i -= 1) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    // -----------------------------------------------------------------------
    // 位缓冲
    // -----------------------------------------------------------------------

    /** 按位追加的缓冲区。 */
    private static final class BitBuffer {
        private byte[] data = new byte[64];
        private int bitLength = 0;

        int length() {
            return bitLength;
        }

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i -= 1) {
                appendBit(((value >>> i) & 1) != 0);
            }
        }

        private void appendBit(boolean dark) {
            int byteIndex = bitLength >>> 3;
            if (byteIndex >= data.length) {
                byte[] next = new byte[data.length * 2];
                System.arraycopy(data, 0, next, 0, data.length);
                data = next;
            }
            if (dark) data[byteIndex] |= (byte) (0x80 >>> (bitLength & 7));
            bitLength += 1;
        }

        byte[] toBytes() {
            int len = (bitLength + 7) >>> 3;
            byte[] out = new byte[len];
            System.arraycopy(data, 0, out, 0, len);
            return out;
        }
    }
}
