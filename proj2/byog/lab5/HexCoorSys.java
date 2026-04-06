package byog.lab5;

import byog.TileEngine.TETile;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Objects;


public class HexCoorSys implements Iterable<Hexagon> {
    private Hexagon origin;
    private final int size;
    private final int X_UNIT_LEN;
    private final int Y_UNIT_LEN;
    HashMap<HexCoordinate, Hexagon> hexSet;

    /**
     * @param p position of the origin in the world
     * @param size Hexagons in one CoorSys must have the same size
     * @param style style of the origin
     * */
    public HexCoorSys(Position p, int size, TETile style) {
        this.size = size;
        X_UNIT_LEN = 2 * size - 1;
        Y_UNIT_LEN = 2 * size;
        origin = new Hexagon(p, size, style);
        this.hexSet = new HashMap<>();
        hexSet.put(new HexCoordinate(0,0), origin);
    }


    private Position calculatePosition(int x, double y) {
        Position op = origin.getPosition();
        return new Position(op.x + x*X_UNIT_LEN, (int) (op.y + y*Y_UNIT_LEN));
    }

    public boolean addHex(int x, double y, TETile style) {
        HexCoordinate coordinate = new HexCoordinate(x, y);
        if (hexSet.containsKey(coordinate)) {
            return false;
        }
        Hexagon newHex = new Hexagon(calculatePosition(x, y), size, style);
        hexSet.put(coordinate, newHex);
        return true;
    }

    public boolean removeHex(int x, double y) {
        HexCoordinate coordinate = new HexCoordinate(x, y);
        if (!hexSet.containsKey(coordinate)) {
            return false;
        }
        if (x==0 && y==0) {
            throw new IllegalArgumentException("原点不可删除只可覆盖");   //changeHex尚未实现
        }
        hexSet.remove(coordinate);
        return true;
    }

    public Hexagon getHex(int x, double y) {
        return hexSet.get(new HexCoordinate(x, y));
    }

    public boolean hasHex(int x, double y) {
        return hexSet.containsKey(new HexCoordinate(x, y));
    }

    public void addSelf(TETile[][] world) {
        for(Hexagon h:this) {
            h.addSelf(world);
        }
    }

    @Override
    public Iterator<Hexagon> iterator() {
        return hexSet.values().iterator();
    }

}

class HexCoordinate {
    private int x;
    private double y;

    HexCoordinate(int x, double y) {
        if (x % 2 == 0 && y % 1 != 0) {
            throw new IllegalArgumentException("x为偶数时，y必须为整数");
        }
        if (x % 2 != 0 && y % 1 != 0.5) {
            throw new IllegalArgumentException("x为奇数时，y必须为整数加0.5");
        }
        this.x = x;
        this.y = y;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true; // 如果是同一个对象，返回true
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false; // 如果obj为null或者类型不同，返回false
        }
        HexCoordinate that = (HexCoordinate) obj; // 将obj强制转换为HexCoordinate
        return x == that.x && Double.compare(that.y, y) == 0; // 比较x和y
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y);
    }

    @Override
    public String toString() {
        return String.format("HexCoordinate:(%d, %.1f)", x, y);
    }

}
