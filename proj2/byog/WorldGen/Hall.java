package byog.WorldGen;

import byog.Helper.Logger;
import byog.Helper.MathHelper;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.Random;

import static byog.Common.RandomUtils.uniform;

/**
 * 该结构被绘制时才确定其具体形状
 * */
public class Hall {
    private TETile style;
    private Room room1;
    private Room room2;
    private double scale;


    public Hall(Room room1, Room room2) {
        this.style = Tileset.FLOOR;
        this.room1 = room1;
        this.room2 = room2;
    }

    /**
     * Described by two rooms' distance
     * */
    public double getScale() {
        return room1.distanceTo(room2);
    }

    boolean addSelf(TETile[][] world, Random random) {
        if (room1.getShape().equals("SQUARE") && room2.getShape().equals("SQUARE")) {
            Position pos1 = room1.getPosition();
            Position pos2 = room2.getPosition();
            int size1 = room1.getSize();
            int size2 = room2.getSize();

            // 计算房间的边界
            int room1Left = pos1.x;
            int room1Right = pos1.x + size1 - 1;
            int room1Top = pos1.y + size1 - 1;
            int room1Bottom = pos1.y;

            int room2Left = pos2.x;
            int room2Right = pos2.x + size2 - 1;
            int room2Top = pos2.y + size2 - 1;
            int room2Bottom = pos2.y;

            boolean isHorizontalIntersect = room1Left+1 < room2Right && room2Left+1 < room1Right;   //水平投影重合
            boolean isVerticalIntersect = room1Bottom+1 < room2Top && room2Bottom+1 < room1Top;     //垂直投影重合
            boolean room2IsUpperRight = room2Left+1 >= room1Right && room2Bottom+1 >= room1Top;
            boolean room2IsUpperLeft = room1Left+1 >= room2Right && room2Bottom+1 >= room1Top;
            boolean room2IsLowerLeft = room1Left+1 >= room2Right && room1Bottom+1 >= room2Top;
            boolean room2IsLowerRight = room2Left+1 >= room1Right && room1Bottom+1 >= room2Top;

            // 房间 2 位于房间 1 右侧，且垂直投影重合
            if (room1Right < room2Left && isVerticalIntersect) {
                Logger.debug("Horizontal path from room1 right to room2 left");
                int[] bound = MathHelper.findMiddleTwo(room1Bottom,room1Top,room2Bottom,room2Top);
                int Y = uniform(random, bound[0]+1, bound[1]);
                Position start = new Position(room1Right, Y); // 房间1的右侧
                Position end = new Position(room2Left, Y);     // 房间2的左侧
                generateStraightPath(world, start, end, false);

            // 房间 2 位于房间 1 左侧，且垂直投影重合
            } else if (room1Left > room2Right && isVerticalIntersect) {
                Logger.debug("Horizontal path from room1 left to room2 right");
                int[] bound = MathHelper.findMiddleTwo(room1Bottom,room1Top,room2Bottom,room2Top);
                int Y = uniform(random, bound[0]+1, bound[1]);
                Position start = new Position(room1Left, Y);  // 房间1的左侧
                Position end = new Position(room2Right, Y);   // 房间2的右侧
                generateStraightPath(world, start, end, false);

            // 房间 2 位于房间 1 下方，且水平投影重合
            } else if (room1Bottom > room2Top && isHorizontalIntersect) {
                Logger.debug("Vertical path from room1 bottom to room2 top");
                int[] bound = MathHelper.findMiddleTwo(room1Left,room1Right,room2Left,room2Right);
                int X = uniform(random, bound[0]+1, bound[1]);
                Position start = new Position(X, room1Bottom); // 房间1的顶部中间
                Position end = new Position(X, room2Top); // 房间2的底部中间
                generateStraightPath(world, start, end, true);

            // 房间 2 位于房间 1 上方，且水平投影重合
            } else if (room1Top < room2Bottom && isHorizontalIntersect) {
                Logger.debug("Vertical path from room1 top to room2 bottom");
                int[] bound = MathHelper.findMiddleTwo(room1Left,room1Right,room2Left,room2Right);
                int X = uniform(random, bound[0]+1, bound[1]);
                Position start = new Position(X, room1Top); // 房间1的底部中间
                Position end = new Position(X , room2Bottom);     // 房间2的顶部中间
                generateStraightPath(world, start, end, true);

            // 房间 2 位于右上方
            } else if (room2IsUpperRight) {
                Logger.debug("Bent path, room2 is at upper right");
                boolean isPathStartVertically = random.nextBoolean();
                if (isPathStartVertically) {
                    int Xstart = uniform(random, room1Left+1, Math.min(room1Right,room2Left));
                    int Yend = uniform(random, Math.max(room2Bottom+1,room1Bottom+1), room2Top);   //1:as6
                    Position start = new Position(Xstart, room1Top);                 //1:as6
                    Position end = new Position(room2Left, Yend);
                    generatePathWithBend(world, start, end, isPathStartVertically);
                } else {
                    int Ystart = uniform(random, room1Bottom+1, Math.min(room1Top,room2Bottom));  //1:as6
                    int Xend = uniform(random, Math.max(room2Left+1,room1Right+1), room2Right);
                    Position start = new Position(room1Right, Ystart);
                    Position end = new Position(Xend, room2Bottom);    //1:as6
                    generatePathWithBend(world, start, end, isPathStartVertically);
                }

            // 房间 2 位于左上方
            } else if (room2IsUpperLeft) {
                Logger.debug("Bent path, room2 is at upper left");
                boolean isPathStartVertically = random.nextBoolean();
                if (isPathStartVertically) {
                    int Xstart = uniform(random, Math.max(room1Left+1,room2Right), room1Right);
                    int Yend = uniform(random, Math.max(room2Bottom+1,room1Bottom+1), room2Top);
                    Position start = new Position(Xstart, room1Top);
                    Position end = new Position(room2Right, Yend);
                    generatePathWithBend(world, start, end, isPathStartVertically);
                } else {
                    int Ystart = uniform(random, room1Bottom+1, Math.min(room1Top,room2Bottom));
                    int Xend = uniform(random, room2Left+1, Math.min(room2Right,room1Left));
                    Position start = new Position(room1Left, Ystart);
                    Position end = new Position(Xend, room2Bottom);
                    generatePathWithBend(world, start, end, isPathStartVertically);
                }

            // 房间 2 位于左下方
            } else if (room2IsLowerLeft) {
                Logger.debug("Bent path, room2 is at lower left");
                boolean isPathStartVertically = random.nextBoolean();
                if (isPathStartVertically) {
                    int Xstart = uniform(random, Math.max(room1Left+1,room2Right), room1Right);   //2:as6
                    int Yend = uniform(random, room2Bottom+1, Math.min(room2Top,room1Bottom));
                    Position start = new Position(Xstart, room1Bottom);
                    Position end = new Position(room2Right, Yend);   //2:as6
                    generatePathWithBend(world, start, end, isPathStartVertically);
                } else {
                    int Ystart = uniform(random, Math.max(room1Bottom+1,room2Top), room1Top);
                    int Xend = uniform(random, room2Left+1, Math.min(room2Right,room1Left));   //2:as6
                    Position start = new Position(room1Left, Ystart);   //2:as6
                    Position end = new Position(Xend, room2Top);
                    generatePathWithBend(world, start, end, isPathStartVertically);
                }

            // 房间 2 位于右下方
            } else if (room2IsLowerRight) {
                Logger.debug("Bent path, room2 is at lower right");
                boolean isPathStartVertically = random.nextBoolean();
                if (isPathStartVertically) {
                    int Xstart = uniform(random, room1Left+1, Math.min(room1Right,room2Left));  //3:as5
                    int Yend = uniform(random, room2Bottom+1, Math.min(room2Top,room1Bottom));  //3:as7
                    Position start = new Position(Xstart, room1Bottom);  //3:as7
                    Position end = new Position(room2Left, Yend);    //3:as5
                    generatePathWithBend(world, start, end, isPathStartVertically);
                } else {
                    int Ystart = uniform(random, Math.max(room1Bottom+1,room2Top), room1Top);  //3:as7
                    int Xend = uniform(random, Math.max(room2Left+1,room1Right+1), room2Right);   //3:as5
                    Position start = new Position(room1Right, Ystart);   //3:as5
                    Position end = new Position(Xend, room2Top);  //3:as7
                    generatePathWithBend(world, start, end, isPathStartVertically);
                }

            // 无效情况
            } else {
                return false;
            }
            return true;
        }
        return false;
    }


