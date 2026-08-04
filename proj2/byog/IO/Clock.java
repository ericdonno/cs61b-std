package byog.IO;

/**
 * 存档摘要使用的可注入时钟。只影响显示元数据，不进入 canonical gameplay。
 */
@FunctionalInterface
public interface Clock {

    /** 当前 epoch 毫秒（仅用于展示与排序）。 */
    long epochMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }
}
