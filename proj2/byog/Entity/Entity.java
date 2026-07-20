package byog.Entity;

import static byog.Common.RandomUtils.uniform;
import byog.Helper.Logger;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.Random;

public abstract class Entity {
    private static int nextId = 0;
    protected final int id = nextId++;
    protected Position position;
    protected TETile tile;
    protected boolean alive = true;
    /** 正式可存档身份，稳定性由调用方保证 */
    protected String agentId = null;

    public Entity(Position position, TETile tile) {
        this.position = position;
        this.tile = tile;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public TETile getTile() {
        return tile;
    }

    public void setTile(TETile tile) {
        this.tile = tile;
    }

    public int getId() {
        return id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public boolean isAlive() {
        return alive;
    }

    /** 标记实体为死亡状态，待下一帧清理 */
    public void die() {
        this.alive = false;
    }

    /**
     * 判断指定位置是否为实体可站立的合法地面（不能是墙或虚空）。
     */
    public static boolean canStandOn(Position p, TETile[][] world) {
        if (p.x < 0 || p.x >= world.length || p.y < 0 || p.y >= world[0].length) {
            return false;
        }
        TETile tile = world[p.x][p.y];
        return tile != Tileset.WALL && tile != Tileset.NOTHING;
    }

    /**
     * 使用 seed 在世界中随机放置实体
     */
    public static void initEntity(Entity entity, TETile[][] world, String seed) {
        if (seed == null || seed.isEmpty()) {
            seed = String.valueOf(System.currentTimeMillis());
            Logger.info("Seed is empty. Using default seed: %s", seed);
        }
        final Random random = new Random(seed.hashCode());

        int xPos, yPos;
        while (!canStandOn(entity.position, world)) {
            xPos = uniform(random, world.length);
            yPos = uniform(random, world[0].length);
            entity.position = new Position(xPos, yPos);
        }
    }
}