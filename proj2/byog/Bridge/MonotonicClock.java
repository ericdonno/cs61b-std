package byog.Bridge;

/**
 * Supplies elapsed-time measurements that are not affected by wall-clock changes.
 */
@FunctionalInterface
public interface MonotonicClock {

    /** Returns the current monotonic time in nanoseconds. */
    long nanoTime();

    /** Returns the production clock backed by {@link System#nanoTime()}. */
    static MonotonicClock systemClock() {
        return System::nanoTime;
    }
}
