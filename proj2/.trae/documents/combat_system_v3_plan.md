# 战斗系统 v3 设计方案（攻击动画 + 受击反馈）

## 一、需求分析

用户希望修改三个方面：

1. **攻击消耗蓄力**：即使周围没有敌人，按下空格也应消耗蓄力（当前只有击中敌人才消耗）
2. **攻击动画**：玩家攻击时，周围一圈格子变为橙色瓦片闪烁
3. **受击反馈**：玩家被敌人攻击时，玩家本身变成红色

## 二、现有架构分析

### 2.1 当前问题

| 问题 | 当前状态 | 需要修改 |
|------|----------|----------|
| 蓄力消耗 | 只有击中敌人时才消耗 | 按下空格就消耗 |
| 攻击动画 | 无 | 周围8格临时变为橙色瓦片 |
| 受击反馈 | 无 | 玩家变成红色若干帧 |

### 2.2 需要修改的核心模块

| 文件 | 修改内容 |
|------|----------|
| Tileset.java | 添加 PLAYER_HIT、ATTACK_FLASH 瓦片 |
| AttackAction.java | 修改蓄力消耗逻辑（立即消耗），设置玩家 hitTimer |
| Player.java | 添加受击状态（hitTimer），支持红色玩家瓦片 |
| Game.java | 攻击动画渲染，受击状态更新，帧计数器 |
| TETile.java | **不需要修改**，动画瓦片在 Tileset 中预定义即可 |

## 三、设计方案

### 3.1 蓄力消耗修改

```
修改 AttackAction.executePlayerAttack():
1. 检查蓄力是否已满（canAttack）
2. 立即重置蓄力（无论是否有敌人）  ← 核心修改
3. 执行范围攻击检测
4. 返回结果
```

### 3.2 攻击动画设计

```
动画机制：
- 玩家攻击时记录攻击帧号（attackFrame）
- buildActiveFrame() 中检测是否在攻击动画期间（当前帧 - attackFrame < 8）
- 攻击动画期间，将玩家周围8格替换为 Tileset.ATTACK_FLASH
- 8帧后恢复正常颜色

实现方式：
- Tileset 中预定义 ATTACK_FLASH 瓦片（橙色背景）
- Game 类添加 frameCounter 和 attackFrame 字段
- attackPlayer() 方法中记录当前帧号
- buildActiveFrame() 中根据 attackFrame 直接替换为 ATTACK_FLASH
```

### 3.3 受击反馈设计

```
受击机制：
- Player 类添加 hitTimer 字段（受击后倒计时）
- 玩家受到伤害时，设置 hitTimer = 8
- 每帧更新时递减 hitTimer
- hitTimer > 0 时，玩家显示红色瓦片（PLAYER_HIT）

实现方式：
- Tileset 添加 PLAYER_HIT 红色玩家瓦片（白字红底）
- Player 添加 hitTimer、setHitTimer()、updateHitTimer()、getDisplayTile()
- AttackAction.dealDamage() 中设置玩家 hitTimer
- buildActiveFrame() 中使用 player.getDisplayTile() 替代 player.getTile()
```

### 3.4 帧计数器

```
Game 类添加 frameCounter：
- 主循环中每帧递增
- 用于判断攻击动画和受击状态的持续时间
- 初始值为 0
```

## 四、代码修改计划

### 4.1 修改文件

| 文件 | 修改内容 |
|------|----------|
| Tileset.java | 添加 PLAYER_HIT、ATTACK_FLASH 瓦片 |
| AttackAction.java | 攻击时立即消耗蓄力，dealDamage 中设置玩家 hitTimer |
| Player.java | 添加 hitTimer、updateHitTimer()、getDisplayTile() |
| Game.java | 添加 frameCounter、attackFrame，修改 buildActiveFrame()、主循环、attackPlayer() |

### 4.2 详细设计

#### 4.2.1 Tileset.java 修改

```java
public static final TETile PLAYER = new TETile('@', Color.white, Color.black, "player");
public static final TETile PLAYER_HIT = new TETile('@', Color.white, Color.red, "player hit");
public static final TETile ATTACK_FLASH = new TETile('*', Color.orange, Color.orange, "attack flash");
```

#### 4.2.2 Player.java 修改

