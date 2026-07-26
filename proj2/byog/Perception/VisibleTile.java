package byog.Perception;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;

/**
 * 不可变的可见 tile 快照。序列化时直接读它，不需要 live world。
 */
public final class VisibleTile {
    public enum TileType {
        FLOOR, WALL, STAIRS, NOTHING, GRASS, WATER, FLOWER,
        LOCKED_DOOR, UNLOCKED_DOOR, SAND, MOUNTAIN, TREE, UNKNOWN
    }

    private final int x;
    private final int y;
    private final TileType type;
    private final boolean walkable;

    public VisibleTile(int x, int y, TileType type, boolean walkable) {
        this.x = x;
        this.y = y;
        this.type = type;
        this.walkable = walkable;
    }

    /**
     * 从 TETile 映射到 TileType。
     * 先用 == 比较 Tileset 单例常量；FLOOR_FOV 显式映射为 FLOOR；
     * 实体 tile（ENEMY/PLAYER/PLAYER_HIT/ATTACK_FLASH）防御性映射为 FLOOR；
     * 其余未匹配返回 UNKNOWN。不依赖 description() 字符串解析。
     */
    public static TileType tileTypeOf(TETile tile) {
        if (tile == null) {
            return TileType.UNKNOWN;
        }
        if (tile == Tileset.FLOOR || tile == Tileset.FLOOR_FOV) {
            return TileType.FLOOR;
        }
        if (tile == Tileset.WALL) {
            return TileType.WALL;
        }
        if (tile == Tileset.STAIRS) {
            return TileType.STAIRS;
        }
        if (tile == Tileset.NOTHING) {
            return TileType.NOTHING;
        }
        if (tile == Tileset.GRASS) {
            return TileType.GRASS;
        }
        if (tile == Tileset.WATER) {
            return TileType.WATER;
        }
        if (tile == Tileset.FLOWER) {
            return TileType.FLOWER;
        }
        if (tile == Tileset.LOCKED_DOOR) {
            return TileType.LOCKED_DOOR;
        }
        if (tile == Tileset.UNLOCKED_DOOR) {
            return TileType.UNLOCKED_DOOR;
        }
        if (tile == Tileset.SAND) {
            return TileType.SAND;
        }
        if (tile == Tileset.MOUNTAIN) {
            return TileType.MOUNTAIN;
        }
        if (tile == Tileset.TREE) {
            return TileType.TREE;
        }
        // 实体 tile 防御性映射为 FLOOR（实体必然站在可行走地形上）
        if (tile == Tileset.ENEMY
                || tile == Tileset.PLAYER
                || tile == Tileset.PLAYER_HIT
                || tile == Tileset.ATTACK_FLASH) {
            return TileType.FLOOR;
        }
        return TileType.UNKNOWN;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public TileType getType() {
        return type;
    }

    public boolean isWalkable() {
        return walkable;
    }
}
