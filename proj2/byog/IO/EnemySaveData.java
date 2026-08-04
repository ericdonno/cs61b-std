package byog.IO;

import byog.AI.PatrolState;
import byog.lab5.Position;

import java.io.Serializable;

/**
 * 类型化敌人快照：当前楼层敌人身体与巡视状态。
 * facing 使用 {@code Facing} 的 name 字符串，读档时转换并校验。
 */
public final class EnemySaveData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String agentId;
    private int x;
    private int y;
    private boolean alive;
    private int hp;
    private int maxHp;
    private int sightRange;
    private int attackDamage;
    private int damageVariance;
    private int moveInterval;
    private String facing;
    private PatrolState patrolState;

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Position getPosition() {
        return new Position(x, y);
    }

    public void setPosition(Position position) {
        this.x = position.x;
        this.y = position.y;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public int getHp() {
        return hp;
    }

    public void setHp(int hp) {
        this.hp = hp;
    }

    public int getMaxHp() {
        return maxHp;
    }

    public void setMaxHp(int maxHp) {
        this.maxHp = maxHp;
    }

    public int getSightRange() {
        return sightRange;
    }

    public void setSightRange(int sightRange) {
        this.sightRange = sightRange;
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

    public int getMoveInterval() {
        return moveInterval;
    }

    public void setMoveInterval(int moveInterval) {
        this.moveInterval = moveInterval;
    }

    public String getFacing() {
        return facing;
    }

    public void setFacing(String facing) {
        this.facing = facing;
    }

    public PatrolState getPatrolState() {
        return patrolState;
    }

    public void setPatrolState(PatrolState patrolState) {
        this.patrolState = patrolState;
    }
}
