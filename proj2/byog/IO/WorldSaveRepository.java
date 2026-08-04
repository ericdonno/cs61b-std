package byog.IO;

import java.util.List;

/**
 * 命名世界存档端口。生产实现只访问注入的 {@code save/worlds} 根目录；
 * 测试注入临时根目录。调用方不接触文件路径。
 */
public interface WorldSaveRepository {

    /** 列出所有世界（可读摘要或坏档路径），按名称判重键、worldId 稳定排序。 */
    List<WorldSaveEntry> list();

    /** 原子保存；失败不报告成功，且不破坏旧文件。 */
    SaveResult save(GameSaveData data);

    /** 读取并完整校验一个世界；失败返回稳定原因。 */
    LoadResult load(String worldId);

    /** 判重：不区分大小写地检查世界名是否已存在。 */
    boolean nameExists(String normalizedName);
}
