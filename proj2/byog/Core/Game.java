package byog.Core;

import byog.Helper.Logger;
import byog.TileEngine.TERenderer;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Game {
    TERenderer ter = new TERenderer();
    public static final int WIDTH = 80;
    public static final int HEIGHT = 30;

    private TETile[][] world;
    private Player player;
    private List<Entity> entities;
    private Map<Character, Runnable> keyBindings;
    private String seed;

    // 游戏状态枚举
    private enum GameState {
        MENU, SEED_INPUT, PLAYING, QUIT_PENDING
    }

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
        // 初始化世界和实体
        entities = new ArrayList<>();
        player = null;

        // 状态机初始化
        GameState currentState = GameState.MENU;
        StringBuilder seedStr = new StringBuilder();
        input = input.toLowerCase();

        // 初始化键位映射
        initKeyBindings();

        // 逐个字符处理，绝对不需要在循环体内部手动改变 i 的值
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            switch (currentState) {
                case MENU:
                    if (c == 'n') {
                        currentState = GameState.SEED_INPUT; // 切换状态：准备接收数字
                    } else if (c == 'l') {
                        // 加载游戏存档逻辑
                        if (loadGameState()) {
                            Logger.section("Game started (loaded save).");
                            currentState = GameState.PLAYING;    // 加载完毕，进入游玩状态
                        }
                    } else if (c == 'q') {
                        // 退出游戏 (在此方法中可能直接 return 当前空帧)
                        return renderFrame();
                    }
                    break;

                case SEED_INPUT:
                    if (Character.isDigit(c)) {
                        seedStr.append(c); // 收集数字，状态不改变
                    } else if (c == 's') {
                        // 种子输入完毕，开始生成世界
                        world = generateWorld(seedStr.toString());
                        player = spawnPlayer(this.seed);
                        entities.add(player);

                        Logger.section("Game started (new game).");
                        currentState = GameState.PLAYING; // 世界生成完毕，进入游玩状态
                    } else {
                        // 处理异常输入，或者忽略
                        Logger.error("Invalid character in seed input.");
                    }
                    break;

                case PLAYING:
                    if (c == ':') {
                        currentState = GameState.QUIT_PENDING; // 玩家按下了 ':'，进入待退出状态
                    } else {
                        handlePlayerInput(c); // 正常处理 w, a, s, d 移动
                    }
                    break;

                case QUIT_PENDING:
                    if (c == 'q') {
                        // 保存游戏存档逻辑
                        saveGameState();
                        // 存档后退出 (对于 String 方法通常是停止处理并返回)
                        return renderFrame();
                    } else {
                        // 如果按了 ':' 但紧接着按的不是 'q' (比如误触)，退回游玩状态
                        currentState = GameState.PLAYING;
                        handlePlayerInput(c); // 把这个字符当做正常操作处理
                    }
                    break;
            }
        }

        // 渲染并返回最终帧
        TETile[][] frame = renderFrame();

        // To draw, for tests, comment this when testing "Survivals" or publishing
        TERenderer ter = new TERenderer();
        ter.initialize(WIDTH, HEIGHT);
        ter.renderFrame(frame);

        return frame;
    }

    /**
     * find the function for the input character and run it.
     * */
    private void handlePlayerInput(char c) {
        Runnable action = keyBindings.get(c);
        if (action != null) {
            action.run();
        } else {
            Logger.info("Unbind Key \"%c\"", c);
        }
    }

    private void initKeyBindings() {
        keyBindings = new HashMap<>();

        // 1. 基础移动 (利用 Lambda 表达式)
        keyBindings.put('w', () -> movePlayer(player, Direction.UP));
        keyBindings.put('s', () -> movePlayer(player, Direction.DOWN));
        keyBindings.put('a', () -> movePlayer(player, Direction.LEFT));
        keyBindings.put('d', () -> movePlayer(player, Direction.RIGHT));
    }

    private void movePlayer(Player player, Direction direction) {
        player.move(direction, this::isPlayerColliding);
    }

    /**
     * Collision Detection
     * Rule: a player stands on floors, and cannot walk on an entity
     * */
    private boolean isPlayerColliding(Position p) {
        if (!Player.canMoveTo(p, world)) {
            return true;
        }
        for (Entity e : entities) {
            if (e == player && e.getPosition().equals(p)) {
                return true;
            }
        }
        return false;
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

    /**
     * 用 seed 生成世界并记录到 seed 字段。
     * @return 生成后的 TETile[][]
     */
    private TETile[][] generateWorld(String seed) {
        this.seed = seed;
        return WorldGenerator.RandomSquareRoomWrd(createEmptyWorld(), seed);
    }

    private TETile[][] createEmptyWorld() {
        TETile[][] w = new TETile[WIDTH][HEIGHT];
        for (int x = 0; x < WIDTH; x += 1) {
            for (int y = 0; y < HEIGHT; y += 1) {
                w[x][y] = Tileset.NOTHING;
            }
        }
        return w;
    }

    /**
     * 在世界中随机放置玩家（用于新游戏）。
     * @param seed 用于随机放置的种子
     * @return 创建的 Player 对象
     */
    private Player spawnPlayer(String seed) {
        Player p = new Player();
        Player.initPlayer(p, world, seed);
        return p;
    }

    /**
     * 在指定位置放置玩家（用于读档）。
     * @param x 玩家 X 坐标
     * @param y 玩家 Y 坐标
     * @return 创建的 Player 对象
     */
    private Player spawnPlayerAt(int x, int y) {
        return new Player(new Position(x, y));
    }

    /**
     * 收集当前游戏状态并保存到文件。
     * 未来新增游戏机制时，只需在 extraData 中 put 新数据即可。
     */
    private void saveGameState() {
        Logger.section("Save Game");
        Logger.info("Saving game...");
        GameSaveData data = new GameSaveData();
        data.seed = this.seed;
        data.playerX = player.getPosition().x;
        data.playerY = player.getPosition().y;
        SaveLoadManager.save(data);
        Logger.info("Game saved successfully.");
    }

    /**
     * 从文件加载游戏状态并重建世界。
     * 世界由 seed 确定性重建，玩家位置从存档恢复。
     * @return 加载成功返回 true，失败返回 false
     */
    private boolean loadGameState() {
        if (!SaveLoadManager.saveExists()) {
            Logger.info("No save file found.");
            return false;
        }
        GameSaveData data = SaveLoadManager.load();
        if (data == null) {
            return false;
        }

        world = generateWorld(data.seed);
        player = spawnPlayerAt(data.playerX, data.playerY);
        entities = new ArrayList<>();
        entities.add(player);
        Logger.info("Game loaded successfully.");
        return true;
    }
}