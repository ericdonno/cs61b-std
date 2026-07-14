package byog.Core;

import byog.Helper.Logger;
import byog.Helper.MathHelper;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.*;

import static byog.Core.RandomUtils.biasUniform;
import static byog.Core.RandomUtils.uniform;

public class WorldGenerator {
    public static WorldGenResult RandomSquareRoomWrd(TETile[][] world, String seed) {
        if (seed == null || seed.isEmpty()) {
            seed = String.valueOf(System.currentTimeMillis());
            Logger.info("Seed is empty. Using default seed: %s", seed);
        }
        final Random random = new Random(seed.hashCode());

        Logger.section("World Generation");
        Logger.debug("World size: %d x %d", world.length, world[0].length);

        // Generating random rooms
        Logger.subsection("Room Generation");
        int C = 80;
        int A = 10;
        int numberOfRooms = (int) biasUniform(random, C, C+A, 1.5);
        Logger.debug("Target room count: %d", numberOfRooms);
        ArrayList<SquareRoom> allRoom = new ArrayList<>();
        Loop:
        for (int i = 0; i < numberOfRooms; i++) {
            // 随机生成房间的位置和大小
            int roomSize = uniform(random, 5, 12); // 随机房间大小（最小5，最大9）
            int xPos = uniform(random, world.length );   //在生成房间位置时，也许已经保证了房间不会出界
            int yPos = uniform(random, world[0].length );
            Position p = new Position(xPos,yPos);
            Logger.debug("Room Size: %d, Position: %s", roomSize, p);
            SquareRoom room = new SquareRoom(p, roomSize);
            // 判断房间位置是否合法
            if (!room.isWithinBounds(world)) {
                continue;
            }
            for (SquareRoom other: allRoom) {
                if (room.isIntersect(other)) {
                    continue Loop;
                }
            }
            allRoom.add(room);
        }

        Logger.info("Surviving rooms: %d", allRoom.size());
        for (SquareRoom r: allRoom) {
            Logger.debug("Room Size: %d, Position: %s", r.getSize(), r.getPosition());
        }

        Logger.subsection("Adding Rooms to World");
        for (SquareRoom r: allRoom) {
            r.addSelf(world);
        }

        Logger.subsection("Hall Generation");
        RoomGraph roomGraph = new RoomGraph(allRoom);
        Logger.debug("RoomGraph:\n %s", roomGraph);
        List<Hall>[] allHall = roomGraph.mstAndOtherHalls();
        List<Hall> mstHall = allHall[0];
        List<Hall> otherHall = allHall[1];
        Logger.info("MST Halls: %d, Other Halls: %d", mstHall.size(), otherHall.size());

        Logger.debug("Adding MST Halls:");
        for (Hall h: mstHall) {
            Logger.debug("  Hall: %s", h.addSelf(world, random));
        }

        int nOfOtherToAdd = (int)( mstHall.size()/1.5);
        Logger.info("Other halls to add (poisson): %d", nOfOtherToAdd);
        Collections.sort(otherHall, (h1, h2) -> Double.compare(h1.getScale(), h2.getScale()));
        otherHall.forEach(h -> Logger.debug("Hall scale: %.2f", h.getScale()));
        int otherIndecies[] = MathHelper.poissonDistributedIndecies(random, otherHall.size(), nOfOtherToAdd);
        Logger.debug("Poisson selected indices:");
        for (int i: otherIndecies) {
            Logger.debug("  Hall scale: %.2f", otherHall.get(i).getScale());
        }
        Logger.debug("Adding other Halls:");
        for (int i: otherIndecies) {
            Logger.debug("  Hall: %s", otherHall.get(i).addSelf(world, random));
        }

        Logger.subsection("Adding Walls");
        Set<Position> floors = new HashSet<>();
            //记录world中所有的地板，除开最外层一圈。而且理论上此函数生成的world在最外层不会出现floor。
        for (int i = 1; i < world.length-1; i++) {
            for (int j = 1; j < world[i].length-1; j++) {
                if (world[i][j].description() == Tileset.FLOOR.description()) {
                    floors.add(new Position(i,j));
                }
            }
        }
        for (Position p : floors) {
            // 使用方向数组替代硬编码方向
            int[][] directions = {
                    {1, 1},  {1, 0},  {1, -1}, // 右上、右、右下
                    {0, 1},           {0, -1},  // 上、下
                    {-1, 1}, {-1, 0}, {-1, -1} // 左上、左、左下
            };

            for (int[] dir : directions) {
                Position neighbor = new Position(p.x + dir[0], p.y + dir[1]);
                ifIsNothingToWall(world, neighbor);
            }
        }


        return new WorldGenResult(world, allRoom);
    }

    /** Helper */
    private static void ifIsNothingToWall(TETile[][] world, Position p) {
        if (world[p.x][p.y].description() == Tileset.NOTHING.description()) {
            world[p.x][p.y] = Tileset.WALL;
        }
    }
}


