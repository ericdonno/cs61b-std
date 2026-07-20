package byog.Perception;

import byog.lab5.Position;

/**
 * 感知系统中可见实体的简要信息。不暴露完整 Entity 引用以防信息泄漏。
 */
public final class VisibleEntity {
    public enum EntityType { PLAYER, ENEMY, OTHER }

    private final EntityType type;
    private final Position position;
    private final int visibleHp;
    private final String agentId;

    public VisibleEntity(EntityType type, Position position,
                         int visibleHp, String agentId) {
        this.type = type;
        this.position = position;
        this.visibleHp = visibleHp;
        this.agentId = agentId;
    }

    public EntityType getType() {
        return type;
    }

    public Position getPosition() {
        return position;
    }

    public int getVisibleHp() {
        return visibleHp;
    }

    /** 如果是 enemy，返回其 agentId；否则返回 null */
    public String getAgentId() {
        return agentId;
    }
}
