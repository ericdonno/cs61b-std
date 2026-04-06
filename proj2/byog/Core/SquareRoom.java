package byog.Core;

import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import static java.lang.Math.sqrt;

/**
 * The structure of floors surrounded by walls
 */

public class SquareRoom implements Room{
    private static final String shape = "SQUARE";
    /**Size of all, including walls*/
    private int size;
    /**maybe changeable someday*/
    private TETile floorStyle;
    private TETile wallStyle;
    private Position position;  // 用右下角表示该房间的位置

    public SquareRoom(Position p, int size) {
        this.size = size;
        this.position = p;
        this.floorStyle = Tileset.FLOOR;
        this.wallStyle = Tileset.WALL;
    }

    @Override
    public int getSize() {
        return size;
    }

    @Override
    public Position getPosition() {
        return position;
    }

    @Override
    public boolean isIntersect(SquareRoom other) {
        if (other == null) {
            return false;
        }
        //如果水平不相交
        if (position.x + size <= other.position.x || other.position.x + other.size <= this.position.x) {
            return false;
        }
        //如果垂直不相交
        if (position.y + size <= other.position.y || other.position.y + other.size <= this.position.y) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isWithinBounds(TETile[][] world) {
        // 检查房间的右上角是否在世界边界内
        return position.x >= 0 && position.y >= 0 &&
                position.x + size <= world.length &&
                position.y + size <= world[0].length;
    }

    @Override
    public boolean addSelf(TETile[][] world) {
        if (!isWithinBounds(world)) {
            return false;
        }
        for (int x = position.x; x < position.x + size; x++) {
            for (int y = position.y; y < position.y + size; y++) {
                // 判断是否在墙壁的边界上
                if (x == position.x || x == position.x + size - 1 || y == position.y || y == position.y + size - 1) {
                    world[x][y] = wallStyle; // 设置墙壁
                } else {
                    world[x][y] = floorStyle; // 设置地板
                }
            }
        }
        return true;
    }

    @Override
    public String getShape() {
        return shape;
    }
}
