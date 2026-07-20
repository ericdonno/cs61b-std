package byog.IO;

import byog.Helper.Logger;
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

    /**
     * 保存游戏状态到默认路径。
     * @param data 要保存的游戏数据
     * @return 成功返回 true，失败返回 false
     */
    public static boolean save(GameSaveData data) {
        return save(data, DEFAULT_SAVE_PATH);
    }

    /**
     * 保存游戏状态到指定路径。
     * @param data 要保存的游戏数据
     * @param filepath 文件路径
     * @return 成功返回 true，失败返回 false
     */
    public static boolean save(GameSaveData data, String filepath) {
        Logger.subsection("Serializing");
        File f = new File(filepath);
        try {
            File parentDir = f.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            FileOutputStream fs = new FileOutputStream(f);
            ObjectOutputStream os = new ObjectOutputStream(fs);
            os.writeObject(data);
            os.close();
            Logger.info("Game saved to: %s", filepath);
            return true;
        } catch (IOException e) {
            Logger.error("Save failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * 从默认路径加载游戏状态。
     * @return 加载成功返回 GameSaveData，失败返回 null
     */
    public static GameSaveData load() {
        return load(DEFAULT_SAVE_PATH);
    }

    /**
     * 从指定路径加载游戏状态。
     * @param filepath 文件路径
     * @return 加载成功返回 GameSaveData，失败返回 null
     */
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
            Logger.info("Game loaded from: %s", filepath);
            return data;
        } catch (IOException | ClassNotFoundException e) {
            Logger.error("Load failed: %s", e.getMessage());
            return null;
        }
    }

    /**
     * 检查默认存档文件是否存在。
     * @return 存在返回 true，不存在返回 false
     */
    public static boolean saveExists() {
        return saveExists(DEFAULT_SAVE_PATH);
    }

    /**
     * 检查指定路径的存档文件是否存在。
     * @param filepath 文件路径
     * @return 存在返回 true，不存在返回 false
     */
    public static boolean saveExists(String filepath) {
        return new File(filepath).exists();
    }
}
