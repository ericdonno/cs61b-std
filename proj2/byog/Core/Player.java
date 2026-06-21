package byog.Core;

import byog.Helper.Logger;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.Random;
import java.util.function.Predicate;

import static byog.Core.RandomUtils.uniform;

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
        if (p.x < 0 || p.x >= world.length || p.y < 0 || p.y >= world[0].length) {
            return false;
        }
        TETile tile = world[p.x][p.y];
        return tile != Tileset.WALL && tile != Tileset.NOTHING;
    }

    /**
     * initialize a player in the certain world (or not)
     */
    public static void initPlayer(Player player, TETile[][] world, String seed) {
        if (seed == null || seed.isEmpty()) {
            seed = String.valueOf(System.currentTimeMillis());
            Logger.info("Seed is empty. Using default seed: %s", seed);
        }
        final Random random = new Random(seed.hashCode());

        int xPos, yPos;
        while (!canMoveTo(player.position, world)) {
            xPos = uniform(random, world.length);
            yPos = uniform(random, world[0].length);
            player.position = new Position(xPos, yPos);
        }
    }

    public static void initPlayer(Player player) {
    }
}