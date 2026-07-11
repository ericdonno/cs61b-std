package byog.Core;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.function.Predicate;

public class Player extends Entity {

    public Player() {
        super(new Position(0, 0), Tileset.PLAYER);
    }

    public Player(Position position) {
        super(position, Tileset.PLAYER);
    }

    /**
     * move a player in certain world
     */
    public void move(Direction direction, Predicate<Position> isPlayerColliding) {
        Position newPos = getNewPosition(direction);
        if (!isPlayerColliding.test(newPos)) {
            this.position = newPos;
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
     * player can only stand on floors
     */
    public static boolean canMoveTo(Position p, TETile[][] world) {
        return Entity.canMoveTo(p, world);
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