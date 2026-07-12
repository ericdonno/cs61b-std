package byog.Core;

import byog.TileEngine.TETile;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体管理与空间索引。
 * <p>
 * 唯一的碰撞检测入口：{@link #canMoveTo(Entity, Position, TETile[][])}
 * — 同时检查地形和实体占用，Player 和 Enemy 都走同一路径。
 * <p>
 * 延迟更新模式：AI tick 中只修改 Entity.position 字段，帧末通过
 * {@link #flushPendingChanges()} 统一重建索引，避免遍历期间修改 HashMap。
 */
public class EntityManager {
    private Map<Position, Entity> positionIndex = new HashMap<>();
    /** 帧内已被实体占用的新位置，用于防止同帧内重叠。帧末清空。 */
    private Set<Position> frameOccupied = new HashSet<>();
    /** 待加入实体列表，帧末统一添加 */
    private List<Entity> pendingAdd = new ArrayList<>();

    /** 检查实体能否移动到目标位置（地形 + 实体碰撞）。 */
    public boolean canMoveTo(Entity entity, Position target, TETile[][] world) {
        if (!Entity.canStandOn(target, world)) {
            return false;
        }
        if (frameOccupied.contains(target)) {
            return false;
        }
        Entity occupant = positionIndex.get(target);
        return occupant == null || occupant == entity || !occupant.isAlive();
    }

    /** 添加实体到空间索引。 */
    public void addEntity(Entity e) {
        positionIndex.put(e.getPosition(), e);
    }

    /** 从空间索引移除实体。 */
    public void removeEntity(Entity e) {
        positionIndex.remove(e.getPosition());
    }

    /** 清理所有死亡实体。 */
    public void removeDeadEntities() {
        List<Position> toRemove = new ArrayList<>();
        for (Map.Entry<Position, Entity> entry : positionIndex.entrySet()) {
            if (!entry.getValue().isAlive()) {
                toRemove.add(entry.getKey());
            }
        }
        for (Position p : toRemove) {
            positionIndex.remove(p);
        }
    }

    /** 获取所有活实体（返回视图，遍历期间不应修改索引）。 */
    public Collection<Entity> getAllEntities() {
        return positionIndex.values();
    }

    /** 请求在帧末添加实体。 */
    public void requestAddEntity(Entity e) {
        pendingAdd.add(e);
    }

    /** 标记位置为帧内已占用，防止同帧其他实体走入。 */
    public void claimPosition(Position p) {
        frameOccupied.add(p);
    }

    /** 帧末统一重建索引：根据实体最新 position 重建，并处理待加入实体。 */
    public void flushPendingChanges() {
        Map<Position, Entity> newIndex = new HashMap<>();
        for (Entity e : positionIndex.values()) {
            newIndex.put(e.getPosition(), e);
        }
        positionIndex.clear();
        positionIndex.putAll(newIndex);

        for (Entity e : pendingAdd) {
            positionIndex.put(e.getPosition(), e);
        }
        pendingAdd.clear();
        frameOccupied.clear();
    }
}
