package byog.Helper;

import java.util.*;

public class ListGraph<T> {
    /** 邻接表表示图 */
    private final Map<T, List<Edge<T>>> adjacencyList;

    public ListGraph() {
        this.adjacencyList = new HashMap<>();
    }

    /** 添加节点 */
    public void addNode(T node) {
        adjacencyList.putIfAbsent(node, new ArrayList<>());
    }

    /** 添加边，带权重 */
    public void addEdge(T from, T to, double weight) {
        adjacencyList.putIfAbsent(from, new ArrayList<>());
        adjacencyList.putIfAbsent(to, new ArrayList<>());
        adjacencyList.get(from).add(new Edge<>(from, to, weight));
        adjacencyList.get(to).add(new Edge<>(to, from, weight)); // 无向图
    }

    /** 获取节点列表 */
    public Set<T> getNodes() {
        return adjacencyList.keySet();
    }

    /** 获取最小生成树的边集合（Prim算法） */
    public List<Edge<T>> getMinimumSpanningTree(T startNode) {
        if (!adjacencyList.containsKey(startNode)) {
            throw new IllegalArgumentException("Start node not in graph");
        }

        List<Edge<T>> mstEdges = new ArrayList<>(); // 保存最小生成树的边
        Set<T> visited = new HashSet<>(); // 已访问的节点
        PriorityQueue<Edge<T>> edgeQueue = new PriorityQueue<>(Comparator.comparingDouble(e -> e.weight));

        // 从起始节点开始
        visited.add(startNode);
        edgeQueue.addAll(adjacencyList.get(startNode));

        while (!edgeQueue.isEmpty() && visited.size() < adjacencyList.size()) {
            Edge<T> edge = edgeQueue.poll(); // 获取权重最小的边

            if (visited.contains(edge.to)) {
                continue; // 跳过已访问的节点
            }

            // 将边加入MST，标记节点为已访问
            mstEdges.add(edge);
            visited.add(edge.to);

            // 将新节点的邻接边加入队列
            for (Edge<T> nextEdge : adjacencyList.get(edge.to)) {
                if (!visited.contains(nextEdge.to)) {
                    edgeQueue.add(nextEdge);
                }
            }
        }

        if (visited.size() < adjacencyList.size()) {
            throw new IllegalStateException("Graph is not connected, MST cannot span all nodes");
        }

        return mstEdges;
    }

    /** 边类，表示两个节点之间的连接 */
    public static class Edge<T> {
        public final T from;
        public final T to;
        public final double weight;

        public Edge(T from, T to, double weight) {
            this.from = from;
            this.to = to;
            this.weight = weight;
        }

        @Override
        public String toString() {
            return String.format("%s --(%s)--> %s", from, weight, to);
        }
    }

    public static void main(String[] args) {
        ListGraph<String> graph = new ListGraph<>();

        // 添加节点
        graph.addNode("A");
        graph.addNode("B");
        graph.addNode("C");
        graph.addNode("D");
        graph.addNode("E");

        // 添加边
        graph.addEdge("A", "B", 1);
        graph.addEdge("A", "C", 4);
        graph.addEdge("B", "C", 2);
        graph.addEdge("B", "D", 6);
        graph.addEdge("C", "D", 3);
        graph.addEdge("C", "E", 5);
        graph.addEdge("D", "E", 7);

        // 获取最小生成树
        List<Edge<String>> mst = graph.getMinimumSpanningTree("A");
        System.out.println("Minimum Spanning Tree:");
        for (Edge<String> edge : mst) {
            System.out.println(edge);
        }
    }
}
