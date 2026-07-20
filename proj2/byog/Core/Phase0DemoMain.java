package byog.Core;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * 供 run-phase0-demo.bat 调用的命令行演示入口。
 * 它执行 reading 中的 1 + 11 tick 示例，并分别打印 trace 与最终状态。
 */
public final class Phase0DemoMain {

    private Phase0DemoMain() { }

    public static void main(String[] args) {
        Phase0EncounterHarness harness =
                Phase0EncounterHarness.baselineTwoGuardsV1();

        /*
         * 旧 Brain/Planner 会向 stdout 打印大量 DEBUG/INFO。演示期间暂时屏蔽这些
         * 自由文本，只保留下面的结构化结果；这不会关闭 AgentTrace 记录。
         */
        PrintStream originalOut = System.out;
        PrintStream quietOut = new PrintStream(OutputStream.nullOutputStream());
        try {
            System.setOut(quietOut);
            harness.step();       // 运行 tick 0
            harness.runTicks(11); // 再运行 11 tick，总计 12
        } finally {
            System.setOut(originalOut);
            quietOut.close();
        }

        String traceJson = harness.canonicalTraceJson();
        String finalState = harness.canonicalState();

        System.out.println("=== TRACE JSON (12 ticks) ===");
        System.out.println(traceJson);
        System.out.println();
        System.out.println("=== FINAL STATE (12 ticks) ===");
        System.out.println(finalState);
    }
}