```java
public class Player extends Entity {
    // ... 现有属性

    private int hitTimer = 0;

    /** 设置受击状态，持续 N 帧 */
    public void setHitTimer(int frames) {
        this.hitTimer = frames;
    }

    /** 每帧更新受击状态 */
    public void updateHitTimer() {
        if (hitTimer > 0) {
            hitTimer--;
        }
    }

    /** 获取当前显示的瓦片（受击时返回红色） */
    public TETile getDisplayTile() {
        if (hitTimer > 0) {
            return Tileset.PLAYER_HIT;
        }
        return getTile();
    }
}
```

#### 4.2.3 AttackAction.java 修改

```java
private ActionResult executePlayerAttack(Player player) {
    if (!player.canAttack()) {
        return ActionResult.BLOCKED;
    }

    // 立即消耗蓄力（无论是否有敌人）← 核心修改
    player.resetCharge();

    Position pos = player.getPosition();
    int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
    int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};

    boolean hitAny = false;
    for (int i = 0; i < 8; i++) {
        Position targetPos = new Position(pos.x + dx[i], pos.y + dy[i]);
        Entity target = entityMgr.findEntityAt(targetPos);

        if (target instanceof Enemy enemy && enemy.isAlive()) {
            int damage = calculateDamage(player);
            dealDamage(enemy, damage);
            hitAny = true;
        }
    }

    return hitAny ? ActionResult.DAMAGE : ActionResult.SUCCESS;
}

private void dealDamage(Entity target, int damage) {
    if (target instanceof Player player) {
        player.setHp(Math.max(0, player.getHp() - damage));
        player.setHitTimer(8);       // 受击后8帧红色闪烁
        if (player.getHp() <= 0) {
            player.die();
        }
    } else if (target instanceof Enemy enemy) {
        enemy.setHp(Math.max(0, enemy.getHp() - damage));
        if (enemy.getHp() <= 0) {
            enemy.die();
        }
    }
}
```

#### 4.2.4 Game.java 修改

```java
public class Game {
    private long frameCounter = 0;
    private long attackFrame = -1;

    // === 主循环中添加 ===
    // 每帧递增
    // frameCounter++;

    // PLAYING 状态下更新玩家受击状态
    // if (player != null) {
    //     player.updateHitTimer();
    // }

    // === attackPlayer() 修改 ===
    private void attackPlayer() {
        if (player == null || !player.isAlive()) {
            return;
        }
        if (!player.canAttack()) {
            Logger.info("Charge not ready! (%d/%d)", player.getCharge(), player.getMaxCharge());
            return;
        }
        AttackAction action = new AttackAction(entityMgr, new Random(seed.hashCode()));
        action.execute(world, player);
        attackFrame = frameCounter;  // 记录攻击帧号
    }

    // === buildActiveFrame() 修改 ===
    public TETile[][] buildActiveFrame() {
        if (world == null || player == null) {
            return createEmptyWorld();
        }
        TETile[][] frame = TETile.copyOf(world);
        Position p = player.getPosition();
        frame[p.x][p.y] = player.getDisplayTile();  // ← 改用 getDisplayTile()
        for (Entity e : entityMgr.getAllEntities()) {
            if (e != player && e.isAlive()) {
                Position ep = e.getPosition();
                frame[ep.x][ep.y] = e.getTile();
            }
        }

        // 攻击动画：周围8格替换为橙色闪光瓦片，持续8帧
        if (attackFrame >= 0 && frameCounter - attackFrame < 8) {
            int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
            int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
            for (int i = 0; i < 8; i++) {
                int nx = p.x + dx[i];
                int ny = p.y + dy[i];
                if (nx >= 0 && nx < frame.length && ny >= 0 && ny < frame[0].length) {
                    frame[nx][ny] = Tileset.ATTACK_FLASH;
                }
            }
        }

        return frame;
    }
}
```

## 五、实施步骤

1. **Step 1**：修改 Tileset.java 添加 ATTACK_FLASH 和 PLAYER_HIT
2. **Step 2**：修改 Player.java 添加受击状态
3. **Step 3**：修改 AttackAction.java 调整蓄力消耗和受击设置
4. **Step 4**：修改 Game.java 添加帧计数器、攻击动画、buildActiveFrame
5. **Step 5**：编译测试验证
