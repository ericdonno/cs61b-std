# 战斗系统 v2 设计方案（蓄力范围攻击）

## 一、需求分析

用户希望修改攻击方式：
1. **按键**：使用空格键攻击（替代原 F/R/C/V 方向键攻击）
2. **蓄力条**：攻击需要蓄力，蓄力条满了才能攻击
3. **范围攻击**：攻击对玩家周围一圈（8个方向）的敌人造成伤害

## 二、现有架构分析

### 2.1 当前攻击系统问题
- 方向键攻击（F/R/C/V）不符合用户偏好
- 单次攻击，无蓄力机制
- 单体攻击，无范围伤害

### 2.2 需要修改的核心模块

| 模块 | 当前状态 | 需要修改 |
|------|----------|----------|
| Player.java | 有攻击伤害属性 | 添加蓄力条属性（charge、maxCharge、chargeRate） |
| AttackAction.java | 单体方向攻击 | 修改为范围攻击（周围8格） |
| Game.java | 方向键攻击绑定 | 改为空格键，添加蓄力更新和UI |
| drawUIBar() | HP和敌人数量 | 添加蓄力条显示 |

## 三、设计方案

### 3.1 核心设计原则

| 原则 | 说明 |
|------|------|
| **蓄力机制** | 玩家移动时自动蓄力，蓄力满后可释放范围攻击 |
| **范围伤害** | 攻击对玩家周围8个方向的所有敌人造成伤害 |
| **空格键触发** | 简单直观的攻击键，蓄力满时按下释放攻击 |
| **UI反馈** | 顶部状态栏显示蓄力进度条 |

### 3.2 蓄力机制设计

```
蓄力条属性：
- maxCharge: 最大蓄力值（100）
- charge: 当前蓄力值（0~100）
- chargeRate: 每帧恢复量（2）
- isCharged: 是否已满（charge >= maxCharge）

蓄力规则：
1. 每帧自动恢复 charge += chargeRate
2. 蓄力满后停止恢复（charge = maxCharge）
3. 攻击释放后重置 charge = 0
4. 玩家死亡时蓄力停止
```

### 3.3 范围攻击设计

```
玩家周围8个方向检测：
    (x-1,y+1) (x,y+1) (x+1,y+1)
    (x-1,y)   (x,y)   (x+1,y)
    (x-1,y-1) (x,y-1) (x+1,y-1)
    
攻击逻辑：
1. 检查蓄力是否已满
2. 遍历周围8个位置
3. 对每个位置的敌人造成伤害
4. 重置蓄力条
5. 记录攻击日志
```

### 3.4 战斗流程

```
┌─────────────────────────────────────────────────┐
│  游戏主循环（每帧）                              │
│  ┌─────────────────────────────────────────┐   │
│  │  1. 更新蓄力条: charge += chargeRate    │   │
│  │     (上限 maxCharge)                    │   │
│  └─────────────────────────────────────────┘   │
│                     │                           │
│                     ▼                           │
│  ┌─────────────────────────────────────────┐   │
│  │  2. 处理输入: 空格 → 检查蓄力 → 攻击    │   │
│  └─────────────────────────────────────────┘   │
│                     │                           │
│                     ▼                           │
│  ┌─────────────────────────────────────────┐   │
│  │  3. 范围攻击: 遍历周围8格 → 造成伤害    │   │
│  │     → 重置蓄力为0                        │   │
│  └─────────────────────────────────────────┘   │
│                     │                           │
│                     ▼                           │
│  ┌─────────────────────────────────────────┐   │
│  │  4. 绘制UI: HP + 敌人数量 + 蓄力条     │   │
│  └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

## 四、代码修改计划

### 4.1 修改文件

| 文件 | 修改内容 |
|------|----------|
| Player.java | 添加蓄力条属性（charge、maxCharge、chargeRate）及 getter/setter |
| AttackAction.java | 修改为范围攻击，移除方向参数，检测周围8格敌人 |
| Game.java | 修改键位绑定（空格攻击），添加蓄力更新逻辑，添加蓄力条UI |

### 4.2 详细设计

#### 4.2.1 Player.java 修改

```java
public class Player extends Entity {
    private int hp;
    private int sightRange;
    private int attackDamage = 15;
    private int damageVariance = 5;
    
    // 蓄力条属性
    private int charge = 0;
    private int maxCharge = 100;
    private int chargeRate = 2;
    
    /** 每帧更新蓄力 */
    public void updateCharge() {
        if (isAlive()) {
            charge = Math.min(maxCharge, charge + chargeRate);
        }
    }
    
