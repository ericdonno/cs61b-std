package byog.Core;

import java.io.Serializable;

/** 实体的可序列化快照，用于存档 */
public class EntityState implements Serializable {
    private static final long serialVersionUID = 1L;

    public String type;    // "Player" | "Enemy"
    public int x, y;       // position
    public boolean alive;
    public int hp;
    public int sightRange;
}
