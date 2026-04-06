package byog.lab5;

import byog.TileEngine.TETile;

/**
 * Basic biome unit
 * */
public class Hexagon {
    private Position p;
    private int size;
    private TETile style;

    public Hexagon(int size, TETile style) {   //unknow position
        this.size = size;
        this.style = style;
        this.p = new Position(Integer.MIN_VALUE, Integer.MIN_VALUE);
    }
    public Hexagon(Position p, int size, TETile style) {
        this.p = p;
        this.size = size;
        this.style = style;
    }

    public void addSelf(TETile[][] world) {
        addHexagon(world, this.p, this.size, this.style);
    }

    public void setPosition(Position p) {
        this.p = p;
    }

    public Position getPosition() {
        return this.p;
    }

    public String getStyle() {
        return style.description();
    }

    public static void addHexagon(TETile[][] world, Position p, int size, TETile style) {
        int i = 0;
        while(i<size) {
            int x0 = p.x - i;
            int y0 = p.y + i;
            for (int step = 0; step < size + 2*i; step++) {
                world[x0+step][y0] = style;
            }
            i++;
        }
        i--;
        int px2 = p.x;
        int py2 = p.y + size;
        while(i>=0) {
            int x0 = px2 - i;
            int y0 = py2 + size - 1 - i;
            for (int step = 0; step < size + 2*i; step++) {
                world[x0+step][y0] = style;
            }
            i--;
        }
    }
}




