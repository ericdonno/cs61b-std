package byog.Perception;

import byog.Common.Facing;
import byog.Common.VisionMode;
import byog.Entity.Enemy;
import byog.Entity.Entity;
import byog.Entity.EntityManager;
import byog.Entity.Player;
import byog.Helper.MathHelper;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.List;

/**
 * 私有感知计算系统。纯函数，不持有状态。
 * 为每个敌人计算该 tick 内可感知的世界切片。
 */
public final class PerceptionSystem {

    /** 判断某个 tile 是否阻挡视线（WALL 或 NOTHING） */
    public static boolean blocksVision(TETile tile) {
        return tile == Tileset.WALL || tile == Tileset.NOTHING;
    }

    /**
     * Bresenham 直线检测：从 (x0,y0) 到 (x1,y1) 的射线是否被 WALL/NOTHING 阻挡。
     * @return true 如果终点可见（射线路径上无障碍物）
     */
    public static boolean hasLineOfSight(TETile[][] world, int x0, int y0, int x1, int y1) {
        if (x0 == x1 && y0 == y1) {
            return true;
        }

        if (x0 < 0 || x0 >= world.length || y0 < 0 || y0 >= world[0].length) {
            return false;
        }
        if (x1 < 0 || x1 >= world.length || y1 < 0 || y1 >= world[0].length) {
            return false;
        }

        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;

        int x = x0;
        int y = y0;

        while (true) {
            if (x == x1 && y == y1) {
                return true;
            }

            if (x != x0 || y != y0) {
                if (blocksVision(world[x][y])) {
                    return false;
                }
            }

            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    /**
     * 计算单个敌人的私有感知结果。
     *
     * <p>计算顺序固定为：曼哈顿菱形 → 视野模式/朝向半边 → LOS → 可见 tile/实体快照。
     * 必须先完成 mask 再收集可见实体，避免背后玩家先进入 visibleEntities 再被裁掉。</p>
     *
     * @param worldId 当前命名世界的稳定身份
     * @param runId 本次游戏运行的 ID
     * @param floorId 当前楼层
     * @param observationSeq 此敌人的 observation 序号
     * @param visionMode DIRECTIONAL 只保留面朝方向的半边菱形；OMNIDIRECTIONAL 保留原全向菱形
     */
    public static ObservationEnvelope computeObservation(
            String worldId, String runId, int floorId, long observationSeq,
            TETile[][] world, EntityManager entityMgr,
            Enemy self, Player player,
            int sightRange, VisionMode visionMode, long currentTurn) {

        Position selfPos = self.getPosition();
        int selfX = selfPos.x;
        int selfY = selfPos.y;

        boolean[][] visibleMask = new boolean[world.length][world[0].length];
        List<VisibleEntity> visibleEntities = new ArrayList<>();
        List<HeardEvent> heardEvents = new ArrayList<>();

        // 第一步：范围 → 模式/朝向半边 → LOS。
        for (int x = 0; x < world.length; x++) {
            for (int y = 0; y < world[0].length; y++) {
                int dist = Math.abs(x - selfX) + Math.abs(y - selfY);
                if (dist > sightRange) {
                    continue;
                }
                if (visionMode == VisionMode.DIRECTIONAL
                        && !allowedByFacing(selfPos, self.getFacing(), x, y)) {
                    continue;
                }
                if (hasLineOfSight(world, selfX, selfY, x, y)) {
                    visibleMask[x][y] = true;
                }
            }
        }

        // 第二步：固化可见 tile 快照（类型 + 可行走性），避免后续序列化时再读 live world
        List<VisibleTile> visibleTiles = new ArrayList<>();
        for (int x = 0; x < world.length; x++) {
            for (int y = 0; y < world[0].length; y++) {
                if (visibleMask[x][y]) {
                    TETile tile = world[x][y];
                    VisibleTile.TileType type = VisibleTile.tileTypeOf(tile);
                    boolean walkable = tile != Tileset.WALL && tile != Tileset.NOTHING;
                    visibleTiles.add(new VisibleTile(x, y, type, walkable));
                }
            }
        }

        // 第三步：收集可见实体。只查询 FOV 内的位置，避免信息泄漏
        for (int x = 0; x < world.length; x++) {
            for (int y = 0; y < world[0].length; y++) {
                if (visibleMask[x][y]) {
                    Entity entity = entityMgr.findEntityAt(new Position(x, y));
                    if (entity != null && entity.isAlive() && entity != self) {
                        VisibleEntity.EntityType type;
                        int hp;
                        String agentId = null;

                        // 根据实体类型提取不同信息，防止通过引用泄漏完整实体
                        if (entity instanceof Player) {
                            type = VisibleEntity.EntityType.PLAYER;
                            hp = ((Player) entity).getHp();
                        } else if (entity instanceof Enemy) {
                            type = VisibleEntity.EntityType.ENEMY;
                            hp = ((Enemy) entity).getHp();
                            agentId = ((Enemy) entity).getAgentId();
                        } else {
                            type = VisibleEntity.EntityType.OTHER;
                            hp = 0;
                        }

                        visibleEntities.add(new VisibleEntity(type,
                                entity.getPosition(), hp, agentId));
                    }
                }
            }
        }

        // 第四步：构造不可变的 ObservationEnvelope 返回
        return new ObservationEnvelope(worldId, runId, floorId,
                self.getAgentId(), observationSeq, currentTurn,
                selfPos, self.getHp(), self.getMaxHp(),
                self.getFacing(), visionMode,
                visibleMask, visibleEntities, heardEvents,
                visibleTiles, world);
    }

    /**
     * 半边过滤：只保留面朝方向的半菱形（含穿过 self 的切分中线）。
     * 中线使用 &gt;=/&lt;= 保证可见；自身格始终可见。
     */
    private static boolean allowedByFacing(
            Position self, Facing facing, int x, int y) {
        if (x == self.x && y == self.y) {
            return true;
        }
        return switch (facing) {
            case NORTH -> y >= self.y;
            case SOUTH -> y <= self.y;
            case EAST -> x >= self.x;
            case WEST -> x <= self.x;
        };
    }
}
