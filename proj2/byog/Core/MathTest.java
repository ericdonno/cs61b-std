package byog.Core;

import byog.Helper.Logger;
import byog.Helper.MathHelper;
import byog.TileEngine.TETile;
import byog.lab5.Position;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static byog.Core.RandomUtils.biasUniform;
import static byog.Core.RandomUtils.poisson;
import static org.junit.Assert.assertTrue;

public class MathTest {
    @Test
    public void biasUniformDistributionTest() {
        double bias = 10;
        Random r = new Random();
        int[] frequencies = new int[20];  // 用于统计0到10之间数字的频率
        for (int i = 0; i < 1000; i++) {
            int value = (int) biasUniform(r, 0, 20, bias);
            frequencies[value]++;  // 增加对应数值的计数
            System.out.print(value + " ");
        }

        // 输出频度统计
        System.out.println("\nFrequencies:");
        for (int i = 0; i < frequencies.length; i++) {
            System.out.println(i + ": " + frequencies[i]);
        }
    }

    @Test
    public void squareRoomTest() {
        final int WIDTH = 80;
        final int HEIGHT = 30;
        TETile[][] world = new TETile[WIDTH][HEIGHT];
        SquareRoom room = new SquareRoom(new Position(71,14), 10);
        System.out.println(room.isWithinBounds(world));
    }

    @Test
    public void testRandomSquareRoomWrd() {
        // 设置重复次数
        int repetitions = 100;
        double totalSurvivalRate = 0;
        int totalSurvivedRoom = 0;

        for (int i = 0; i < repetitions; i++) {
            // 捕获 System.out 输出
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;  // 保存原始的 System.out
            System.setOut(new PrintStream(baos));  // 重定向 System.out 到 ByteArrayOutputStream

            // 生成随机种子，调用方法
            String seed = System.currentTimeMillis() + "_" + i;  //重复实验速度太快，时间作为种子失去了随机性
            char[] charArray = seed.toCharArray();
            RandomUtils.shuffle(new Random(seed.hashCode()), charArray);
            seed = new String(charArray);
            String[] input = new String[1];
            input[0] = "n"+seed+":q";  // 示例输入
            Main.main(input);

            // 获取输出内容
            String output = baos.toString();

            // 正则表达式匹配输出中的数字
            Pattern pattern = Pattern.compile("Target room count: (\\d+)");
            Matcher matcher = pattern.matcher(output);

            int roomCount = -1;
            if (matcher.find()) {
                roomCount = Integer.parseInt(matcher.group(1));
            }

            pattern = Pattern.compile("Surviving rooms: (\\d+)");
            matcher = pattern.matcher(output);

            int roomSurviveCount = -1;
            if (matcher.find()) {
                roomSurviveCount = Integer.parseInt(matcher.group(1));
            }

            // 计算存活率并累加
            if (roomCount > 0) {
                double SurvivalRate = (double) roomSurviveCount / roomCount;
                totalSurvivalRate += SurvivalRate;
                totalSurvivedRoom += roomSurviveCount;
                originalOut.println(String.format("Room Count: %d, Survived: %d, Survival Rate: %.2f", roomCount, roomSurviveCount, SurvivalRate));
            }

            // 恢复原始的 System.out
            System.setOut(originalOut);
        }

        // 计算平均存活率
        double averageSurvivalRate = totalSurvivalRate / repetitions;
        double averageSurvival = (double) totalSurvivedRoom / repetitions;

        Logger.section("Test Results");
        Logger.info("After %d repetitions:", repetitions);
        Logger.info("Average Survival Rate: %.4f", averageSurvivalRate);
        Logger.info("Average Survival: %.2f", averageSurvival);

    }

    @Test
    public void poissonDistributionTest() {
        Random r = new Random();
        ArrayList<Integer> values = new ArrayList<>();
        HashMap<Integer, Integer> frequencies = new HashMap<>();
        Set<Integer> selectedIndices = new HashSet<>();

        int sum = 0;
        int capacity = 10000;
        for (int i = 0; i < capacity; i++) {
            int sizeOfOthers = 600;
            double lambda = (double)sizeOfOthers ;
            int v = poisson(r, lambda);
            if (v >= 0 && v < sizeOfOthers) {
                selectedIndices.add(v);
            }
            values.add(v);
            sum += v;
            frequencies.put(v, frequencies.getOrDefault(v, 0) + 1); // 计数
            System.out.print(v + " ");
        }
        System.out.println("\n"+selectedIndices.size()+"selected indices:");
        for (int i:selectedIndices) System.out.print(i+" ");

        // 输出频度统计
        System.out.println("\nFrequencies:");
        for (Map.Entry<Integer, Integer> entry : frequencies.entrySet()) {
            System.out.println(entry.getKey() + ": " + entry.getValue());
        }
        // 输出期望
        System.out.println("\nExpectation:" + (double)sum/capacity);
    }

    @Test
    public void temptest() {
        int[] a = {1,2};
        System.out.println(Arrays.toString(a));
        int[] b = MathHelper.poissonDistributedIndecies(new Random(),120,10);
        Arrays.sort(b);
        System.out.println(Arrays.toString(b));
    }
}