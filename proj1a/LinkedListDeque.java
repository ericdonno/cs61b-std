/** 不使用哨兵节点
 * ：实现起来有很多特殊情况需要考虑，很麻烦。
 * */

public class LinkedListDeque<T> {
    private static class Node<T> {
        T data;
        Node<T> prev;
        Node<T> next;

        Node(T data) {
            this.data = data;
            this.prev = null;
            this.next = null;
        }
        Node(T data, Node<T> prev, Node<T> next ) {
            this.data = data;
            this.prev = prev;
            this.next = next;
        }
    }

    private Node<T> first;
    private Node<T> last;
    private int size;

    public LinkedListDeque(){
        this.size = 0;
        this.first = null;
        this.last = null;
    }

    // Methods below

    public int size(){
        return this.size;
    };

    public void addFirst(T item){     //虽然简洁，但感觉可读性不是很高，并非正常的思维过程，有些过于简洁了
        Node<T> newNode = new Node<>(item, null, this.first);
        if (this.isEmpty()) {      //原first是否存在
            this.last = newNode;
        } else {
            this.first.prev = newNode;
        }
        this.first = newNode;     //最后更新first指针
        this.size++;
    }

    public void addLast(T item){
        Node<T> newNode = new Node<>(item, this.last, null);
        if (this.isEmpty()) {
            this.first = newNode;
        } else {
            this.last.next = newNode;
        }
        this.last = newNode;
        this.size++;
    }

    public T removeFirst() {
        if (this.isEmpty()) {
            return null;
        }
        T out = this.first.data;
        if (this.size == 1){
            this.last = null;
            this.first = null;
        } else {
            this.first = this.first.next;
            this.first.prev = null;
        }
        this.size--;
        return out;
    }

    public T removeLast() {
        if (this.isEmpty()) {
            return null;
        }
        T out = this.last.data;
        if (this.size == 1){     //只剩一个节点的特殊情况
            this.last = null;
            this.first = null;
        } else {
            this.last = this.last.prev;
            this.last.next = null;
        }
        this.size--;
        return out;
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public T get(int index) {
        if (index+1 <= size/2) {       //判断从头还是从尾进入，节省1倍时间
            Node<T> p = this.first;
            for (int i=0; i<index; i++) {   //循环结束后p指向index位node
                p = p.next;
            }
            return p.data;
        } else if (index+1 > size/2 && index+1 <= size) {
            Node<T> p = this.last;
            for (int i=size-1; i>index; i--) {   //循环结束后p指向index位node
                p = p.prev;
            }
            return p.data;
        } else return null;   //不进入
    }

    public T getRecursive(int index) {
        if ( index+1 > this.size || index < 0) {    //空队列索引也越界
            return null;
        } else return getRecursiveHelper(first, index);
    }
    /** helper */
    private T getRecursiveHelper(Node<T> current, int index) {
        if (current == null) {     //为了代码安全。如果仅仅从getRecursive访问getRecursiveHelper,是不会出现这种情况的。
            return null;
        } else if (index == 0){
            return current.data;
        } else return getRecursiveHelper(current.next, index - 1);
    }

    public void printDeque() {
        Node<T> p = this.first;
        while (p == null) {
            System.out.print(p.data.toString()+' ');
            p = p.next;
        }
        System.out.println();
    }
}