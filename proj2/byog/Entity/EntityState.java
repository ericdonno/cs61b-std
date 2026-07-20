package byog.Entity;

import java.io.Serializable;

/** 实体的可序列化快照，用于存档 */
public class EntityState implements Serializable {
    private static final long serialVersionUID = 2L;

    public String type;    // "Player" | "Enemy"
    public int x, y;       // position
    public boolean alive;
    public int hp;
    public int sightRange;
    public int attackDamage;
    public int damageVariance;
    public int moveInterval;  // 仅 Enemy 使用
    public int maxCharge;     // 仅 Player 使用
    public int chargeRate;    // 仅 Player 使用
}
