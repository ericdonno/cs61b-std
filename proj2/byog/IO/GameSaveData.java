package byog.IO;

import byog.Common.Facing;
import byog.Common.VisionMode;
import byog.lab5.Position;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 命名世界的类型化存档快照。不再使用开放式 {@code extraData} 字符串协议。
 *
 * <p>{@code savedAtEpochMillis} 只用于展示；{@code replacesWorldId} 只用于
 * 未完成的覆盖清理。加载时通过 {@link #validate()} 做完整校验，任一必填字段、
 * 范围、重复身份或越界位置不合格都会拒绝整个世界。</p>
 */
public final class GameSaveData implements Serializable {
    private static final long serialVersionUID = 20260802L;

    private String worldId;
    private String worldName;
    private String replacesWorldId;
    private String seed;
    private int floorLevel;
    private String difficulty;
    private long savedAtEpochMillis;
    private String visionMode;
    private int runCurrentHp;
    private int floorPlayerX;
    private int floorPlayerY;
    private int floorPlayerCharge;
    private int stairsX;
    private int stairsY;
    private List<HealthPackPosition> healthPacks;
    private List<EnemySaveData> enemyStates;

    public GameSaveData() {
        healthPacks = new ArrayList<>();
        enemyStates = new ArrayList<>();
    }

    // ---- getters / setters ----

    public String getWorldId() {
        return worldId;
    }

    public void setWorldId(String worldId) {
        this.worldId = worldId;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public String getReplacesWorldId() {
        return replacesWorldId;
    }

    public void setReplacesWorldId(String replacesWorldId) {
        this.replacesWorldId = replacesWorldId;
    }

    public String getSeed() {
        return seed;
    }

    public void setSeed(String seed) {
        this.seed = seed;
    }

    public int getFloorLevel() {
        return floorLevel;
    }

    public void setFloorLevel(int floorLevel) {
        this.floorLevel = floorLevel;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public long getSavedAtEpochMillis() {
        return savedAtEpochMillis;
    }

    public void setSavedAtEpochMillis(long savedAtEpochMillis) {
        this.savedAtEpochMillis = savedAtEpochMillis;
    }

    public String getVisionMode() {
        return visionMode;
    }

    public void setVisionMode(String visionMode) {
        this.visionMode = visionMode;
    }

    public void setVisionMode(VisionMode visionMode) {
        this.visionMode = visionMode == null ? null : visionMode.name();
    }

    public int getRunCurrentHp() {
        return runCurrentHp;
    }

    public void setRunCurrentHp(int runCurrentHp) {
        this.runCurrentHp = runCurrentHp;
    }

    public Position getFloorPlayerPosition() {
        return new Position(floorPlayerX, floorPlayerY);
    }

    public void setFloorPlayerPosition(Position position) {
        this.floorPlayerX = position.x;
        this.floorPlayerY = position.y;
    }

    public int getFloorPlayerCharge() {
        return floorPlayerCharge;
    }

    public void setFloorPlayerCharge(int floorPlayerCharge) {
        this.floorPlayerCharge = floorPlayerCharge;
    }

    public Position getStairsPosition() {
        return new Position(stairsX, stairsY);
    }

    public void setStairsPosition(Position position) {
        this.stairsX = position.x;
        this.stairsY = position.y;
    }

    public List<HealthPackPosition> getHealthPacks() {
        return healthPacks;
    }

    /** 追加一个苹果坐标（用于从生成结果构建快照）。 */
    public void addHealthPack(Position p) {
        healthPacks.add(HealthPackPosition.from(p));
    }

    public List<EnemySaveData> getEnemyStates() {
        return enemyStates;
    }

    /** 构建列表展示摘要。 */
    public WorldSaveSummary toSummary() {
        return new WorldSaveSummary(worldId, worldName, floorLevel,
                runCurrentHp, difficulty, savedAtEpochMillis);
    }

    /**
     * 完整校验：必填字段、范围、重复 agentId/坐标与越界位置。
     * 校验不依赖 world 尺寸（由调用方在生成基础世界后二次确认坐标）。
     */
    public void validate() {
        if (isBlank(worldId)) {
            throw new IllegalArgumentException("worldId must not be blank");
        }
        if (isBlank(worldName)) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        // 加载路径二次校验：worldName 必须是合法显示名（长度/控制字符），
        // 防止坏档把非法字符带进列表 UI。
        WorldName.validateForDisplay(worldName);
        if (isBlank(seed)) {
            throw new IllegalArgumentException("seed must not be blank");
        }
        if (floorLevel < 1) {
            throw new IllegalArgumentException("floorLevel must be positive");
        }
        if (difficulty == null) {
            throw new IllegalArgumentException("difficulty must not be null");
        }
        if (runCurrentHp < 0) {
            throw new IllegalArgumentException("runCurrentHp must be >= 0");
        }
        if (visionMode == null) {
            throw new IllegalArgumentException("visionMode must not be null");
        }
        try {
            VisionMode.valueOf(visionMode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown visionMode: " + visionMode, e);
        }
        if (healthPacks == null || enemyStates == null) {
            throw new IllegalArgumentException(
                    "health packs and enemy states must not be null");
        }
        // 楼梯允许 (-1,-1) 作为“本层无楼梯”哨兵；部分哨兵或负数非法。
        if (stairsX < -1 || stairsY < -1
                || (stairsX == -1) != (stairsY == -1)) {
            throw new IllegalArgumentException(
                    "stairs position must be a valid coordinate or (-1,-1)");
        }
        if (floorPlayerX < 0 || floorPlayerY < 0) {
            throw new IllegalArgumentException(
                    "floor player position must not be negative");
        }
        for (HealthPackPosition hp : healthPacks) {
            if (hp.x() < 0 || hp.y() < 0) {
                throw new IllegalArgumentException(
                        "health pack position must not be negative");
            }
        }

        java.util.Set<Position> occupied = new java.util.HashSet<>();
        occupied.add(getFloorPlayerPosition());
        occupied.add(getStairsPosition());
        java.util.Set<String> agentIds = new java.util.HashSet<>();
        for (EnemySaveData enemy : enemyStates) {
            if (isBlank(enemy.getAgentId())) {
                throw new IllegalArgumentException(
                        "enemy agentId must not be blank");
            }
            if (!agentIds.add(enemy.getAgentId())) {
                throw new IllegalArgumentException(
                        "duplicate enemy agentId: " + enemy.getAgentId());
            }
            Position pos = enemy.getPosition();
            if (pos.x < 0 || pos.y < 0) {
                throw new IllegalArgumentException(
                        "enemy position must not be negative");
            }
            if (!occupied.add(pos)) {
                throw new IllegalArgumentException(
                        "duplicate or player/stair-overlapping position: "
                                + pos);
            }
            if (enemy.getHp() < 0 || enemy.getMaxHp() <= 0
                    || enemy.getHp() > enemy.getMaxHp()) {
                throw new IllegalArgumentException(
                        "enemy hp out of range for " + enemy.getAgentId());
            }
            if (enemy.getMoveInterval() <= 0
                    || enemy.getSightRange() <= 0) {
                throw new IllegalArgumentException(
                        "enemy combat config out of range for "
                                + enemy.getAgentId());
            }
            if (enemy.getFacing() != null) {
                try {
                    Facing.valueOf(enemy.getFacing());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "unknown enemy facing: " + enemy.getFacing()
                                    + " for " + enemy.getAgentId(), e);
                }
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
