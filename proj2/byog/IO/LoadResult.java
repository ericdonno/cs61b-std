package byog.IO;

/** 读取结果：成功携带完整存档数据，失败携带稳定原因。 */
public record LoadResult(boolean success, GameSaveData data, String failureReason) {

    public static LoadResult ok(GameSaveData data) {
        return new LoadResult(true, data, null);
    }

    public static LoadResult failure(String failureReason) {
        return new LoadResult(false, null, failureReason);
    }
}
