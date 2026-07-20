package byog.Core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Phase 0 golden baseline 的显式生成入口。
 *
 * <p>普通 JUnit 测试只能读取标准答案，不能在失败时自动更新它。只有人工运行本类并
 * 显式传入 {@code --write <path>} 时才允许写文件；没有 --write 时只打印刚运行得到的
 * canonical JSON，供开发者审查。</p>
 *
 * <p>更新 baseline 属于受控操作：调用者应确认行为变化是有意的，检查 JSON diff，
 * 必要时提升 scenarioVersion。当前旧 Logger 也会写 stdout，导致无参数输出前夹杂
 * DEBUG/INFO 文本；这是已知机器输出契约问题，不能把整段 stdout 直接当作纯 JSON。</p>
 */
public final class Phase0BaselineMain {

    private Phase0BaselineMain() { }

    /**
     * 运行固定遭遇 12 tick，并打印或显式写出 baseline。
     * 未识别参数当前被忽略；单独出现且没有路径的 --write 不会写文件。
     */
    public static void main(String[] args) throws IOException {
        String writePath = null;
        // 写入能力必须由明确的“--write 后跟路径”参数开启，默认保持只读/打印模式。
        for (int i = 0; i < args.length; i++) {
            if ("--write".equals(args[i]) && i + 1 < args.length) {
                writePath = args[++i];
            }
        }

        // baseline 内容必须来自真实 Harness 路径，不能在生成器中另写一套模拟结果。
        Phase0EncounterHarness harness = Phase0EncounterHarness.baselineTwoGuardsV1();
        harness.runTicks(12);

        String json = harness.buildBaselineJson();

        if (writePath != null) {
            Path path = Paths.get(writePath);
            Path parent = path.getParent();
            if (parent != null) {
                // 只为调用者明确指定的目标创建父目录，不触碰默认存档目录。
                Files.createDirectories(parent);
            }
            Files.writeString(path, json, StandardCharsets.UTF_8);
            System.out.println("Baseline written to: " + path.toAbsolutePath());
        } else {
            System.out.println(json);
        }
    }
}
