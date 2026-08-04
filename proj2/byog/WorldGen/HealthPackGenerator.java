package byog.WorldGen;

import byog.Entity.EntityManager;
import byog.Helper.Logger;
import byog.IO.HealthPackConfig;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 苹果血包初始放置器。只负责生成期放置，不处理拾取、UI 或存档 I/O。
 *
 * <p>候选来自房间内部地板，排除出生房与楼梯房、非普通地板、实体占位和已选位置；
 * 使用独立随机流与稳定排序，保证相同输入产生相同结果且不扰动地图/楼梯/敌人/战斗
 * 随机序列。候选不足时放置全部合法位置并记录。</p>
 */
public final class HealthPackGenerator {

    private HealthPackGenerator() {
    }

    /**
     * 在世界中放置苹果并把对应 tile 改为 {@link Tileset#APPLE}。
     *
     * @param world            当前楼层世界（会被修改）
     * @param rooms            当前楼层房间列表
     * @param spawnRoom        玩家出生房间；null 时本层放置 0 个
     * @param stairRoom        楼梯所在房间；null 时本层放置 0 个
     * @param entities         实体索引，用于排除占位（可为 null）
     * @param config           数量与治疗配置
     * @param deterministicSeed 独立于其他生成流程的稳定种子
     * @return 已放置的苹果坐标（按 x、再按 y 稳定排序）
     */
    public static List<Position> place(TETile[][] world,
                                       List<SquareRoom> rooms,
                                       SquareRoom spawnRoom,
                                       SquareRoom stairRoom,
                                       EntityManager entities,
                                       HealthPackConfig config,
                                       long deterministicSeed) {
        if (world == null || rooms == null) {
            throw new IllegalArgumentException(
                    "world and rooms must not be null");
        }
        if (spawnRoom == null || stairRoom == null) {
            Logger.info(
                    "Cannot identify spawn or stair room; placing 0 health packs.");
            return new ArrayList<>();
        }

        List<Position> candidates = new ArrayList<>();
        for (SquareRoom room : rooms) {
            if (room == spawnRoom || room == stairRoom) {
                continue;
            }
            for (Position p : room.getFloorPositions()) {
                if (!isInside(world, p)) {
                    continue;
                }
                if (world[p.x][p.y] != Tileset.FLOOR) {
                    continue;
                }
                if (entities != null && entities.findEntityAt(p) != null) {
                    continue;
                }
                candidates.add(p);
            }
        }
        // 稳定顺序：先 x、再 y；随机选择前必须固定。
        candidates.sort(Comparator.comparingInt((Position p) -> p.x)
                .thenComparingInt(p -> p.y));

        Random random = new Random(deterministicSeed);
        int target = config.minCount()
                + random.nextInt(config.maxCount() - config.minCount() + 1);

        // 无放回抽样：对稳定候选做一次确定性洗牌，再取前缀。
        List<Position> pool = new ArrayList<>(candidates);
        for (int i = pool.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Position tmp = pool.get(i);
            pool.set(i, pool.get(j));
            pool.set(j, tmp);
        }

        int toPlace = Math.min(target, pool.size());
        List<Position> placed = new ArrayList<>();
        for (int i = 0; i < toPlace; i++) {
            Position p = pool.get(i);
            world[p.x][p.y] = Tileset.APPLE;
            placed.add(p);
        }
        if (toPlace < target) {
            Logger.info(
                    "Health pack candidates insufficient: target=%d actual=%d",
                    target, toPlace);
        }
        return placed;
    }

    private static boolean isInside(TETile[][] world, Position p) {
        return p.x >= 0 && p.x < world.length
                && p.y >= 0 && p.y < world[0].length;
    }
}
