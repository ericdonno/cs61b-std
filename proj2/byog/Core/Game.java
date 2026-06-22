package byog.Core;

import byog.Helper.Logger;
import byog.TileEngine.TERenderer;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import edu.princeton.cs.introcs.StdDraw;

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
        MENU, SEED_INPUT, PLAYING, QUIT_PENDING, QUIT
    }

    /**
     * Method used for playing a fresh game. The game should start from the main menu.
     */
    public void playWithKeyboard() {
        // 初始化
        ter.initialize(WIDTH, HEIGHT);
        entities = new ArrayList<>();
        player = null;
        initKeyBindings();

        // 状态机
        GameState currentState = GameState.MENU;
        StringBuilder seedStr = new StringBuilder();

        // 主循环
        while (currentState != GameState.QUIT) {
            // 处理输入
            if (StdDraw.hasNextKeyTyped()) {
                char c = Character.toLowerCase(StdDraw.nextKeyTyped());
                currentState = processInput(currentState, c, seedStr);
            }

            // 绘制
            draw(currentState, seedStr.toString());

            StdDraw.show();
            StdDraw.pause(16);
        }
    }

    /**
     * 处理输入的核心状态机方法，由 {@link #playWithKeyboard} 和 {@link #playWithInputString} 共用。
     * <p>
     * 根据当前游戏状态和输入字符，执行相应操作并返回下一状态：
     * <ul>
     *   <li><b>MENU</b>：{@code n} 进入种子输入，{@code l} 加载存档，{@code q} 退出</li>
     *   <li><b>SEED_INPUT</b>：数字追加到 seedStr，{@code s} 确认种子并生成世界、进入 PLAYING</li>
     *   <li><b>PLAYING</b>：字符交由 {@link #handlePlayerInput} 处理玩家操作</li>
     *   <li><b>QUIT_PENDING</b>："{@code :}" 进入退出待确认，
     *   {@code q} 保存并退出，其余字符取消退出并正常处理</li>
     * </ul>
     *
     * @param state   当前游戏状态
     * @param c       用户输入的字符（已转为小写）
     * @param seedStr 种子字符串构建器，在 SEED_INPUT 状态下收集数字
     * @return 转换后的下一个游戏状态；若无状态变化则返回原 state
     */
    private GameState processInput(GameState state, char c, StringBuilder seedStr) {
        switch (state) {
            case MENU:
                if (c == 'n') {
                    return GameState.SEED_INPUT;
                } else if (c == 'l') {
                    if (loadGameState()) {
                        Logger.section("Game started (loaded save).");
                        return GameState.PLAYING;
                    }
                    return GameState.MENU;
                } else if (c == 'q') {
                    return GameState.QUIT;
                }
                return state;

            case SEED_INPUT:
                if (Character.isDigit(c)) {
                    seedStr.append(c);
                } else if (c == 's') {
                    world = generateWorld(seedStr.toString());
                    player = spawnPlayer(this.seed);
                    entities.add(player);
                    Logger.section("Game started (new game).");
                    return GameState.PLAYING;
                } else {
                    Logger.error("Invalid character in seed input.");
                }
                return state;

            case PLAYING:
                if (c == ':') {
                    return GameState.QUIT_PENDING;
                } else {
                    handlePlayerInput(c);
                }
                return state;

            case QUIT_PENDING:
                if (c == 'q') {
                    saveGameState();
                    return GameState.QUIT;
                } else {
                    handlePlayerInput(c);
                    return GameState.PLAYING;
                }

            default:
                return state;
        }
    }

    /**
     * 根据状态绘制画面
     */
    private void draw(GameState state, String seed) {
        switch (state) {
            case MENU:
                drawMenu();
                break;
            case SEED_INPUT:
                drawSeedInput(seed);
                break;
            case PLAYING:
            case QUIT_PENDING:
                ter.renderFrame(renderFrame());
                break;
            default:
                break;
        }
    }

    /**
     * 绘制主菜单
     */
    private void drawMenu() {
        StdDraw.clear(StdDraw.BLACK);
        StdDraw.setPenColor(StdDraw.WHITE);
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 + 3, "CS61B: Build Your Own Game");
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0, "New Game (N)");
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 - 1, "Load Game (L)");
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 - 2, "Quit (Q)");
    }

    /**
     * 绘制种子输入界面
     */
    private void drawSeedInput(String seed) {
        StdDraw.clear(StdDraw.BLACK);
        StdDraw.setPenColor(StdDraw.WHITE);
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 + 2, "Enter seed number:");
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0, seed.isEmpty() ? "" : seed);
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 - 2, "Press 'S' to start");
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

        // 逐个字符处理
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            currentState = processInput(currentState, c, seedStr);

            // 处理需要立即返回的情况
            if (currentState == GameState.QUIT) {
                return renderFrame();
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