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
        ad.addLast(777);
        ad.addLast(5);
        ad.printDeque();
        int g = ad.get(3);   //here for get
        System.out.println(g);

        ad.addLast(6);
        ad.addLast(7);
        ad.addLast(8);
        ad.addLast(888);
        ad.addLast(6789);
        ad.addLast(0);
        ad.printDeque();
    }

    public static void main(String[] args) {
        addRemovePrintTest();
    }
}

