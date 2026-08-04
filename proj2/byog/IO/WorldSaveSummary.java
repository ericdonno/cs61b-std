package byog.IO;

import java.io.Serializable;

/**
 * 世界列表展示摘要。只读、不可变，来自存档文件头；不持有 live Game 对象。
 */
public record WorldSaveSummary(
        String worldId,
        String worldName,
        int floorLevel,
        int playerHp,
        String difficulty,
        long savedAtEpochMillis
) implements Serializable {
    private static final long serialVersionUID = 1L;

    public WorldSaveSummary {
        if (worldId == null || worldId.trim().isEmpty()) {
            throw new IllegalArgumentException("worldId must not be blank");
        }
        if (worldName == null || worldName.trim().isEmpty()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
    }
}
