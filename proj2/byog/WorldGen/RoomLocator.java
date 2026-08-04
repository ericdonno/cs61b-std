package byog.WorldGen;

import byog.lab5.Position;

import java.util.List;

/**
 * 房间归属查询：判断一个坐标属于哪个房间的内部地板。
 * 生成苹果、判定出生房间时使用同一份实现，避免各调用方复制排除逻辑。
 */
public final class RoomLocator {

    private RoomLocator() {
    }

    /** 返回包含该坐标（内部地板区域）的房间；不属于任何房间时返回 null。 */
    public static SquareRoom roomContaining(List<SquareRoom> rooms, Position p) {
        if (rooms == null || p == null) {
            return null;
        }
        for (SquareRoom room : rooms) {
            if (room.containsFloor(p)) {
                return room;
            }
        }
        return null;
    }
}
