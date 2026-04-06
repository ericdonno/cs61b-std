package byog.Core;

import byog.Helper.MathHelper;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.*;

import static byog.Core.RandomUtils.biasUniform;
import static byog.Core.RandomUtils.uniform;

public class WorldGenerator {
    public static TETile[][] RandomSquareRoomWrd(TETile[][] world, String seed) {
        if (seed == null || seed.isEmpty()) {
            seed = String.valueOf(System.currentTimeMillis());  // 使用时间戳作为种子
            System.out.println("Seed is empty. Using default seed: " + seed);
        }
        final Random random = new Random(seed.hashCode());

        System.out.println(world.length+" "+world[0].length);

        // Generating random rooms
        int C = 80;
        int A = 10;
        int numberOfRooms = (int) biasUniform(random, C, C+A, 1.5);  //房间数C-C+A间，偏向C
        System.out.println("Room:"+numberOfRooms);
        ArrayList<SquareRoom> allRoom = new ArrayList<>();
        Loop:
        for (int i = 0; i < numberOfRooms; i++) {
            // 随机生成房间的位置和大小
            int roomSize = uniform(random, 5, 12); // 随机房间大小（最小5，最大9）
            int xPos = uniform(random, world.length );   //在生成房间位置时，也许已经保证了房间不会出界
            int yPos = uniform(random, world[0].length );
            Position p = new Position(xPos,yPos);
            System.out.print(roomSize+":"+p+" ");   //for test, don't change it
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

        //for test, don't change it
        System.out.println();
        System.out.println("Room survive:"+allRoom.size());
        for (SquareRoom r: allRoom) System.out.print(r.getSize()+":"+r.getPosition()+" ");

        // adding Rooms
        System.out.println();
        for (SquareRoom r: allRoom) {
            r.addSelf(world);
        }


        // get all Halls
        RoomGraph roomGraph = new RoomGraph(allRoom);
        System.out.println(roomGraph);  //for test
        List<Hall>[] allHall = roomGraph.mstAndOtherHalls();
        List<Hall> mstHall = allHall[0];
        List<Hall> otherHall = allHall[1];
        System.out.println("mst Halls:"+mstHall.size()+" other Halls:"+otherHall.size()+"\n");  //for test

        //add最小生成树Hall
        for (Hall h: mstHall) {
            System.out.println(h.addSelf(world,random));
        }

        //add其他Hall
        int nOfOtherToAdd = (int)( mstHall.size()/1.5);  //确定生成其他走廊数量
        System.out.println("number of other to add:"+nOfOtherToAdd+"\n");
        Collections.sort(otherHall, (h1, h2) -> Double.compare(h1.getScale(), h2.getScale()));  // 使用 Comparator 按 scale 排序
        otherHall.forEach(h -> System.out.println(h.getScale()));   // 按序输出Hall的scale
        int otherIndecies[] = MathHelper.poissonDistributedIndecies(random, otherHall.size(), nOfOtherToAdd);
        System.out.print("\npick by poisson:");
        for (int i: otherIndecies) {
            System.out.print(otherHall.get(i).getScale()+" ");
        }
        System.out.println();
        for (int i: otherIndecies) {
            System.out.println(otherHall.get(i).addSelf(world,random));
        }


        // 将走廊四周围上Wall
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


        return world;
    }

    /** Helper */
    private static void ifIsNothingToWall(TETile[][] world, Position p) {
        if (world[p.x][p.y].description() == Tileset.NOTHING.description()) {
            world[p.x][p.y] = Tileset.WALL;
        }
    }
}


