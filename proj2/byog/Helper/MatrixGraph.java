package byog.Helper;

import java.util.*;

public class MatrixGraph<T> {
    /** 节点到索引的映射 */
    private final Map<T, Integer> nodeToIndex;
    /** 索引到节点的映射 */
    private final List<T> indexToNode;
    /** 邻接矩阵 */
    private final double[][] adjacencyMatrix;
    private int size;

    public MatrixGraph(int capacity) {
        this.nodeToIndex = new HashMap<>();
        this.indexToNode = new ArrayList<>();
        this.adjacencyMatrix = new double[capacity][capacity];
        this.size = 0;

        for (int i = 0; i < capacity; i++) {
            Arrays.fill(adjacencyMatrix[i], Double.POSITIVE_INFINITY);
        }
    }

    /** For test */
    @Override
    public String toString() {
        double[][] array = adjacencyMatrix.clone();
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < array.length; i++) {
            sb.append("[");
            for (int j = 0; j < array[i].length; j++) {
                sb.append(String.format("%.2f", array[i][j]));
                if (j < array[i].length - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
            if (i < array.length - 1) {
                sb.append(", \n");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /** For test */
    public int findNodeIndex(T node) {
        Integer index = nodeToIndex.get(node);
        if (index == null) {
            throw new NoSuchElementException("Node not found in the graph");
        }
        return index;
    }

    /** 获取图中的节点数 */
    public int getNodeCount() {
        return size;
    }

    /** 获取图中的边数, O(size^2) */
    public int getEdgeCount() {
        int edgeCount = 0;
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) { // 只遍历上三角部分，避免重复计数
                if (adjacencyMatrix[i][j] < Double.POSITIVE_INFINITY) {
                    edgeCount++;
                }
            }
        }
        return edgeCount;
    }

    /** 获取图中的所有边, O(size^2) */
    public List<Edge<T>> getAllEdges() {
        List<Edge<T>> edges = new ArrayList<>();

        // 遍历邻接矩阵的上三角部分，避免重复添加边
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                if (adjacencyMatrix[i][j] < Double.POSITIVE_INFINITY) {
                    edges.add(new Edge<>(indexToNode.get(i), indexToNode.get(j), adjacencyMatrix[i][j]));
                }
            }
        }

        return edges;
    }


    /** 添加节点 */
    public void addNode(T node) {
        if (!nodeToIndex.containsKey(node)) {
            nodeToIndex.put(node, size);
            indexToNode.add(node);
            size++;
        }
    }

    /** 添加边，带权重 */
    public void addEdge(T from, T to, double weight) {
        if (!nodeToIndex.containsKey(from) || !nodeToIndex.containsKey(to)) {
            throw new IllegalArgumentException("Both nodes must be added before adding an edge.");
        }
        int fromIndex = nodeToIndex.get(from);
        int toIndex = nodeToIndex.get(to);
        adjacencyMatrix[fromIndex][toIndex] = weight;
        adjacencyMatrix[toIndex][fromIndex] = weight; // 无向图
    }

    /** 获取最小生成树的边集合（Prim算法），返回的是一个ArrayList */
    public List<Edge<T>> getMinimumSpanningTree(T startNode) {
        if (!nodeToIndex.containsKey(startNode)) {
            throw new IllegalArgumentException("Start node not in graph");
        }

        List<Edge<T>> mstEdges = new ArrayList<>(); // 保存最小生成树的边
        boolean[] visited = new boolean[size]; // 已访问的节点
        PriorityQueue<Edge<T>> edgeQueue = new PriorityQueue<>(Comparator.comparingDouble(e -> e.weight));
        int startIndex = nodeToIndex.get(startNode);

        visited[startIndex] = true;
        addEdgesToQueue(startIndex, edgeQueue);

        while (!edgeQueue.isEmpty()) {
            Edge<T> edge = edgeQueue.poll(); // 获取权重最小的边
            int toIndex = nodeToIndex.get(edge.to);

            if (visited[toIndex]) {
                continue; // 跳过已访问的节点
            }

            // 将边加入MST，标记节点为已访问
            mstEdges.add(edge);
            visited[toIndex] = true;

            // 将新节点的邻接边加入队列
            addEdgesToQueue(toIndex, edgeQueue);
        }

        if (mstEdges.size() < size - 1) {
            throw new IllegalStateException("Graph is not connected, MST cannot span all nodes");
        }

        return mstEdges;
    }

    /** 将节点的邻接边加入优先队列 */
    private void addEdgesToQueue(int nodeIndex, PriorityQueue<Edge<T>> edgeQueue) {
        for (int i = 0; i < size; i++) {
            if (i != nodeIndex && adjacencyMatrix[nodeIndex][i] < Double.POSITIVE_INFINITY) {
                edgeQueue.add(new Edge<>(indexToNode.get(nodeIndex), indexToNode.get(i), adjacencyMatrix[nodeIndex][i]));
            }
        }
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
        MatrixGraph<String> graph = new MatrixGraph<>(5);

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
