package byog.Core;

import byog.Helper.MatrixGraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
