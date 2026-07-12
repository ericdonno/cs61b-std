package byog.Core;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

public class Player extends Entity {
    private int hp;
    private int sightRange;

    public Player() {
        this(new Position(0, 0), 100, 10);
    }

    public Player(Position position) {
        this(position, 100, 10);
    }

    public Player(Position position, int hp, int sightRange) {
        super(position, Tileset.PLAYER);
        this.hp = hp;
        this.sightRange = sightRange;
    }

    public int getHp() {
        return hp;
    }

    public void setHp(int hp) {
        this.hp = hp;
    }

    public int getSightRange() {
        return sightRange;
    }

    /**
     * 通过 EntityManager 统一检测碰撞，尝试沿指定方向移动玩家。
     */
    public void move(Direction direction, TETile[][] world, EntityManager entityMgr) {
        Position newPos = getNewPosition(direction);
        if (entityMgr.canMoveTo(this, newPos, world)) {
            this.position = newPos;
            entityMgr.claimPosition(newPos);
        }
    }

    private Position getNewPosition(Direction direction) {
        int x = position.x;
        int y = position.y;
        switch (direction) {
            case UP:
                y += 1;
                break;
            case DOWN:
                y -= 1;
                break;
            case LEFT:
                x -= 1;
                break;
            case RIGHT:
                x += 1;
                break;
            default:
                break;
        }
        return new Position(x, y);
    }

    /**
     * 判断玩家能否站立在指定位置（不能是墙或虚空）。
     */
    public static boolean canStandOn(Position p, TETile[][] world) {
        return Entity.canStandOn(p, world);
    }

    /**
     * initialize a player in the certain world (or not)
     */
    public static void initPlayer(Player player, TETile[][] world, String seed) {
        Entity.initEntity(player, world, seed);
    }

    public static void initPlayer(Player player) {
    }
}
