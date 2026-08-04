package byog.Entity;

import byog.Common.Direction;
import byog.IO.GameConfig;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

public class Player extends Entity {
    private int hp;
    private int maxHp;
    private int sightRange;
    private int attackDamage = 15;
    private int damageVariance = 5;

    private int charge = 0;
    private int maxCharge = 100;
    private int chargeRate = 2;

    private int hitTimer = 0;

    public Player() {
        this(new Position(0, 0), 100, 10);
    }

    public Player(Position position) {
        this(position, 100, 10);
    }

    public Player(Position position, int hp, int sightRange) {
        super(position, Tileset.PLAYER);
        this.hp = hp;
        this.maxHp = hp;
        this.sightRange = sightRange;
    }

    /** 从 GameConfig 创建玩家 */
    public Player(Position position, GameConfig config) {
        super(position, Tileset.PLAYER);
        this.hp = config.playerHp;
        this.maxHp = config.playerHp;
        this.sightRange = 10;
        this.attackDamage = config.playerAttack;
        this.damageVariance = config.playerDamageVariance;
        this.maxCharge = config.playerMaxCharge;
        this.chargeRate = config.playerChargeRate;
    }

    public int getHp() {
        return hp;
    }

    /** 当前难度配置的生命上限；本阶段不参与永久成长。 */
    public int getMaxHp() {
        return maxHp;
    }

    /** 设置当前生命值并收敛到 [0, maxHp]。 */
    public void setHp(int hp) {
        this.hp = Math.max(0, Math.min(maxHp, hp));
    }

    /** 治疗：恢复生命但不超过生命上限。amount 为负或零时无效果。 */
    public void heal(int amount) {
        if (amount > 0) {
            this.hp = Math.min(maxHp, hp + amount);
        }
    }

    public int getSightRange() {
        return sightRange;
    }

    public int getAttackDamage() {
        return attackDamage;
    }

    public void setAttackDamage(int attackDamage) {
        this.attackDamage = attackDamage;
    }

    public int getDamageVariance() {
        return damageVariance;
    }

    public void setDamageVariance(int damageVariance) {
        this.damageVariance = damageVariance;
    }

    public void setMaxCharge(int maxCharge) {
        this.maxCharge = maxCharge;
    }

    public void setChargeRate(int chargeRate) {
        this.chargeRate = chargeRate;
    }

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

    public int getCharge() {
        return charge;
    }

    /** 读档时恢复当前蓄力，收敛到 [0, maxCharge]。换层不调用此入口。 */
    public void restoreCharge(int charge) {
        this.charge = Math.max(0, Math.min(maxCharge, charge));
    }

    public int getMaxCharge() {
        return maxCharge;
    }

    public int getChargeRate() {
        return chargeRate;
    }

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

    /**
     * 通过 EntityManager 统一检测碰撞，尝试沿指定方向移动玩家。
     */
    public void move(Direction direction, TETile[][] world, EntityManager entityMgr) {
        Position newPos = getNewPosition(direction);
        if (entityMgr.canMoveTo(this, newPos, world)) {
            this.position = newPos;
            entityMgr.claimPosition(newPos);
        }
    }

    private Position getNewPosition(Direction direction) {
        int x = position.x;
        int y = position.y;
        switch (direction) {
            case UP:
                y += 1;
                break;
            case DOWN:
                y -= 1;
                break;
            case LEFT:
                x -= 1;
                break;
            case RIGHT:
                x += 1;
                break;
            default:
                break;
        }
        return new Position(x, y);
    }

    /**
     * 判断玩家能否站立在指定位置（不能是墙或虚空）。
     */
    public static boolean canStandOn(Position p, TETile[][] world) {
        return Entity.canStandOn(p, world);
    }

    /**
     * initialize a player in the certain world (or not)
     */
    public static void initPlayer(Player player, TETile[][] world, String seed) {
        Entity.initEntity(player, world, seed);
    }

    public static void initPlayer(Player player) {
    }
}
