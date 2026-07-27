package byog.Action;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * Cross-tick action buffer with explicit low/high-water marks.
 *
 * <p>The interactive agent runtime uses {@link #replaceWithBoundedPrefix(List)} and
 * {@link #appendBounded(List)}. The older enqueue methods remain as compatible
 * bounded aliases.</p>
 */
public class ActionQueue {
    public static final int DEFAULT_LOW_WATER = 2;
    public static final int DEFAULT_HIGH_WATER = 5;

    private final Queue<Action> queue;
    private final int lowWater;
    private final int highWater;

    public ActionQueue() {
        this(DEFAULT_LOW_WATER, DEFAULT_HIGH_WATER);
    }

    public ActionQueue(int lowWater, int highWater) {
        if (lowWater < 0) {
            throw new IllegalArgumentException("lowWater must be >= 0");
        }
        if (highWater <= lowWater) {
            throw new IllegalArgumentException(
                    "highWater must be greater than lowWater");
        }
        this.lowWater = lowWater;
        this.highWater = highWater;
        this.queue = new ArrayDeque<>();
    }

    /**
     * 入队单个动作。
     */
    public void enqueue(Action action) {
        if (action != null && queue.size() < highWater) {
            queue.add(action);
        }
    }

    /**
     * 批量入队动作列表。
     */
    public void enqueueAll(List<Action> actions) {
        appendBounded(actions);
    }

    /**
     * Clears the old plan and stores at most {@code highWater} actions.
     */
    public void replaceWithBoundedPrefix(List<? extends Action> actions) {
        clear();
        appendBounded(actions);
    }

    /**
     * Appends actions until the high-water capacity is reached.
     *
     * @return number of actions actually appended
     */
    public int appendBounded(List<? extends Action> actions) {
        if (actions == null) {
            return 0;
        }
        int appended = 0;
        for (Action action : actions) {
            if (queue.size() >= highWater) {
                break;
            }
            if (action != null) {
                queue.add(action);
                appended++;
            }
        }
        return appended;
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
        return queue.size() <= lowWater;
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

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int getLowWater() {
        return lowWater;
    }

    public int getHighWater() {
        return highWater;
    }

    public int remainingCapacity() {
        return Math.max(0, highWater - queue.size());
    }
}
