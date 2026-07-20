package byog.AI;

import byog.Common.Direction;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BFS 寻路器。在地牢地图中计算从起点到终点的最短路径。
 */
public class BFSPathfinder {

    /**
     * 使用 BFS 计算从 start 到 goal 的最短路径。
     * @param start 起点位置
     * @param goal  目标位置
     * @param world 游戏世界瓦片数组
     * @return 从起点到终点的有序位置列表（不包含起点），不可达时返回空列表
     */
    public static List<Position> findPath(Position start, Position goal, TETile[][] world) {
        if (world == null) {
            return new ArrayList<>();
        }
        if (start.equals(goal)) {
            return new ArrayList<>();
        }

        int w = world.length;
        int h = world[0].length;

        boolean[][] visited = new boolean[w][h];
        Direction[][] cameFrom = new Direction[w][h];

        ArrayDeque<Position> queue = new ArrayDeque<>();
        visited[start.x][start.y] = true;
        queue.addLast(start);

        boolean found = false;
        while (!queue.isEmpty()) {
            Position cur = queue.removeFirst();
            if (cur.equals(goal)) {
                found = true;
                break;
            }

            for (Direction d : Direction.values()) {
                int nx = cur.x + d.dx;
                int ny = cur.y + d.dy;

                if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                    continue;
                }
                if (visited[nx][ny]) {
                    continue;
                }

                // goal 位置可能被 Entity 占据（tile 不是 FLOOR），特殊允许通过
                boolean isGoal = (nx == goal.x && ny == goal.y);
                if (!isGoal && !isPassable(world[nx][ny])) {
                    continue;
                }

                visited[nx][ny] = true;
                cameFrom[nx][ny] = d;
                queue.addLast(new Position(nx, ny));
            }
        }

        if (!found) {
            return new ArrayList<>();
        }

        // 从 goal 沿 cameFrom 回溯到 start
        List<Position> path = new ArrayList<>();
        Position cur = goal;
        while (!cur.equals(start)) {
            path.add(new Position(cur.x, cur.y));
            Direction d = cameFrom[cur.x][cur.y];
            cur = new Position(cur.x - d.dx, cur.y - d.dy);
        }

        Collections.reverse(path);
        return path;
    }

    /** 判断瓦片是否可通行（FLOOR）。 */
    private static boolean isPassable(TETile tile) {
        return tile.description().equals(Tileset.FLOOR.description());
    }
}
