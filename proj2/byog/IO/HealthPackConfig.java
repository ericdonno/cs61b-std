package byog.IO;

/**
 * 苹果血包生成配置。字段语义见 health-pack 需求：目标数量在
 * [minCount, maxCount] 闭区间内均匀选取，healAmount 必须为正。
 */
public record HealthPackConfig(int minCount, int maxCount, int healAmount) {

    /** 配置非法时的安全默认值（Balanced 档）。 */
    public static final HealthPackConfig SAFE_DEFAULT =
            new HealthPackConfig(2, 4, 20);

    public HealthPackConfig {
        if (minCount < 0) {
            throw new IllegalArgumentException("minCount must be >= 0");
        }
        if (maxCount < minCount) {
            throw new IllegalArgumentException(
                    "maxCount must be >= minCount");
        }
        if (healAmount <= 0) {
            throw new IllegalArgumentException("healAmount must be > 0");
        }
    }
}
