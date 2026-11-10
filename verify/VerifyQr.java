import com.wbgateway.QrCode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 二维码编码器验证：把 Java 侧矩阵与参考实现逐位比对。
 *
 * <p>为什么值得单独验：二维码是**没有中间反馈**的格式 —— 编错了仍然是一张
 * 看着像二维码的图，只有真拿手机扫才发现扫不出来，而这时用户已经卡在
 * 「Raccoon 登录」这一步了。所以这里拿 Node 的 `qrcode` 包（同一份输入）
 * 生成参考矩阵，逐模块对比。
 *
 * <p>用法：由 `verify/run-qr-verify.mjs` 驱动 —— 它先算出参考矩阵喂给 stdin，
 * 再比对本程序 stdout。
 */
public class VerifyQr {

    public static void main(String[] args) throws Exception {
        // stdin 每行：<ecc> <期望版本> <矩阵边长> <矩阵行...> <文本 URL 编码>
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        int pass = 0;
        int fail = 0;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\t");
            if (parts.length < 5) continue;
            String eccName = parts[0];
            int expectVersion = Integer.parseInt(parts[1]);
            int expectSize = Integer.parseInt(parts[2]);
            String expectRows = parts[3];
            String text = java.net.URLDecoder.decode(parts[4], "UTF-8");

            QrCode.Ecc ecc = QrCode.Ecc.valueOf(eccName);
            QrCode qr = QrCode.encode(text, ecc);

            List<String> problems = new ArrayList<>();
            if (qr.version() != expectVersion) {
                problems.add("版本 " + qr.version() + " ≠ " + expectVersion);
            }
            if (qr.size() != expectSize) {
                problems.add("边长 " + qr.size() + " ≠ " + expectSize);
            }
            String[] expected = expectRows.split(",");
            if (expected.length == qr.size()) {
                for (int y = 0; y < qr.size(); y += 1) {
                    if (expected[y].length() != qr.size()) {
                        problems.add("第 " + y + " 行长度不符");
                        break;
                    }
                    for (int x = 0; x < qr.size(); x += 1) {
                        boolean want = expected[y].charAt(x) == '1';
                        if (qr.isDark(x, y) != want) {
                            problems.add("模块 (" + x + "," + y + ") 期望 " + (want ? "黑" : "白")
                                    + " 实际 " + (qr.isDark(x, y) ? "黑" : "白"));
                            break;
                        }
                    }
                    if (!problems.isEmpty()) break;
                }
            } else {
                problems.add("参考矩阵行数 " + expected.length + " ≠ " + qr.size());
            }

            String label = eccName + " v" + expectVersion + " " + qr.size() + "×" + qr.size()
                    + " 「" + (text.length() > 46 ? text.substring(0, 46) + "…" : text) + "」";
            if (problems.isEmpty()) {
                pass += 1;
                System.out.println("PASS " + label);
            } else {
                fail += 1;
                System.out.println("FAIL " + label + " —— " + String.join("; ", problems));
            }
        }
        System.out.println();
        System.out.println("二维码编码器：" + pass + " PASS / " + fail + " FAIL");
        if (fail > 0) System.exit(1);
    }
}
