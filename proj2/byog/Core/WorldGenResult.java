package byog.Core;

import byog.TileEngine.TETile;

import java.util.List;

/** 单层地牢生成结果，打包 world 和房间列表。 */
public class WorldGenResult {
    private final TETile[][] world;
    private final List<SquareRoom> rooms;

    public WorldGenResult(TETile[][] world, List<SquareRoom> rooms) {
        this.world = world;
        this.rooms = rooms;
    }

    public TETile[][] getWorld() {
        return world;
    }

    public List<SquareRoom> getRooms() {
        return rooms;
    }
}
