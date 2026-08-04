package byog.IO;

/**
 * 世界列表中的一个条目：可读摘要或坏档路径。单个坏文件不阻断其他世界。
 */
public record WorldSaveEntry(WorldSaveSummary summary, String failurePath) {

    /** 可读条目。 */
    public static WorldSaveEntry readable(WorldSaveSummary summary) {
        return new WorldSaveEntry(summary, null);
    }

    /** 不可读条目；failurePath 保留文件位置用于诊断，不自动删除。 */
    public static WorldSaveEntry unreadable(String failurePath) {
        return new WorldSaveEntry(null, failurePath);
    }

    public boolean isReadable() {
        return summary != null;
    }
}
