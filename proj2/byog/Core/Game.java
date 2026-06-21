package byog.Core;

import byog.Helper.Logger;
import byog.TileEngine.TERenderer;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;

public class Game {
    TERenderer ter = new TERenderer();
    /* Feel free to change the width and height. */
    public static final int WIDTH = 80;
    public static final int HEIGHT = 30;

    /**
     * Method used for playing a fresh game. The game should start from the main menu.
     */
    public void playWithKeyboard() {
    }

    /**
     * Method used for autograding and testing the game code. The input string will be a series
     * of characters (for example, "n123sswwdasdassadwas", "n123sss:q", "lwww". The game should
     * behave exactly as if the user typed these characters into the game after playing
     * playWithKeyboard. If the string ends in ":q", the same world should be returned as if the
     * string did not end with q. For example "n123sss" and "n123sss:q" should return the same
     * world. However, the behavior is slightly different. After playing with "n123sss:q", the game
     * should save, and thus if we then called playWithInputString with the string "l", we'd expect
     * to get the exact same world back again, since this corresponds to loading the saved game.
     * @param input the input string to feed to your program
     * @return the 2D TETile[][] representing the state of the world
     */
    public TETile[][] playWithInputString(String input) {
        // TODO: Fill out this method to run the game using the input passed in,
        // and return a 2D tile representation of the world that would have been
        // drawn if the same inputs had been given to playWithKeyboard().

        // initialize tiles
        TETile[][] world = new TETile[WIDTH][HEIGHT];
        for (int x = 0; x < WIDTH; x += 1) {
            for (int y = 0; y < HEIGHT; y += 1) {
                world[x][y] = Tileset.NOTHING;
            }
        }
        boolean wrdGenerated = false;

        // deal with input
        input = input.toLowerCase();
        int index = 0;
        while (index < input.length()) {
            char c = input.charAt(index);
            if (c == 'n') {
                index++;
                StringBuilder seedStr = new StringBuilder();
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    seedStr.append(input.charAt(index));
                    index++;
                }
                if (index < input.length() && input.charAt(index) == 's') {
                    index++;
                    world = WorldGenerator.RandomSquareRoomWrd(world, seedStr.toString());
                    wrdGenerated = true;
                } else {
                    Logger.error("Seeds end with 's'.");
                    System.exit(0);
                }
            } else if (c == 'l') {
                index++;
                //加载游戏
                wrdGenerated = true;
            } else if (c == ':') {
                index++;
                if (index < input.length() && input.charAt(index) == 'q') {
                    //保存游戏
                }
            } else if (wrdGenerated) {
                //处理玩家输入
            }
        }

        // To draw, for tests, comment this when testing "Survivals" or publishing
        TERenderer ter = new TERenderer();
        ter.initialize(WIDTH, HEIGHT);
        ter.renderFrame(world);

        return world;
    }
}
