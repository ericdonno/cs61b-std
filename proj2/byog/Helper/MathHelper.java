package byog.Helper;

import java.util.*;

import static byog.Core.RandomUtils.poisson;
import static byog.Core.RandomUtils.uniform;

public class MathHelper {
    /**
     * 返回一个数组 {b,c}，b小于c
     * */
    public static int[] findMiddleTwo(int a, int b, int c, int d) {
        int[] numbers = {a, b, c, d};
        // 对四个数进行排序
        java.util.Arrays.sort(numbers);
        // 返回第二小和第二大的数
        return new int[] {numbers[1], numbers[2]};
    }

    /**
     * @return 返回泊松分布的下标，下标有可能会重复，且数组为均匀分布的
     * */
    public static int[] poissonDistributedIndecies(Random random, int size, int needed) {
        Set<Integer> generatedIndices = new HashSet<>();
        int capacity = 1000;   //1000可以获得
        for (int i = 0; i < capacity; i++) {
            double lambda = (double)size/6 ;
            int v = poisson(random, lambda);
            if (v >= 0 && v < size) {
                generatedIndices.add(v);
            }
        }
        // 转换为 List
        List<Integer> indexList = new ArrayList<>(generatedIndices);
        ArrayList<Integer> pickIndexlist = new ArrayList<>();
        for (int i = 0; i < needed; i++) {
            int index = uniform(random, 0, indexList.size());
            pickIndexlist.add(indexList.get(index));
        }
        return pickIndexlist.stream().mapToInt(Integer::intValue).toArray();
    }

}
