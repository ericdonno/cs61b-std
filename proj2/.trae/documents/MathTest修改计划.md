# MathTest.java 修改计划

## 问题分析

由于 WorldGenerator.java 的输出格式已更新，MathTest.java 中的正则表达式匹配和输出格式需要同步更新：

1. **正则表达式匹配失效** - 第72行和第80行的正则表达式需要更新以匹配新的 Logger 输出格式
2. **字符串拼接格式问题** - 第93行、第106-108行的输出格式需要优化

## 修改步骤

### 步骤1：更新正则表达式匹配

**第72行：**
- 原正则：`"Room:(\\d+)"`
- 新正则：`"Target room count: (\\d+)"` 或 `"Room: (\\d+)"`
- 说明：Logger.debug 输出格式为 `[DEBUG] Target room count: %d`

**第80行：**
- 原正则：`"Room survive:(\\d+)"`
- 新正则：`"Surviving rooms: (\\d+)"`
- 说明：Logger.info 输出格式为 `[INFO] Surviving rooms: %d`

### 步骤2：优化输出格式

**第93行：**
- 原格式：`"roomCount:"+roomCount+" roomSurviveCount:"+roomSurviveCount+" SurvivalRate:"+String.format("%.2f", SurvivalRate)`
- 新格式：使用 Logger 或统一格式化字符串：
  - `"Room Count: %d, Survived: %d, Survival Rate: %.2f"`

**第106-108行：**
- 原格式：
  ```java
  System.out.println("After"+repetitions+" repetitions: ");
  System.out.println("Average Survival Rate : " + averageSurvivalRate);
  System.out.println("Average Survival : " + averageSurvival);
  ```
- 新格式：
  ```java
  Logger.section("Test Results");
  Logger.info("After %d repetitions:", repetitions);
  Logger.info("Average Survival Rate: %.4f", averageSurvivalRate);
  Logger.info("Average Survival: %.2f", averageSurvival);
  ```

### 步骤3：添加 Logger import

在文件开头添加：
```java
import byog.Helper.Logger;
```

## 涉及文件

- 修改：`byog/Core/MathTest.java`

## 注意事项

1. 正则表达式需要匹配 Logger 输出的完整格式（包含 `[DEBUG]` 或 `[INFO]` 前缀）
2. 测试代码的输出可以保持使用 System.out 或改用 Logger，根据需要选择
3. 确保正则表达式能正确捕获数字