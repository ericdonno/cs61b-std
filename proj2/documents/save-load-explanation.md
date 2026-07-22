# Save & Load 功能实现详解

## 目录
1. [整体架构](#1-整体架构)
2. [为什么需要保存 seed 而不是整个世界](#2-为什么需要保存-seed-而不是整个世界)
3. [GameSaveData：存档数据容器](#3-gamesavedata存档数据容器)
4. [SaveLoadManager：文件读写工具](#4-saveloadmanager文件读写工具)
5. [Game 中的状态机改造](#5-game-中的状态机改造)
6. [如何扩展：添加新的存档数据](#6-如何扩展添加新的存档数据)

---

## 1. 整体架构

我们采用了**三层分离**的设计：

```
┌─────────────────────────────────────────────────────────┐
│                    Game.java                            │
│              (游戏逻辑层 - 状态机)                        │
│  负责：响应用户输入 'l' (加载) 和 ':q' (保存)              │
└───────────────────────┬─────────────────────────────────┘
                        │ 调用
┌───────────────────────▼─────────────────────────────────┐
│               SaveLoadManager.java                       │
│               (文件 I/O 层 - 静态工具类)                   │
│  负责：把 GameSaveData 写入文件 / 从文件读出               │
└───────────────────────┬─────────────────────────────────┘
                        │ 序列化/反序列化
┌───────────────────────▼─────────────────────────────────┐
│               GameSaveData.java                          │
│               (数据传输层 - DTO)                         │
│  负责：装要保存的所有游戏数据                             │
└─────────────────────────────────────────────────────────┘
```

**为什么要分成三层？**

| 层 | 职责 | 好处 |
|---|------|------|
| Game | 游戏逻辑 | 只管"什么时候存/取"，不管文件操作 |
| SaveLoadManager | 文件读写 | 只管"怎么读写文件"，不管游戏是什么 |
| GameSaveData | 数据容器 | 只管"存哪些数据"，结构清晰 |

---

## 2. 为什么需要保存 seed 而不是整个世界

### 2.1 什么是 seed？

`seed` 是生成随机世界的"种子"。看这行代码：

```java
final Random random = new Random(seed.hashCode());
```

Java 的 `Random` 是**伪随机数生成器**。给定同一个 seed，生成的随机数序列**完全相同**。

### 2.2 实验验证

```
seed = "123" → Random → 房间位置 [5,3], [12,8], ...
seed = "123" → Random → 房间位置 [5,3], [12,8], ...  ← 完全一样！
seed = "456" → Random → 房间位置 [2,7], [19,4], ...  ← 不一样
```

### 2.3 结论

| 保存内容 | 文件大小 | 重建难度 |
|----------|----------|----------|
| 整个 `TETile[][]` (80×30 = 2400 个 tile) | ~几十 KB | 不需要，直接用 |
| 只保存 seed | ~几个字节 | 用 seed 重新生成 |

**核心思想**：世界是由 seed **确定性生成**的，所以不需要保存整个世界，只需要保存 seed！

---

## 3. GameSaveData：存档数据容器

### 3.1 完整代码

```java
package byog.Core;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 游戏存档数据的纯数据容器 (DTO)。
 * 实现 Serializable 以便直接序列化到文件。
 */
public class GameSaveData implements Serializable {
    private static final long serialVersionUID = 20250622L;

    /** 世界生成种子，用于确定性重建世界 */
    public String seed;

    /** 玩家在地图中的 X 坐标 */
    public int playerX;

    /** 玩家在地图中的 Y 坐标 */
    public int playerY;

    /**
     * 开放式扩展数据。
     * 未来新增的游戏机制将自己的状态以 key-value 形式存入此 Map。
     */
    public Map<String, Serializable> extraData;

    public GameSaveData() {
        extraData = new HashMap<>();
    }
}
```

### 3.2 重要知识点

#### (1) `implements Serializable`

这是 Java 的**序列化接口**，标记这个类的对象可以被"打包"成字节流写入文件。

```java
public class GameSaveData implements Serializable  // ← 必须写这个
```

**为什么需要？** Java 的对象不能直接写入文件，必须先转换成字节序列（序列化），读出时再转回来（反序列化）。

#### (2) `serialVersionUID`

```java
private static final long serialVersionUID = 20250622L;
```

这是一个**版本号**。当类结构改变时（比如新增字段），Java 会检查序列化时的版本号和当前类的版本号是否一致。

| 场景 | 结果 |
|------|------|
| 版本号一致 | 正常反序列化 |
| 版本号不一致 | 抛出 `InvalidClassException` |

可以简单理解为"存档格式版本号"。

#### (3) `public` 字段 vs `private` + getter

这里故意用了 `public` 字段：

```java
public String seed;
public int playerX;
```

**为什么不用 getter/setter？** 这是一个纯数据容器（DTO），不需要任何逻辑。用 `public` 字段更简洁，减少样板代码。

为什么不把字段都放进`extraData` 中？明确核心字段，安全，代码自解释。

#### (4) `extraData`：开放式扩展

```java
public Map<String, Serializable> extraData;
```

这是一个**Map（键值对集合）**。当前只保存了 `seed`、`playerX`、`playerY` 三个字段，但未来如果添加"背包系统"、"敌人位置"等新功能，可以往 `extraData` 里塞，不需要修改这个类的代码。

**原理**：Map 的 key 是 String，value 必须是 `Serializable`（所有可序列化的对象都行）。

---

## 4. SaveLoadManager：文件读写工具

### 4.1 完整代码

```java
package byog.Core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * 游戏存档的静态文件 I/O 工具类。
 * 与 Game 类完全解耦，可被 playWithInputString 和 playWithKeyboard 共同复用。
 */
public class SaveLoadManager {

    /** 默认存档文件路径 */
    private static final String DEFAULT_SAVE_PATH = "./save/game.ser";

    public static boolean save(GameSaveData data) {
        return save(data, DEFAULT_SAVE_PATH);
    }

    public static boolean save(GameSaveData data, String filepath) {
        File f = new File(filepath);
        try {
            // 确保目录存在
            File parentDir = f.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            // 写文件
            FileOutputStream fs = new FileOutputStream(f);
            ObjectOutputStream os = new ObjectOutputStream(fs);
            os.writeObject(data);
            os.close();
            return true;
        } catch (IOException e) {
            System.out.println("Save failed: " + e.getMessage());
            return false;
        }
    }

    public static GameSaveData load() {
        return load(DEFAULT_SAVE_PATH);
    }

    public static GameSaveData load(String filepath) {
        File f = new File(filepath);
        if (!f.exists()) {
            return null;
        }
        try {
            FileInputStream fs = new FileInputStream(f);
            ObjectInputStream os = new ObjectInputStream(fs);
            GameSaveData data = (GameSaveData) os.readObject();
            os.close();
            return data;
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("Load failed: " + e.getMessage());
            return null;
        }
    }

    public static boolean saveExists() {
        return saveExists(DEFAULT_SAVE_PATH);
    }

    public static boolean saveExists(String filepath) {
        return new File(filepath).exists();
    }
}
```

### 4.2 重要知识点

#### (1) 静态工具类

```java
public class SaveLoadManager {
    public static boolean save(GameSaveData data) { ... }
    public static GameSaveData load() { ... }
}
```

没有 `new SaveLoadManager()`，直接 `SaveLoadManager.save(data)` 调用。

**为什么用静态类？**
- Save/Load 是纯"工具操作"，不依赖任何对象状态
- Game 调用它时不需要创建实例
- `playWithKeyboard()` 也可以直接调用（未来会用到）

#### (2) 文件读写流程

**保存（write）：**
```
GameSaveData → ObjectOutputStream → FileOutputStream → 文件 (game.ser)
```

**加载（read）：**
```
文件 (game.ser) → FileInputStream → ObjectInputStream → GameSaveData
```

#### (3) `try-catch` 异常处理

```java
try {
    // 可能抛出异常的代码
} catch (IOException | ClassNotFoundException e) {
    // 捕获并处理
}
```

文件操作可能失败（文件不存在、磁盘满了、权限问题等），必须用 `try-catch` 捕获。

#### (4) 目录自动创建

```java
File parentDir = f.getParentFile();
if (parentDir != null && !parentDir.exists()) {
    parentDir.mkdirs();
}
```

`./save/game.ser` 的父目录是 `save/`。如果是第一次保存，这个目录可能不存在。`mkdirs()` 会自动创建所有必要的父目录。

---

## 5. Game 中的状态机改造

### 5.1 新增 seed 字段

```java
private TETile[][] world;
private Player player;
private List<Entity> entities;
private Map<Character, Runnable> keyBindings;
private String seed;  // ← 新增：记录当前世界的 seed
```

### 5.2 保存 seed（生成世界时）

```java
case SEED_INPUT:
    if (Character.isDigit(c)) {
        seedStr.append(c);
    } else if (c == 's') {
        this.seed = seedStr.toString();  // ← 保存 seed
        world = WorldGenerator.RandomSquareRoomWrd(world, this.seed);
        ...
    }
```

### 5.3 加载存档（MENU 状态）

```java
case MENU:
    if (c == 'l') {
        if (loadGameState()) {           // ← 尝试加载
            currentState = GameState.PLAYING;
        }
    }
```

### 5.4 保存存档（QUIT_PENDING 状态）

```java
case QUIT_PENDING:
    if (c == 'q') {
        saveGameState();                  // ← 保存
        return renderFrame();
    }
```

### 5.5 `saveGameState()` 方法

```java
private void saveGameState() {
    GameSaveData data = new GameSaveData();
    data.seed = this.seed;
    data.playerX = player.getPosition().x;
    data.playerY = player.getPosition().y;
    SaveLoadManager.save(data);          // 委托给工具类
}
```

### 5.6 `loadGameState()` 方法

```java
private boolean loadGameState() {
    if (!SaveLoadManager.saveExists()) { // 检查存档是否存在
        return false;
    }
    GameSaveData data = SaveLoadManager.load();
    if (data == null) {
        return false;
    }

    this.seed = data.seed;

    // 重建世界（用 seed 确定性生成）
    world = new TETile[WIDTH][HEIGHT];
    for (int x = 0; x < WIDTH; x += 1) {
        for (int y = 0; y < HEIGHT; y += 1) {
            world[x][y] = Tileset.NOTHING;
        }
    }
    world = WorldGenerator.RandomSquareRoomWrd(world, this.seed);

    // 恢复玩家位置（直接用存档中的坐标）
    player = new Player(new Position(data.playerX, data.playerY));
    entities = new ArrayList<>();
    entities.add(player);

    initKeyBindings();
    return true;
}
```

---

## 6. 如何扩展：添加新的存档数据

假设你要添加一个"背包系统"。

### 6.1 定义背包数据类

```java
import java.io.Serializable;
import java.util.List;

public class InventoryData implements Serializable {
    public List<String> items;
    public int maxSize;
}
```

### 6.2 保存时注入数据

```java
private void saveGameState() {
    GameSaveData data = new GameSaveData();
    data.seed = this.seed;
    data.playerX = player.getPosition().x;
    data.playerY = player.getPosition().y;

    // 添加背包数据
    InventoryData inv = new InventoryData();
    inv.items = player.getInventory();
    inv.maxSize = 20;
    data.extraData.put("inventory", inv);  // ← 一行代码搞定

    SaveLoadManager.save(data);
}
```

### 6.3 加载时取出数据

```java
private boolean loadGameState() {
    ...
    // 重建世界和玩家
    ...

    // 恢复背包数据
    InventoryData inv = (InventoryData) data.extraData.get("inventory");
    if (inv != null) {
        player.setInventory(inv.items);
        player.setMaxSize(inv.maxSize);
    }

    initKeyBindings();
    return true;
}
```

**核心思想**：`extraData` 是一个开放的"插线板"，任何新功能只需要"插上去"，不需要改 `GameSaveData` 的字段定义。

---

## 总结

| 概念 | 作用 |
|------|------|
| Serializable | 标记类可以被序列化/反序列化 |
| serialVersionUID | 序列化版本号，防止不兼容的类互相读取 |
| DTO (Data Transfer Object) | 纯数据容器，无业务逻辑 |
| 静态工具类 | 不需要实例化，直接调方法 |
| seed 确定性生成 | 相同 seed → 相同世界，无需保存整个地图 |
| extraData Map | 开放式扩展槽，未来功能"即插即用" |