    /** 判断是否可以攻击 */
    public boolean canAttack() {
        return charge >= maxCharge;
    }
    
    /** 释放攻击，重置蓄力 */
    public void resetCharge() {
        charge = 0;
    }
    
    // getter/setter
    public int getCharge() { return charge; }
    public int getMaxCharge() { return maxCharge; }
}
```

#### 4.2.2 AttackAction.java 修改

```java
public class AttackAction implements Action {
    private EntityManager entityMgr;
    private Random random;
    
    public AttackAction(EntityManager entityMgr, Random random) {
        this.entityMgr = entityMgr;
        this.random = random;
    }
    
    @Override
    public ActionResult execute(TETile[][] world, Entity entity) {
        if (!(entity instanceof Player player)) {
            return ActionResult.BLOCKED;
        }
        
        if (!player.canAttack()) {
            return ActionResult.BLOCKED;
        }
        
        // 范围攻击：检测周围8个方向
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
                
                Logger.info("Player attacked Enemy#%d for %d damage!",
                        enemy.getId(), damage);
            }
        }
        
        if (hitAny) {
            player.resetCharge();
            return ActionResult.DAMAGE;
        }
        
        return ActionResult.BLOCKED;
    }
}
```

#### 4.2.3 Game.java 修改

```java
public class Game {
    // 键位绑定修改
    private void initKeyBindings() {
        keyBindings = new HashMap<>();
        keyBindings.put('w', () -> movePlayer(player, Direction.UP));
        keyBindings.put('s', () -> movePlayer(player, Direction.DOWN));
        keyBindings.put('a', () -> movePlayer(player, Direction.LEFT));
        keyBindings.put('d', () -> movePlayer(player, Direction.RIGHT));
        
        // 空格键攻击
        keyBindings.put(' ', () -> attackPlayer());
    }
    
    // 攻击方法修改
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
    }
    
    // 主循环中添加蓄力更新
    // 在 Enemies AI tick 之前添加：
    // if (player != null && currentState == GameState.PLAYING) {
    //     player.updateCharge();
    // }
    
    // UI 绘制添加蓄力条
    private void drawUIBar(boolean isPaused) {
        // ... 现有绘制
        // 蓄力条显示
        if (player != null) {
            double chargePercent = (double) player.getCharge() / player.getMaxCharge();
            // 绘制蓄力条背景
            StdDraw.setPenColor(new Color(50, 50, 50));
            StdDraw.filledRectangle(35, barY, 10, 0.3);
            // 绘制蓄力条进度
            if (player.canAttack()) {
                StdDraw.setPenColor(StdDraw.GREEN);
            } else {
                StdDraw.setPenColor(StdDraw.BLUE);
            }
            StdDraw.filledRectangle(35 - 10 + chargePercent * 10, barY, chargePercent * 10, 0.3);
            // 绘制边框
            StdDraw.setPenColor(StdDraw.WHITE);
            StdDraw.rectangle(35, barY, 10, 0.3);
            // 文字提示
            StdDraw.text(35, barY + 0.6, "CHARGE");
        }
    }
}
```

## 五、键位设计

| 按键 | 功能 |
|------|------|
| W/A/S/D | 移动 |
| 空格 | 蓄力满时释放范围攻击 |
| : + Q | 保存退出 |
| P | 暂停 |

## 六、风险与注意事项

### 6.1 边界情况

| 场景 | 处理方式 |
|------|----------|
| 蓄力未满按空格 | 提示蓄力不足，不执行攻击 |
| 周围无敌人 | 消耗蓄力但不造成伤害（可考虑优化） |
| 玩家死亡 | 停止蓄力更新 |

### 6.2 性能考虑

- 范围攻击只需检查8个格子，O(1) 复杂度
- 蓄力更新为简单算术运算，无性能影响

### 6.3 平衡性考虑

- 蓄力速度（chargeRate）决定攻击频率
- 攻击伤害（attackDamage）决定单次输出
- 可根据游戏体验调整参数

## 七、实施步骤

1. **Step 1**：修改 Player.java 添加蓄力条属性和方法
2. **Step 2**：修改 AttackAction.java 改为范围攻击
3. **Step 3**：修改 Game.java 键位绑定（空格攻击）
4. **Step 4**：修改 Game.java 主循环添加蓄力更新
5. **Step 5**：修改 Game.java UI 添加蓄力条显示
6. **Step 6**：编译测试验证
