package byog.Core;

import byog.Helper.Logger;
import byog.Helper.MatrixGraph;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;


/**
 * 房间的数据结构。
 * 包含生成走廊的方法。
 * */
public class RoomGraph {
    private MatrixGraph<Room> graph;
    private Room firstRoom;

    public RoomGraph(List<? extends Room> allRoom) {
        graph = new MatrixGraph<>(allRoom.size());
        firstRoom = allRoom.get(0);

        for (Room r: allRoom) graph.addNode(r);
        for (int i = 0; i < allRoom.size(); i++) {
            for (int j = i + 1; j < allRoom.size(); j++) {
                Room room1 = allRoom.get(i);
                Room room2 = allRoom.get(j);
                graph.addEdge(room1, room2, room1.distanceTo(room2));  // 添加边
            }
        }
    }


    //越高层的代码，复杂度越高，访问等级越低

    List<Hall> mstHalls() {
        List<MatrixGraph.Edge<Room>> mstEdges = graph.getMinimumSpanningTree(firstRoom);
        ArrayList<Hall> mstHalls = new ArrayList<>();
        for (MatrixGraph.Edge e: mstEdges) {
            mstHalls.add(edgeToHall(e));
        }
        return mstHalls;
    }

    /**
     * 同时得到mstHall和除mst之外其他Hall
     */
    List<Hall>[] mstAndOtherHalls() {
        // 获取最小生成树的边
        List<Hall> mstHall = this.mstHalls();

        // 使用一个集合来快速判断是否属于MST
        Set<Hall> mstSet = new HashSet<>(mstHall);

        // 存储非MST的边
        List<Hall> otherHall = new ArrayList<>();

        // 遍历图中所有的边，将非MST的边加入otherHall
        for (MatrixGraph.Edge<Room> edge : graph.getAllEdges()) {
            Hall hall = edgeToHall(edge);
            if (!mstSet.contains(hall)) {
                otherHall.add(hall);
            }
        }

        // 返回结果
        return new List[]{mstHall, otherHall};
    }

    /**
     * 在 MST 拓扑树上计算从 fromPos 所在房间出发，图距离最远的房间。
     * 若 fromPos 不在任何房间内，fallback 到离 fromPos 最近的房间。
     */
    public Room findFarthestRoom(Position fromPos, List<SquareRoom> rooms) {
        // 1. 找到 fromPos 所在的房间
        Room startRoom = null;
        double minDist = Double.POSITIVE_INFINITY;
        for (SquareRoom r : rooms) {
            if (containsPosition(r, fromPos)) {
                startRoom = r;
                break;
            }
            double d = Math.abs(fromPos.x - r.getPosition().x)
                     + Math.abs(fromPos.y - r.getPosition().y);
            if (d < minDist) {
                minDist = d;
                startRoom = r;
            }
        }

        if (startRoom == null || rooms.isEmpty()) {
            return null;
        }

        // 2. 获取 MST 边
        List<MatrixGraph.Edge<Room>> mstEdges = graph.getMinimumSpanningTree(firstRoom);

        // 3. 在 MST 上构建邻接表
        Map<Room, List<MatrixGraph.Edge<Room>>> adj = new HashMap<>();
        for (MatrixGraph.Edge<Room> e : mstEdges) {
            adj.computeIfAbsent(e.from, k -> new ArrayList<>()).add(e);
            adj.computeIfAbsent(e.to, k -> new ArrayList<>()).add(
                    new MatrixGraph.Edge<>(e.to, e.from, e.weight));
        }

        // 4. BFS 计算从 startRoom 到各房间的图距离
        Map<Room, Double> distances = new HashMap<>();
        Queue<Room> queue = new LinkedList<>();
        Set<Room> visited = new HashSet<>();
        queue.add(startRoom);
        visited.add(startRoom);
        distances.put(startRoom, 0.0);

        while (!queue.isEmpty()) {
            Room current = queue.poll();
            double curDist = distances.get(current);
            List<MatrixGraph.Edge<Room>> neighbors = adj.get(current);
            if (neighbors == null) continue;
            for (MatrixGraph.Edge<Room> e : neighbors) {
                Room neighbor = e.to;
                if (!visited.contains(neighbor)) {
                    visited.add(neighbor);
                    distances.put(neighbor, curDist + e.weight);
                    queue.add(neighbor);
                }
            }
        }

        // 5. 找出距离最大的房间
        Room farthest = startRoom;
        double maxDist = 0;
        for (Map.Entry<Room, Double> entry : distances.entrySet()) {
            if (entry.getValue() > maxDist) {
                maxDist = entry.getValue();
                farthest = entry.getKey();
            }
        }

        Logger.info("Farthest room from %s: distance=%.2f", fromPos, maxDist);
        return farthest;
    }

    /** 判断坐标是否在房间内（包含墙壁边框）。 */
    private static boolean containsPosition(SquareRoom room, Position pos) {
        int rx = room.getPosition().x;
        int ry = room.getPosition().y;
        int sz = room.getSize();
        return pos.x >= rx && pos.x < rx + sz && pos.y >= ry && pos.y < ry + sz;
    }

    private Hall edgeToHall(MatrixGraph.Edge<Room> edge) {
        return new Hall(edge.from, edge.to);
    }


    /** For test */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(graph.toString());
        sb.append("\n Halls: "+graph.getEdgeCount());
        sb.append("\n FirstRoom: "+graph.findNodeIndex(firstRoom)+" "+firstRoom.getPosition());
        return sb.toString();
    }
}
