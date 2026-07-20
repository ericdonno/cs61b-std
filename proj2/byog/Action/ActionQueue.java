package byog.Action;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public class ActionQueue {
    private Queue<Action> queue;

    public ActionQueue() {
        this.queue = new ArrayDeque<>();
    }

    /**
     * 入队单个动作。
     */
    public void enqueue(Action action) {
        queue.add(action);
    }

    /**
     * 批量入队动作列表。
     */
    public void enqueueAll(List<Action> actions) {
        queue.addAll(actions);
    }

    /**
     * 出队并返回队首动作，队列为空时返回 null。
     * @return 队首动作或 null
     */
    public Action poll() {
        return queue.poll();
    }

    /**
     * 判断队列是否需要补充动作。当队列长度 ≤ 2 时返回 true。
     * @return 是否需要补充
     */
    public boolean needRefill() {
        return queue.size() <= 2;
    }

    /**
     * 清空队列。用于中断重规划场景。
     */
    public void clear() {
        queue.clear();
    }

    /**
     * 返回队列当前长度。
     */
    public int size() {
        return queue.size();
    }
}
