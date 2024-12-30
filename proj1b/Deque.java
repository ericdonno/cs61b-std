public interface Deque<T> {
    int size();
    void addFirst(T item);
    void addLast(T item);
    boolean isEmpty();
    void printDeque();
    T removeFirst();
    T removeLast();
    T get(int index);
}