    /**
     * 生成直线走廊
     */
    private void generateStraightPath(TETile[][] world, Position start, Position end, boolean pathIsVertical) {
        ArrayList<Position> path = new ArrayList<>();
        if (pathIsVertical) {
            // 垂直走廊
            for (int y = Math.min(start.y, end.y); y <= Math.max(start.y, end.y); y++) {
                path.add(new Position(start.x, y));
            }
        } else {
            // 水平走廊
            for (int x = Math.min(start.x, end.x); x <= Math.max(start.x, end.x); x++) {
                path.add(new Position(x, start.y));
            }
        }

        // 绘制走廊
        for (Position pos : path) {
            world[pos.x][pos.y] = Tileset.FLOOR;
        }
        Logger.debug("Straight path length: %d, from %s to %s", path.size(), start, end);
    }

    /**
     * 生成带拐弯的走廊
     */
    private void generatePathWithBend(TETile[][] world, Position start, Position end, boolean isPathStartVertically) {
        ArrayList<Position> path = new ArrayList<>();
        path.add(start);

        if (isPathStartVertically) {
            for (int y = Math.min(start.y, end.y); y <= Math.max(start.y, end.y); y++) {
                path.add(new Position(start.x, y));
            }
            for (int x = Math.min(start.x, end.x); x <= Math.max(start.x, end.x); x++) {
                path.add(new Position(x, end.y));
            }
        } else {
            for (int x = Math.min(start.x, end.x); x <= Math.max(start.x, end.x); x++) {
                path.add(new Position(x, start.y));
            }
            for (int y = Math.min(start.y, end.y); y <= Math.max(start.y, end.y); y++) {
                path.add(new Position(end.x, y));
            }
        }

        // 绘制走廊
        for (Position pos : path) {
            world[pos.x][pos.y] = Tileset.FLOOR;
        }
        Logger.debug("Bent path length: %d, from %s to %s", path.size(), start, end);
    }
}



