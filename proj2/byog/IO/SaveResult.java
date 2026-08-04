package byog.IO;

/**
 * 保存结果：成功携带 worldId；失败携带稳定原因。
 * warning 只在新档已落盘但旧档清理失败时填充，不影响成功状态。
 */
public record SaveResult(
        boolean success, String worldId, String failureReason,
        String warning) {

    public static SaveResult ok(String worldId) {
        return new SaveResult(true, worldId, null, null);
    }

    public static SaveResult okWithWarning(String worldId, String warning) {
        return new SaveResult(true, worldId, null, warning);
    }

    public static SaveResult failure(String failureReason) {
        return new SaveResult(false, null, failureReason, null);
    }
}
