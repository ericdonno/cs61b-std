package byog.lab5;
import edu.princeton.cs.algs4.StdDraw;
import org.junit.Test;
import static org.junit.Assert.*;

import byog.TileEngine.TERenderer;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;

import java.util.Random;

/**
 * Draws a world consisting of hexagonal regions.
 */
public class HexWorld {
    private static final int WIDTH = 80;
    private static final int HEIGHT = 80;


    public static void main(String[] args) {
        // initialize the tile rendering engine
        TERenderer ter = new TERenderer();
        ter.initialize(WIDTH, HEIGHT);

        // initialize tiles
        TETile[][] world = new TETile[WIDTH][HEIGHT];
        for (int x = 0; x < WIDTH; x += 1) {
            for (int y = 0; y < HEIGHT; y += 1) {
                world[x][y] = Tileset.NOTHING;
            }
        }

        /*
        Hexagon pio = new Hexagon(new Position(35,35), 3, Tileset.PLAYER);
        HexHoneyComb hc = new HexHoneyComb(pio, 3);
        hc.addLeft(0.5, Tileset.FLOWER);
        hc.addLeft(1,Tileset.random());
        hc.addRight(0.5,Tileset.random());

        for (int i = 0; i < 2; i++) hc.addVertical(0,1,-1,Tileset.random());
        for (int i = 0; i < 4; i++) hc.addVertical(1,1,-1,Tileset.random());
        for (int i = 0; i < 3; i++) hc.addVertical(-1,1,-1,Tileset.random());
        for (int i = 0; i < 4; i++) hc.addVertical(-2,1,-1,Tileset.random());


        hc.addSelf(world);
        for (double h: hc.leftOtherHight) System.out.println(h);
        ter.renderFrame(world);
        */

        HexCoorSys hcs = new HexCoorSys(new Position(35,35), 3, Tileset.PLAYER);

        for (int i = 0; i < 6; i++) {
            if (i%2==0) {
                for (int j = 0; j <= i; j++) hcs.addHex(i,j,Tileset.random());
            } else for (int j = 0; j <= i; j++) hcs.addHex(i,j+0.5, Tileset.random());
        }

        hcs.addHex(-2,-2,Tileset.random());

        hcs.addSelf(world);
        ter.renderFrame(world);
        System.out.println(TETile. toString(world));

    }
}
