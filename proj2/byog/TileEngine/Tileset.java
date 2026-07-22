package byog.TileEngine;

import java.awt.Color;
import java.util.Random;

/**
 * Contains constant tile objects, to avoid having to remake the same tiles in different parts of
 * the code.
 *
 * You are free to (and encouraged to) create and add your own tiles to this file. This file will
 * be turned in with the rest of your code.
 *
 * Ex:
 *      world[x][y] = Tileset.FLOOR;
 *
 * The style checker may crash when you try to style check this file due to use of unicode
 * characters. This is OK.
 */

public class Tileset {
    public static final TETile PLAYER = new TETile('@', Color.white, Color.black, "player");
    public static final TETile WALL = new TETile('#', new Color(216, 128, 128), Color.darkGray,
            "wall");
    public static final TETile FLOOR = new TETile('·', new Color(128, 192, 128), Color.black,
            "floor");
    /** 敌人视野内的地板瓦片：与 FLOOR 相同字符，深红背景 */
    public static final TETile FLOOR_FOV = new TETile('·',
            new Color(128, 192, 128), new Color(80, 20, 20), "floor (enemy sight)");
    public static final TETile NOTHING = new TETile(' ', Color.black, Color.black, "nothing");
    public static final TETile GRASS = new TETile('"', Color.green, Color.black, "grass");
    public static final TETile WATER = new TETile('≈', Color.blue, Color.black, "water");
    public static final TETile FLOWER = new TETile('❀', Color.magenta, Color.pink, "flower");
    public static final TETile LOCKED_DOOR = new TETile('█', Color.orange, Color.black,
            "locked door");
    public static final TETile UNLOCKED_DOOR = new TETile('▢', Color.orange, Color.black,
            "unlocked door");
    public static final TETile SAND = new TETile('▒', Color.yellow, Color.black, "sand");
    public static final TETile MOUNTAIN = new TETile('▲', Color.gray, Color.black, "mountain");
    public static final TETile TREE = new TETile('♠', Color.green, Color.black, "tree");

    public static final TETile ENEMY = new TETile('E', Color.red, Color.black, "enemy");
    public static final TETile STAIRS = new TETile('>', Color.green, Color.black, "stairs");
    public static final TETile PLAYER_HIT = new TETile('@', Color.white, Color.red, "player hit");
    public static final TETile ATTACK_FLASH = new TETile('*', Color.orange, Color.orange, "attack flash");

    /**
     * Except "NOTHING"
     * */
    public static TETile random() {
        TETile[] tiles = {PLAYER, WALL, FLOOR, GRASS, WATER, FLOWER, LOCKED_DOOR,
                UNLOCKED_DOOR, SAND, MOUNTAIN, TREE};
        Random rand = new Random();
        return tiles[rand.nextInt(tiles.length)];
    }
}


