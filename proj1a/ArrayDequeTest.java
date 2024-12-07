public class ArrayDequeTest {
    private static void addRemovePrintTest() {
        ArrayDeque<Integer> ad = new ArrayDeque<>();
        ad.addFirst(1);
        ad.addFirst(536);
        ad.printDeque();
        int sb = ad.removeFirst();
        ad.printDeque();
        ad.addLast(666);
        ad.printDeque();
    }

    public static void main(String[] args) {
        addRemovePrintTest();
    }
}

