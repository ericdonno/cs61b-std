import static org.junit.Assert.*;
import org.junit.Test;
import edu.princeton.cs.introcs.StdRandom;

public class TestArrayDequeGold {

    @Test
    public void testDeque() {
        // 创建 StudentArrayDeque 和 ArrayDequeSolution
        StudentArrayDeque<Integer> studentDeque = new StudentArrayDeque<>();
        ArrayDequeSolution<Integer> correctDeque = new ArrayDequeSolution<>();

        // 定义测试次数
        int numOperations = 1000;

        // 用于记录操作的序列
        StringBuilder operationSequence = new StringBuilder();

        for (int i = 0; i < numOperations; i++) {
            // 随机选择操作类型：0 -> addFirst, 1 -> addLast, 2 -> removeFirst, 3 -> removeLast
            int operation = StdRandom.uniform(4);
            Integer value;

            switch (operation) {
                case 0: // addFirst
                    value = StdRandom.uniform(100);  // 生成一个随机数
                    studentDeque.addFirst(value);
                    correctDeque.addFirst(value);
                    operationSequence.append("addFirst(").append(value).append(")\n");
                    break;
                case 1: // addLast
                    value = StdRandom.uniform(100);  // 生成一个随机数
                    studentDeque.addLast(value);
                    correctDeque.addLast(value);
                    operationSequence.append("addLast(").append(value).append(")\n");
                    break;
                case 2: // removeFirst
                    if (!studentDeque.isEmpty() && !correctDeque.isEmpty()) {
                        Integer studentResult = studentDeque.removeFirst();
                        Integer correctResult = correctDeque.removeFirst();
                        // 如果结果不同，输出操作序列并触发断言
                        assertEquals( operationSequence.toString(),
                                correctResult, studentResult);
                    }
                    break;
                case 3: // removeLast
                    if (!studentDeque.isEmpty() && !correctDeque.isEmpty()) {
                        Integer studentResult = studentDeque.removeLast();
                        Integer correctResult = correctDeque.removeLast();
                        // 如果结果不同，输出操作序列并触发断言
                        assertEquals(  operationSequence.toString(),
                                correctResult, studentResult);
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
