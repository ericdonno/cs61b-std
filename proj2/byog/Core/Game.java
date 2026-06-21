package byog.Core;

import byog.Helper.Logger;
import byog.TileEngine.TERenderer;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.List;

public class Game {
    TERenderer ter = new TERenderer();
    public static final int WIDTH = 80;
    public static final int HEIGHT = 30;

    private TETile[][] world;
    private Player player;
    private List<Entity> entities;

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
        world = new TETile[WIDTH][HEIGHT];
        for (int x = 0; x < WIDTH; x += 1) {
            for (int y = 0; y < HEIGHT; y += 1) {
                world[x][y] = Tileset.NOTHING;
            }
        }
        entities = new ArrayList<>();
        boolean wrdGenerated = false;
        player = null;

        // deal with input
        input = input.toLowerCase();
        int index = 0;
        String seed = null;
        while (index < input.length()) {
            char c = input.charAt(index);
            if (c == 'n') {
                index++;
                StringBuilder seedStr = new StringBuilder();
                while (index < input.length() && Character.isDigit(input.charAt(index))) {  // 读取种子序列
                    seedStr.append(input.charAt(index));
                    index++;
                }
                seed = seedStr.toString();
                if (index < input.length() && input.charAt(index) == 's') {
                    index++;
                    world = WorldGenerator.RandomSquareRoomWrd(world, seed);
                    wrdGenerated = true;
                    player = new Player();
                    Player.initPlayer(player, world, seed);
                    entities.add(player);
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
            } else if (wrdGenerated && player != null) {
                //处理玩家输入
                handlePlayerInput(c);
                index++;
            } else {
                index++;
            }
        }

        // 加载世界状态
        TETile[][] frame = renderFrame();

        // To draw, for tests, comment this when testing "Survivals" or publishing
        TERenderer ter = new TERenderer();
        ter.initialize(WIDTH, HEIGHT);
        ter.renderFrame(frame);

        return frame;
    }

    /**
     * Change the player's
     * */
    private void handlePlayerInput(char c) {
        Player.Direction dir = null;
        switch (c) {
            case 'w':
                dir = Player.Direction.UP;
                break;
            case 's':
                dir = Player.Direction.DOWN;
                break;
            case 'a':
                dir = Player.Direction.LEFT;
                break;
            case 'd':
                dir = Player.Direction.RIGHT;
                break;
            default:
                return;
        }

        player.move(dir, this::canMoveTo);
    }

    private boolean canMoveTo(Position p) {
        if (!Player.canMoveTo(p, world)) {
            return false;
        }
        for (Entity e : entities) {
            if (e != player && e.getPosition().equals(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * combine the world and entities
     * */
    public TETile[][] renderFrame() {
        TETile[][] frame = TETile.copyOf(world);
        Position p = player.getPosition();
        frame[p.x][p.y] = player.getTile();
        for (Entity e : entities) {
            if (e != player) {
                Position ep = e.getPosition();
                frame[ep.x][ep.y] = e.getTile();
            }
        }
        return frame;
    }
}