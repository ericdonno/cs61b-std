public class ArrayDeque<T> {
    private T[] items;
    private int size;
    private int nextFirst;
    private int nextLast;

    private static final int REFACTOR = 2;
    private static final int STARTING_SIZE = 8;

    /** 构造空队列 */
    public ArrayDeque() {
        this.items = (T[]) new Object[STARTING_SIZE];
        this.size = 0;
        this.nextFirst = 0;
        this.nextLast = 1;
    }


    // Methods blow

    private void resize(int capacity) {
        T[] r = (T[]) new Object[capacity];
//        System.arraycopy(items,0, r ,0, size);     //相当于在原数组后面接了一串，不需要修改头尾指针   //有bug
        for (int i=0; i<size; i++) {
            r[i+1] = items[(nextFirst + 1 + i) % items.length];
        }
        nextFirst = 0;
        nextLast = size + 1;
        items = r;
    }

    public int size() {
        return this.size;
    }

    public boolean isEmpty() {
//        return this.nextFirst == this.nextLast;      //双端队列不能用头尾指针的相对位置判断空满!
        return size == 0;
    }

    public boolean isFull() {
//        return (this.nextLast+1) % items.length == this.nextFirst;      //双端队列不能用头尾指针的相对位置判断空满!
        return size == items.length;
    }

    public void addFirst(T item) {
        if (isFull()) {
            resize(REFACTOR * size);
        }
        items[nextFirst] = item;
        nextFirst = (nextFirst - 1 + items.length) % items.length;
        size++;
    }

    public void addLast(T item) {
        if (isFull()) {
            resize(REFACTOR * size);
        }
        items[nextLast] = item;
        nextLast = (nextLast + 1) % items.length;
        size++;
    }

    public T removeFirst() {
        if (isEmpty()) {
            return null;
        } else {
            nextFirst = (nextFirst + 1) % items.length;
            T out = items[nextFirst];
            items[nextFirst] = null;
            size--;
            return out;
        }
    }

    public T removeLast() {
        if (isEmpty()) {
            return null;
        } else {
            nextLast = (nextLast - 1 + items.length) % items.length;
            T out = items[nextLast];
            items[nextLast] = null;
            size--;
            return out;
        }
    }

    public T get(int index) {
        if ( index+1 > size || index < 0) {
            return null;
        } else return items[(nextFirst + 1 + index) % items.length];
    }

    public void printDeque() {
        for (int i=0; i<size; i++) {
            System.out.print(items[(nextFirst + 1 + i) % items.length].toString()+' ');
        }
        System.out.println();
    }
}


