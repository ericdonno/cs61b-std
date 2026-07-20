package byog.Perception;

import byog.lab5.Position;

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
    private final List<VisibleEntity> visibleEntities;
    private final List<HeardEvent> heardEvents;

    /** 包内可见，由 PerceptionSystem 创建 */
    ObservationEnvelope(String runId, int floorId, String agentId,
                        long observationSeq, long observedAtTurn,
                        Position selfPosition, int selfHp,
                        boolean[][] visibleMask,
                        List<VisibleEntity> visibleEntities,
                        List<HeardEvent> heardEvents) {
        this.runId = runId;
        this.floorId = floorId;
        this.agentId = agentId;
        this.observationSeq = observationSeq;
        this.observedAtTurn = observedAtTurn;
        this.selfPosition = selfPosition;
        this.selfHp = selfHp;
        this.visibleMask = visibleMask;
        this.visibleEntities = Collections.unmodifiableList(visibleEntities);
        this.heardEvents = Collections.unmodifiableList(heardEvents);
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
        return selfPosition;
    }

    public int getSelfHp() {
        return selfHp;
    }

    public boolean[][] getVisibleMask() {
        return visibleMask;
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
}
