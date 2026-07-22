package byog.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 生成 Phase 1 私有感知的 golden baseline JSON 到指定位置。
 *
 * <p>用法: java byog.Test.Phase1BaselineMain [--write &lt;path&gt;]
 * 不加 --write 只打印到 stdout。</p>
 */
public class Phase1BaselineMain {
    public static void main(String[] args) throws IOException {
        Path writePath = null;
        for (int i = 0; i < args.length; i++) {
            if ("--write".equals(args[i]) && i + 1 < args.length) {
                writePath = Paths.get(args[i + 1]);
                break;
            }
        }

        Phase1EncounterHarness harness = Phase1EncounterHarness.baselineTwoGuardsV1();
        harness.runTicks(12);

        String json = harness.buildBaselineJson();

        if (writePath != null) {
            Files.createDirectories(writePath.getParent());
            Files.writeString(writePath, json);
            System.out.println("Baseline written to: " + writePath.toAbsolutePath());
        } else {
            System.out.println(json);
        }
    }
}
