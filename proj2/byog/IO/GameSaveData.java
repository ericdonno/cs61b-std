package byog.IO;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 游戏存档数据的纯数据容器 (DTO)。
 * 实现 Serializable 以便直接序列化到文件。
 *
 * 扩展性设计：
 *   extraData 是一个开放式 Map，未来任何游戏机制（道具、敌人、机关等）
 *   只需 put(key, value) 即可将自己的状态加入存档，
 *   无需修改本类的字段定义。
 */
public class GameSaveData implements Serializable {
    private static final long serialVersionUID = 20250622L;

    public String seed;

    public int playerX;

    public int playerY;

    public Map<String, Serializable> extraData;

    public Map<Integer, String> entityAgentIds;

    public GameSaveData() {
        extraData = new HashMap<>();
        entityAgentIds = new HashMap<>();
    }
}
