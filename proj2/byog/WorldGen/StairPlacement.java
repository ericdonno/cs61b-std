package byog.WorldGen;

import byog.lab5.Position;

import java.util.Objects;

/**
 * 楼梯放置结果：坐标与所在房间。调用方不再需要二次扫描世界猜测楼梯位置。
 */
public record StairPlacement(Position position, SquareRoom room) {

    public StairPlacement {
        Objects.requireNonNull(position, "position");
        // room 允许为 null：找不到合法楼梯房时返回类型化失败。
    }
}
