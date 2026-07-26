package byog.Perception;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个敌人、单个 tick 的私有感知结果。不可变。
 */
public final class ObservationEnvelope {
    private final String runId;
    private final int floorId;
    private final String agentId;
    private final long observationSeq;
    private final long observedAtTurn;
    private final Position selfPosition;
    private final int selfHp;
    private final boolean[][] visibleMask;
    private final boolean[][] walkableMask;
    private final List<VisibleEntity> visibleEntities;
    private final List<HeardEvent> heardEvents;
    private final List<VisibleTile> visibleTiles;

    /** 包内可见，由 PerceptionSystem 创建 */
    ObservationEnvelope(String runId, int floorId, String agentId,
                        long observationSeq, long observedAtTurn,
                        Position selfPosition, int selfHp,
                        boolean[][] visibleMask,
                        List<VisibleEntity> visibleEntities,
                        List<HeardEvent> heardEvents,
                        List<VisibleTile> visibleTiles,
                        TETile[][] world) {
        this.runId = runId;
        this.floorId = floorId;
        this.agentId = agentId;
        this.observationSeq = observationSeq;
        this.observedAtTurn = observedAtTurn;
        this.selfPosition = copyPosition(selfPosition);
        this.selfHp = selfHp;
        this.visibleMask = copyMask(visibleMask);
        this.walkableMask = buildWalkableMask(this.visibleMask, world);
        this.visibleEntities = Collections.unmodifiableList(
                new ArrayList<>(visibleEntities));
        this.heardEvents = Collections.unmodifiableList(
                new ArrayList<>(heardEvents));
        this.visibleTiles = Collections.unmodifiableList(
                new ArrayList<>(visibleTiles));
    }

    public String getRunId() {
        return runId;
    }

    public int getFloorId() {
        return floorId;
    }

    public String getAgentId() {
        return agentId;
    }

    public long getObservationSeq() {
        return observationSeq;
    }

    public long getObservedAtTurn() {
        return observedAtTurn;
    }

    public Position getSelfPosition() {
        return copyPosition(selfPosition);
    }

    public int getSelfHp() {
        return selfHp;
    }

    public boolean[][] getVisibleMask() {
        return copyMask(visibleMask);
    }

    /** 查询 (x,y) 是否在 FOV 内 */
    public boolean isVisible(int x, int y) {
        if (x < 0 || x >= visibleMask.length || y < 0 || y >= visibleMask[0].length) {
            return false;
        }
        return visibleMask[x][y];
    }

    /** 可见实体列表（不含自身） */
    public List<VisibleEntity> getVisibleEntities() {
        return visibleEntities;
    }

    /** 玩家是否在可见实体中 */
    public boolean canSeePlayer() {
        for (VisibleEntity ve : visibleEntities) {
            if (ve.getType() == VisibleEntity.EntityType.PLAYER) {
                return true;
            }
        }
        return false;
    }

    /** 如果玩家可见则返回 VisibleEntity 中的玩家信息，否则返回 null */
    public VisibleEntity getVisiblePlayer() {
        for (VisibleEntity ve : visibleEntities) {
            if (ve.getType() == VisibleEntity.EntityType.PLAYER) {
                return ve;
            }
        }
        return null;
    }

    /** 当前 tick 听到的事件 */
    public List<HeardEvent> getHeardEvents() {
        return heardEvents;
    }

    /** 可见 tile 快照列表（含坐标、类型、可行走性），不可变 */
    public List<VisibleTile> getVisibleTiles() {
        return visibleTiles;
    }

    /** 统计 visibleMask 中 true 的数量，用于 trace 的 fovTileCount */
    public int countVisibleTiles() {
        int count = 0;
        for (int x = 0; x < visibleMask.length; x++) {
            for (int y = 0; y < visibleMask[x].length; y++) {
                if (visibleMask[x][y]) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 判断 (x,y) 是否为可见且可行走的位置。
     * 先检查 visibleMask（必须在 FOV 内），再检查 tile 类型（必须不是墙或虚空）。
     * 这样 Brain 不需要直接持有 world 引用，也无法访问不可见区域。
     */
    public boolean isWalkable(int x, int y) {
        if (!isVisible(x, y)) {
            return false;
        }
        return walkableMask[x][y];
    }

    private static Position copyPosition(Position position) {
        return position == null ? null : new Position(position.x, position.y);
    }

    private static boolean[][] copyMask(boolean[][] source) {
        boolean[][] copy = new boolean[source.length][];
        for (int x = 0; x < source.length; x++) {
            copy[x] = source[x].clone();
        }
        return copy;
    }

    /**
     * 在 observation 创建时固化可行走信息，避免后续世界变化改变历史 observation。
     */
    private static boolean[][] buildWalkableMask(boolean[][] visibility, TETile[][] world) {
        boolean[][] walkable = new boolean[visibility.length][];
        for (int x = 0; x < visibility.length; x++) {
            walkable[x] = new boolean[visibility[x].length];
            for (int y = 0; y < visibility[x].length; y++) {
                if (!visibility[x][y]) {
                    continue;
                }
                TETile tile = world[x][y];
                walkable[x][y] = tile != Tileset.WALL && tile != Tileset.NOTHING;
            }
        }
        return walkable;
    }
}
