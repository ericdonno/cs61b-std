package byog.Core;

import byog.Helper.Logger;
import byog.TileEngine.TERenderer;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.lab5.Position;
import edu.princeton.cs.introcs.StdDraw;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Game {
    TERenderer ter = new TERenderer();
    public static final int WIDTH = 80;
    public static final int HEIGHT = 30;
    public static final int UI_HEIGHT = 2;
    public static final int WINDOW_HEIGHT = HEIGHT + UI_HEIGHT;

    private TETile[][] world;
    private Player player;
    private EntityManager entityMgr;
    private Map<Character, Runnable> keyBindings;
    private String seed;

    // 游戏状态枚举
    private enum GameState {
        MENU, SEED_INPUT, PLAYING, PAUSED, QUIT_PENDING, QUIT
    }

    // 暂停按钮常量（位于顶部 UI 栏内）
    private static final double BTN_CENTER_X = 76.0;
    private static final double BTN_CENTER_Y = HEIGHT + UI_HEIGHT / 2.0;
    private static final double BTN_HALF_W = 3.5;
    private static final double BTN_HALF_H = 0.75;

    // 鼠标边沿检测
    private boolean mouseWasPressed = false;

    /**
     * Method used for playing a fresh game. The game should start from the main menu.
     */
    public void playWithKeyboard() {
        // 初始化
        ter.initialize(WIDTH, WINDOW_HEIGHT);
        entityMgr = new EntityManager();
        player = null;
        initKeyBindings();

        // 状态机
        GameState currentState = GameState.MENU;
        StringBuilder seedStr = new StringBuilder();

        // 主循环
        while (currentState != GameState.QUIT) {
            // 处理键盘输入
            if (StdDraw.hasNextKeyTyped()) {
                char c = Character.toLowerCase(StdDraw.nextKeyTyped());
                currentState = processInput(currentState, c, seedStr);
            }

            // 处理鼠标点击暂停按钮（边沿检测）
            boolean mousePressed = StdDraw.isMousePressed();
            if (mousePressed && !mouseWasPressed) {
                double mx = StdDraw.mouseX();
                double my = StdDraw.mouseY();
                if (isInsidePauseButton(mx, my)) {
                    if (currentState == GameState.PLAYING) {
                        currentState = GameState.PAUSED;
                    } else if (currentState == GameState.PAUSED) {
                        currentState = GameState.PLAYING;
                    }
                }
            }
            mouseWasPressed = mousePressed;

            // Enemies AI tick
            if (currentState == GameState.PLAYING) {
                for (Entity e : entityMgr.getAllEntities()) {
                    if (e instanceof Enemy enemy && e.isAlive()) {
                        enemy.updateAI(world, entityMgr, player);
                    }
                }
                entityMgr.flushPendingChanges();
                entityMgr.removeDeadEntities();
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
                    // 添加玩家
                    player = spawnPlayer(this.seed);
                    addEntity(player);
                    // 添加敌人
                    List<Enemy> enemies = Enemy.spawnEnemies(world, this.seed, player.getPosition());
                    for (Enemy e : enemies) {
                        addEntity(e);
                    }

                    Logger.section("Game started (new game).");
                    return GameState.PLAYING;
                } else {
                    Logger.error("Invalid character in seed input.");
                }
                return state;

            case PLAYING:
                if (c == ':') {
                    return GameState.QUIT_PENDING;
                } else if (c == 'p') {
                    return GameState.PAUSED;
                } else {
                    handlePlayerInput(c);
                }
                return state;

            case PAUSED:
                if (c == 'p') {
                    return GameState.PLAYING;
                } else if (c == ':') {
                    return GameState.QUIT_PENDING;
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
            case PAUSED:
            case QUIT_PENDING:
                drawGameWithPauseButton(state == GameState.PAUSED);
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
     * 绘制游戏画面和暂停按钮。
     * @param isPaused true 时显示 Resume 按钮，false 时显示 Pause 按钮
     */
    private void drawGameWithPauseButton(boolean isPaused) {
        TETile[][] frame = buildActiveFrame();
        StdDraw.clear(new Color(0, 0, 0));
        for (int x = 0; x < frame.length; x++) {
            for (int y = 0; y < frame[0].length; y++) {
                frame[x][y].draw(x, y);
            }
        }
        drawUIBar(isPaused);
        StdDraw.show();
    }

    /** 绘制顶部 UI 栏（深灰背景 + 分隔线 + 暂停按钮）。 */
    private void drawUIBar(boolean isPaused) {
        double barY = HEIGHT + UI_HEIGHT / 2.0;
        // 背景
        StdDraw.setPenColor(new Color(30, 30, 30));
        StdDraw.filledRectangle(WIDTH / 2.0, barY, WIDTH / 2.0, UI_HEIGHT / 2.0);
        // 分隔线
        StdDraw.setPenColor(new Color(100, 100, 100));
        StdDraw.line(0, HEIGHT, WIDTH, HEIGHT);
        // 暂停按钮
        drawPauseButton(isPaused);
    }

    /** 在 UI 栏内绘制暂停/恢复按钮。 */
    private void drawPauseButton(boolean isPaused) {
        StdDraw.setPenColor(isPaused ? new Color(60, 120, 60) : new Color(80, 80, 80));
        StdDraw.filledRectangle(BTN_CENTER_X, BTN_CENTER_Y, BTN_HALF_W, BTN_HALF_H);
        StdDraw.setPenColor(StdDraw.WHITE);
        StdDraw.rectangle(BTN_CENTER_X, BTN_CENTER_Y, BTN_HALF_W, BTN_HALF_H);
        StdDraw.text(BTN_CENTER_X, BTN_CENTER_Y, isPaused ? "|> Resume (P)" : "|| Pause (P)");
    }

    /** 判断鼠标坐标是否在暂停按钮区域内。 */
    private boolean isInsidePauseButton(double mouseX, double mouseY) {
        return mouseX >= BTN_CENTER_X - BTN_HALF_W
            && mouseX <= BTN_CENTER_X + BTN_HALF_W
            && mouseY >= BTN_CENTER_Y - BTN_HALF_H
            && mouseY <= BTN_CENTER_Y + BTN_HALF_H;
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
        entityMgr = new EntityManager();
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
                return buildActiveFrame();
            }
        }

        // 渲染并返回最终帧
        TETile[][] frame = buildActiveFrame();

        // To draw, for tests, comment this when testing "Survivals" or publishing
        TERenderer ter = new TERenderer();
        ter.initialize(WIDTH, WINDOW_HEIGHT);
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
        player.move(direction, world, entityMgr);
    }

    /**
     * 组合世界地图和实体，构建当前活动帧的瓦片数组。
     * */
    public TETile[][] buildActiveFrame() {
        if (world == null || player == null) {
            return createEmptyWorld();
        }
        TETile[][] frame = TETile.copyOf(world);
        Position p = player.getPosition();
        frame[p.x][p.y] = player.getTile();
        for (Entity e : entityMgr.getAllEntities()) {
            if (e != player && e.isAlive()) {
                Position ep = e.getPosition();
                frame[ep.x][ep.y] = e.getTile();
            }
        }
        return frame;
    }

    /** 添加实体到空间索引。 */
    private void addEntity(Entity e) {
        entityMgr.addEntity(e);
    }

    /** 从空间索引移除实体。 */
    private void removeEntity(Entity e) {
        entityMgr.removeEntity(e);
    }

    /** 供外部在帧中任意时刻请求添加实体，帧末统一执行。 */
    public void requestAddEntity(Entity e) {
        entityMgr.requestAddEntity(e);
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
     * 注意：唯一的player对象在此方法中创建
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
     * 将所有实体状态序列化到 extraData.entityStates，确保 HP、存活状态等在读档后一致。
     */
    private void saveGameState() {
        Logger.section("Save Game");
        Logger.info("Saving game...");
        GameSaveData data = new GameSaveData();
        data.seed = this.seed;
        data.playerX = player.getPosition().x;
        data.playerY = player.getPosition().y;

        List<EntityState> states = new ArrayList<>();
        for (Entity e : entityMgr.getAllEntities()) {
            EntityState s = new EntityState();
            s.x = e.getPosition().x;
            s.y = e.getPosition().y;
            s.alive = e.isAlive();
            if (e instanceof Player pl) {
                s.type = "Player";
                s.hp = pl.getHp();
                s.sightRange = pl.getSightRange();
            } else if (e instanceof Enemy enemy) {
                s.type = "Enemy";
                s.hp = enemy.getHp();
                s.sightRange = enemy.getSightRange();
            }
            states.add(s);
        }
        data.extraData.put("entityStates", (java.io.Serializable) states);

        SaveLoadManager.save(data);
        Logger.info("Game saved successfully.");
    }

    /**
     * 从文件加载游戏状态并重建世界。
     * 优先从 extraData.entityStates 恢复实体（含 HP、存货状态），
     * 若旧存档无此字段则 fallback 到 seed 确定性重建。
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
        entityMgr = new EntityManager();

        @SuppressWarnings("unchecked")
        List<EntityState> states = (List<EntityState>) data.extraData.get("entityStates");
        if (states != null) {
            for (EntityState s : states) {
                Entity e;
                if ("Player".equals(s.type)) {
                    player = new Player(new Position(s.x, s.y), s.hp, s.sightRange);
                    e = player;
                } else if ("Enemy".equals(s.type)) {
                    Random random = new Random(data.seed.hashCode());
                    Enemy enemy = new Enemy(new Position(s.x, s.y), Tileset.ENEMY,
                            s.hp, s.sightRange, 5, random);
                    e = enemy;
                } else {
                    continue;
                }
                if (!s.alive) {
                    e.die();
                }
                addEntity(e);
            }
        }

        Logger.info("Game loaded successfully.");
        return true;
    }
}
