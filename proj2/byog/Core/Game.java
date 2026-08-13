package byog.Core;

import byog.Action.AttackAction;
import byog.Action.MoveAction;
import byog.AI.AiTickContext;
import byog.AI.AiTickLoop;
import byog.Bridge.AgentProtocol;
import byog.Bridge.AgentSession;
import byog.Bridge.AgentSessionConfig;
import byog.Bridge.MonotonicClock;
import byog.Common.Difficulty;
import byog.Common.Direction;
import byog.Common.Facing;
import byog.Common.VisionMode;
import byog.Entity.Entity;
import byog.Entity.EntityManager;
import byog.Entity.Enemy;
import byog.Entity.Player;
import byog.Entity.PlayerRunState;
import byog.Helper.Logger;
import byog.IO.GameConfig;
import byog.IO.GameSaveData;
import byog.IO.HealthPackPosition;
import byog.IO.LoadResult;
import byog.IO.SaveResult;
import byog.IO.WorldName;
import byog.IO.WorldSaveEntry;
import byog.IO.WorldSaveRepository;
import byog.IO.WorldSaveSummary;
import byog.IO.FileWorldSaveRepository;
import byog.IO.Clock;
import byog.IO.EnemySaveData;
import byog.TileEngine.TERenderer;
import byog.TileEngine.TETile;
import byog.TileEngine.Tileset;
import byog.WorldGen.Room;
import byog.WorldGen.RoomGraph;
import byog.WorldGen.RoomLocator;
import byog.WorldGen.SquareRoom;
import byog.WorldGen.StairPlacement;
import byog.WorldGen.WorldGenResult;
import byog.WorldGen.WorldGenerator;
import byog.WorldGen.HealthPackGenerator;
import byog.WorldGen.HealthPackPickup;
import byog.IO.HealthPackConfig;
import byog.lab5.Position;
import edu.princeton.cs.introcs.StdDraw;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class Game {
    TERenderer ter = new TERenderer();
    public static final int WIDTH = ScreenLayout.WORLD_WIDTH;
    public static final int HEIGHT = ScreenLayout.WORLD_HEIGHT;
    public static final int WINDOW_HEIGHT = ScreenLayout.WINDOW_HEIGHT;

    private TETile[][] world;
    private Player player;
    private EntityManager entityMgr;
    private Map<Character, Runnable> keyBindings;
    private final PlayerMoveController playerMoveController =
            new PlayerMoveController();
    private String seed;

    private long frameCounter = 0;
    private long attackFrame = -1;
    private int floorLevel = 1;
    /** 当前楼层剩余苹果坐标（按生成顺序，供拾取与存档使用）。 */
    private List<Position> healthPackPositions = new ArrayList<>();

    private Difficulty difficulty;
    private GameConfig gameConfig;
    /** 命名世界的稳定身份；覆盖同名世界时生成新值。 */
    private String worldId;
    /** 当前世界显示名。 */
    private String worldName;
    /** 视野模式：默认 directional，随世界存档保存。 */
    private VisionMode visionMode = VisionMode.DIRECTIONAL;
    /** 命名世界存档仓库；测试可注入临时根目录。 */
    private WorldSaveRepository worldRepository;
    /** 世界名输入缓冲。 */
    private final StringBuilder worldNameInput = new StringBuilder();
    /** 重名确认：待确认覆盖的已有世界名。 */
    private String pendingOverwriteName;
    /** 重名确认后：被替换的旧 worldId（覆盖保存时写入 marker）。 */
    private String pendingReplacesWorldId;
    /** 世界列表翻页偏移。 */
    private int loadPage = 0;
    private static final int LOAD_PAGE_SIZE = 8;
    /** Interactive agent-run identity. Not used by playWithInputString(). */
    private String runId;
    private long logicalTick = 0;
    private boolean agentRuntimeEnabled;
    private AgentSessionConfig agentSessionConfig;
    private final boolean agentSessionConfigInjected;

    public Game() {
        this(null);
    }

    public Game(AgentSessionConfig agentSessionConfig) {
        if (agentSessionConfig != null) {
            this.agentSessionConfig = agentSessionConfig;
            this.agentSessionConfigInjected = true;
        } else {
            this.agentSessionConfig = null;
            this.agentSessionConfigInjected = false;
        }
        this.worldRepository = new FileWorldSaveRepository(
                java.nio.file.Paths.get(FileWorldSaveRepository.DEFAULT_ROOT),
                Clock.system());
    }

    /** 测试注入：使用临时存档根目录。 */
    public void setWorldRepository(WorldSaveRepository worldRepository) {
        if (worldRepository == null) {
            throw new IllegalArgumentException(
                    "worldRepository must not be null");
        }
        this.worldRepository = worldRepository;
    }

    // 游戏状态枚举
    private enum GameState {
        MENU, WORLD_NAME_INPUT, WORLD_OVERWRITE_CONFIRM, WORLD_LOAD_SELECT,
        DIFFICULTY_SELECT, SEED_INPUT, PLAYING, PAUSED, QUIT_PENDING, QUIT
    }

    // 暂停按钮常量（位于顶部 UI 栏内）
    private static final double BTN_CENTER_X = 76.0;
    private static final double BTN_CENTER_Y =
            ScreenLayout.WORLD_HEIGHT + ScreenLayout.TOP_UI_HEIGHT / 2.0
                    + ScreenLayout.BOTTOM_UI_HEIGHT;
    private static final double BTN_HALF_W = 3.5;
    private static final double BTN_HALF_H = 0.75;

    // 鼠标边沿检测
    private boolean mouseWasPressed = false;

    /**
     * Method used for playing a fresh game. The game should start from the main menu.
     */
    public void playWithKeyboard() {
        // 初始化
        agentRuntimeEnabled = true;
        runId = null;
        logicalTick = 0;
        ter.initialize(WIDTH, WINDOW_HEIGHT);
        entityMgr = new EntityManager();
        player = null;
        initKeyBindings();
        playerMoveController.reset();

        // 状态机
        GameState currentState = GameState.MENU;
        StringBuilder seedStr = new StringBuilder();

        // 主循环
        try {
            while (currentState != GameState.QUIT) {
                frameCounter++;

                // 处理键盘输入（世界名输入保留原始大小写，其余状态转小写）
                while (StdDraw.hasNextKeyTyped()) {
                    char raw = StdDraw.nextKeyTyped();
                    char c = currentState == GameState.WORLD_NAME_INPUT
                            ? raw : Character.toLowerCase(raw);
                    currentState = processTypedKeyboardInput(
                            currentState, c, seedStr);
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

                if (currentState == GameState.PLAYING) {
                    handleHeldPlayerMovement();
                    runPlayingTick();

                    // 检测玩家死亡
                    if (player != null && !player.isAlive()) {
                        currentState = GameState.PAUSED;
                        Logger.info("Player died!");
                    }
                }

                // 绘制
                draw(currentState, seedStr.toString());

                StdDraw.show();
                StdDraw.pause(16);
            }
        } finally {
            closeAllEnemyRuntimes();
            agentRuntimeEnabled = false;
        }
    }

    /**
     * Interactive-game AI scheduler. Every enemy completes the same tick stage
     * before the shared world commit barrier is crossed.
     */
    private void runPlayingTick() {
        if (player != null) {
            player.updateCharge();
            player.updateHitTimer();
        }
        if (world == null || entityMgr == null || player == null) {
            return;
        }
        ensureRunIdentity();

        AiTickContext context =
                new AiTickContext(runId, floorLevel, logicalTick);
        AiTickLoop.run(context, world, entityMgr, player);
        logicalTick++;
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
                    worldNameInput.setLength(0);
                    return GameState.WORLD_NAME_INPUT;
                } else if (c == 'l') {
                    loadPage = 0;
                    return GameState.WORLD_LOAD_SELECT;
                } else if (c == 'q') {
                    return GameState.QUIT;
                }
                return state;

            case WORLD_NAME_INPUT:
                if (c == '\n' || c == '\r') {
                    String name;
                    try {
                        name = WorldName.validateForDisplay(
                                worldNameInput.toString());
                    } catch (IllegalArgumentException e) {
                        Logger.error("Invalid world name: %s", e.getMessage());
                        worldNameInput.setLength(0);
                        return state;
                    }
                    String existingWorldId = findWorldIdByName(name);
                    if (existingWorldId != null) {
                        pendingOverwriteName = existingWorldId;
                        pendingReplacesWorldId = existingWorldId;
                        Logger.info(
                                "World name '%s' already exists; confirm overwrite.",
                                name);
                        return GameState.WORLD_OVERWRITE_CONFIRM;
                    }
                    worldName = name;
                    return GameState.DIFFICULTY_SELECT;
                } else if (c == 8 || c == 127) {
                    // 退格：删除最后一个 code point
                    String current = worldNameInput.toString();
                    if (!current.isEmpty()) {
                        worldNameInput.deleteCharAt(current.length() - 1);
                    }
                    return state;
                } else if (!Character.isISOControl(c)) {
                    worldNameInput.append(c);
                }
                return state;

            case WORLD_OVERWRITE_CONFIRM:
                if (c == 'y') {
                    worldName = WorldName.validateForDisplay(
                            worldNameInput.toString());
                    pendingReplacesWorldId = pendingOverwriteName;
                    return GameState.DIFFICULTY_SELECT;
                } else if (c == 'n') {
                    pendingOverwriteName = null;
                    pendingReplacesWorldId = null;
                    worldNameInput.setLength(0);
                    return GameState.WORLD_NAME_INPUT;
                }
                return state;

            case WORLD_LOAD_SELECT:
                if (c == 'q') {
                    return GameState.MENU;
                } else if (c == 'n') {
                    loadPage++;
                    return state;
                } else if (c == 'p') {
                    loadPage = Math.max(0, loadPage - 1);
                    return state;
                } else if (c >= '1' && c <= '9') {
                    int index = loadPage * LOAD_PAGE_SIZE + (c - '1');
                    if (selectWorldToLoad(index)) {
                        return GameState.PLAYING;
                    }
                }
                return state;

            case DIFFICULTY_SELECT:
                if (c == '1' || c == 'e') {
                    difficulty = Difficulty.EASY;
                    gameConfig = new GameConfig(difficulty);
                    applyLoadedGameConfig();
                    return GameState.SEED_INPUT;
                } else if (c == '2' || c == 'b') {
                    difficulty = Difficulty.BALANCED;
                    gameConfig = new GameConfig(difficulty);
                    applyLoadedGameConfig();
                    return GameState.SEED_INPUT;
                } else if (c == '3' || c == 'h') {
                    difficulty = Difficulty.HARDCORE;
                    gameConfig = new GameConfig(difficulty);
                    applyLoadedGameConfig();
                    return GameState.SEED_INPUT;
                }
                // 旧格式兼容：非123字符默认选 BALANCED 并回退到字符处理
                difficulty = Difficulty.BALANCED;
                gameConfig = new GameConfig(difficulty);
                applyLoadedGameConfig();
                Logger.info("Unknown difficulty key '%c', using BALANCED.", c);
                if (Character.isDigit(c)) {
                    seedStr.append(c);
                }
                return GameState.SEED_INPUT;

            case SEED_INPUT:
                if (Character.isDigit(c)) {
                    seedStr.append(c);
                } else if (c == 's') {
                    WorldGenResult result = generateWorld(seedStr.toString(), floorLevel);
                    player = spawnPlayer(this.seed, floorLevel);
                    addEntity(player);
                    List<Enemy> enemies = Enemy.spawnEnemies(world, this.seed, player.getPosition(), floorLevel - 1, floorLevel, gameConfig);
                    for (Enemy e : enemies) {
                        addEntity(e);
                    }
                    StairPlacement stairs = placeStairs(result, player.getPosition(), floorLevel);
                    placeHealthPacks(result, stairs);
                    if (agentRuntimeEnabled) {
                        beginAgentRun();
                    }
                    // 首次保存：生成新 worldId（覆盖同名世界时也必须生成新值）。
                    worldId = "world-" + UUID.randomUUID();
                    saveGameState();

                    Logger.section("Game started (new game) - " + difficulty.getKey() + ".");
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

    /** Keeps typed WASD events out of interactive movement without changing the legacy string path. */
    private GameState processTypedKeyboardInput(
            GameState state, char c, StringBuilder seedStr) {
        if (isMovementKey(c)) {
            if (state == GameState.PLAYING) {
                return state;
            }
            if (state == GameState.QUIT_PENDING) {
                return GameState.PLAYING;
            }
        }
        return processInput(state, c, seedStr);
    }

    private static boolean isMovementKey(char c) {
        return c == 'w' || c == 'a' || c == 's' || c == 'd';
    }

    /**
     * 根据状态绘制画面
     */
    private void draw(GameState state, String seed) {
        switch (state) {
            case MENU:
                drawMenu();
                break;
            case WORLD_NAME_INPUT:
                drawWorldNameInput();
                break;
            case WORLD_OVERWRITE_CONFIRM:
                drawOverwriteConfirm();
                break;
            case WORLD_LOAD_SELECT:
                drawWorldLoadSelect();
                break;
            case DIFFICULTY_SELECT:
                drawDifficultySelect();
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

    /** 绘制世界名输入界面。 */
    private void drawWorldNameInput() {
        StdDraw.clear(StdDraw.BLACK);
        StdDraw.setPenColor(StdDraw.WHITE);
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 + 2, "Enter world name:");
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0,
                worldNameInput.toString());
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 - 2, "Press Enter to confirm");
    }

    /** 绘制重名覆盖确认界面。 */
    private void drawOverwriteConfirm() {
        StdDraw.clear(StdDraw.BLACK);
        StdDraw.setPenColor(new Color(200, 120, 60));
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 + 2,
                "World name already exists: " + worldNameInput);
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0,
                "Overwrite it? (Y)es / (N)o");
    }

    /** 绘制世界列表选择界面（分页）。 */
    private void drawWorldLoadSelect() {
        StdDraw.clear(StdDraw.BLACK);
        StdDraw.setPenColor(StdDraw.WHITE);
        StdDraw.text(WIDTH / 2.0, HEIGHT + 2, "Select a world to load");
        List<WorldSaveEntry> entries = worldRepository.list();
        int start = loadPage * LOAD_PAGE_SIZE;
        for (int i = 0; i < LOAD_PAGE_SIZE; i++) {
            int index = start + i;
            if (index >= entries.size()) {
                break;
            }
            WorldSaveEntry entry = entries.get(index);
            double y = HEIGHT - 3 - i;
            if (entry.isReadable()) {
                WorldSaveSummary summary = entry.summary();
                StdDraw.setPenColor(StdDraw.WHITE);
                StdDraw.text(WIDTH / 2.0, y, String.format(
                        "%d - %s | floor %d | HP %d | %s",
                        i + 1, summary.worldName(), summary.floorLevel(),
                        summary.playerHp(), summary.difficulty()));
            } else {
                StdDraw.setPenColor(new Color(180, 60, 60));
                StdDraw.text(WIDTH / 2.0, y, String.format(
                        "%d - (unreadable) %s", i + 1,
                        entry.failurePath()));
            }
        }
        StdDraw.setPenColor(new Color(160, 160, 160));
        StdDraw.text(WIDTH / 2.0, 2,
                "(N)ext  (P)rev  (Q)uit");
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

    /** 绘制难度选择界面 */
    private void drawDifficultySelect() {
        StdDraw.clear(StdDraw.BLACK);
        StdDraw.setPenColor(StdDraw.WHITE);
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 + 3, "Select Difficulty");
        StdDraw.setPenColor(new Color(100, 200, 100));
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 + 1, "1 - Easy (E)");
        StdDraw.setPenColor(new Color(200, 200, 100));
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0, "2 - Balanced (B)");
        StdDraw.setPenColor(new Color(200, 100, 100));
        StdDraw.text(WIDTH / 2.0, HEIGHT / 2.0 - 1, "3 - Hardcore (H)");
    }

    /**
     * 绘制游戏画面和暂停按钮。
     * @param isPaused true 时显示 Resume 按钮，false 时显示 Pause 按钮
     */
    private void drawGameWithPauseButton(boolean isPaused) {
        TETile[][] frame = buildActiveFrame();
        StdDraw.clear(new Color(0, 0, 0));
        // 世界 tile 统一上移到底部上下文条之上
        for (int x = 0; x < frame.length; x++) {
            for (int y = 0; y < frame[0].length; y++) {
                frame[x][y].draw(x, ScreenLayout.worldToScreenY(y));
            }
        }
        drawFacingMarkers();
        drawBottomHoverBar();
        drawUIBar(isPaused);
    }

    /** 在所有 tile 绘制后，为每个活敌人画金色朝向标记（不占用相邻 tile）。 */
    private void drawFacingMarkers() {
        if (entityMgr == null) {
            return;
        }
        StdDraw.setPenColor(new Color(255, 200, 60));
        for (Entity e : entityMgr.getAllEntities()) {
            if (!(e instanceof Enemy enemy) || !e.isAlive()
                    || enemy.getFacing() == null) {
                continue;
            }
            Position p = e.getPosition();
            double sx = p.x + 0.5;
            double sy = ScreenLayout.worldToScreenY(p.y) + 0.5;
            switch (enemy.getFacing()) {
                case NORTH:
                    StdDraw.line(sx - 0.15, sy + 0.30, sx + 0.15, sy + 0.30);
                    break;
                case SOUTH:
                    StdDraw.line(sx - 0.15, sy - 0.30, sx + 0.15, sy - 0.30);
                    break;
                case EAST:
                    StdDraw.line(sx + 0.30, sy - 0.15, sx + 0.30, sy + 0.15);
                    break;
                case WEST:
                    StdDraw.line(sx - 0.30, sy - 0.15, sx - 0.30, sy + 0.15);
                    break;
            }
        }
    }

    /** 底部两行上下文条：按需显示 hover 信息，并高亮聚焦敌人的 committed FOV。 */
    private void drawBottomHoverBar() {
        StdDraw.setPenColor(new Color(20, 20, 20));
        StdDraw.filledRectangle(ScreenLayout.WORLD_WIDTH / 2.0, 1.0,
                ScreenLayout.WORLD_WIDTH / 2.0, 1.0);
        Position worldPos = mouseToWorld();
        HoverModel.HoverInfo info = HoverModel.resolve(
                world, entityMgr, player, gameConfig, worldPos);
        if (info.isEmpty()) {
            return;
        }
        StdDraw.setPenColor(StdDraw.WHITE);
        if (info.line1() != null) {
            StdDraw.text(ScreenLayout.WORLD_WIDTH / 2.0, 1.5, info.line1());
        }
        if (info.line2() != null) {
            StdDraw.text(ScreenLayout.WORLD_WIDTH / 2.0, 0.5, info.line2());
        }
        if (info.focusedEnemy() != null) {
            highlightFov(info.focusedEnemy().getVisibleMask());
        }
    }

    /** 鼠标屏幕坐标 → 世界逻辑坐标（与 tile 绘制互为逆映射）。 */
    private Position mouseToWorld() {
        int wx = (int) Math.floor(StdDraw.mouseX());
        int wy = ScreenLayout.screenToWorldY(
                (int) Math.floor(StdDraw.mouseY()));
        return new Position(wx, wy);
    }

    /** 只读 committed mask 高亮，不重新计算 FOV。 */
    private void highlightFov(boolean[][] mask) {
        if (mask == null) {
            return;
        }
        for (int x = 0; x < world.length && x < mask.length; x++) {
            for (int y = 0; y < world[0].length && y < mask[x].length; y++) {
                if (mask[x][y]) {
                    StdDraw.setPenColor(new Color(255, 220, 120));
                    StdDraw.filledSquare(
                            x + 0.5, ScreenLayout.worldToScreenY(y) + 0.5,
                            0.48);
                }
            }
        }
    }

    /** 绘制顶部 UI 栏（深灰背景 + 分隔线 + 暂停按钮 + HP 显示 + 蓄力条）。 */
    private void drawUIBar(boolean isPaused) {
        double barY = ScreenLayout.worldToScreenY(ScreenLayout.WORLD_HEIGHT)
                + ScreenLayout.TOP_UI_HEIGHT / 2.0;
        // 背景
        StdDraw.setPenColor(new Color(30, 30, 30));
        StdDraw.filledRectangle(WIDTH / 2.0, barY, WIDTH / 2.0,
                ScreenLayout.TOP_UI_HEIGHT / 2.0);
        // 分隔线
        StdDraw.setPenColor(new Color(100, 100, 100));
        StdDraw.line(0, ScreenLayout.worldToScreenY(ScreenLayout.WORLD_HEIGHT),
                WIDTH, ScreenLayout.worldToScreenY(ScreenLayout.WORLD_HEIGHT));

        // HP 显示
        if (player != null) {
            StdDraw.setPenColor(StdDraw.WHITE);
            int maxHp = (gameConfig != null) ? gameConfig.playerHp : 100;
            StdDraw.text(5, barY, String.format("HP: %d/%d", player.getHp(), maxHp));

            // 难度标签
            if (difficulty != null) {
                StdDraw.setPenColor(getDifficultyColor());
                StdDraw.setFont(new Font("Monaco", Font.BOLD, 14));
                StdDraw.text(16, barY, difficulty.getKey().toUpperCase());
                StdDraw.setFont(new Font("Monaco", Font.PLAIN, 14));
            }

            int enemyCount = 0;
            for (Entity e : entityMgr.getAllEntities()) {
                if (e instanceof Enemy && e.isAlive()) {
                    enemyCount++;
                }
            }
            StdDraw.text(22, barY, String.format("Enemies: %d", enemyCount));

            // 楼层显示（居中）
            StdDraw.setPenColor(new Color(200, 200, 100));
            StdDraw.setFont(new Font("Monaco", Font.BOLD, 20));
            StdDraw.text(38, barY, String.format("FLOOR: %d", floorLevel));
            StdDraw.setFont(new Font("Monaco", Font.BOLD, 14));

            // 蓄力条显示
            double chargePercent = (double) player.getCharge() / player.getMaxCharge();
            StdDraw.setPenColor(new Color(50, 50, 50));
            StdDraw.filledRectangle(58, barY, 10, 0.3);
            if (player.canAttack()) {
                StdDraw.setPenColor(StdDraw.GREEN);
            } else {
                StdDraw.setPenColor(StdDraw.BLUE);
            }
            StdDraw.filledRectangle(58 - 10 + chargePercent * 10, barY, chargePercent * 10, 0.3);
            StdDraw.setPenColor(StdDraw.WHITE);
            StdDraw.rectangle(58, barY, 10, 0.3);
            StdDraw.text(58, barY + 0.6, "CHARGE");
        }

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

    private static Color getDifficultyColor() {
        return new Color(200, 200, 100);
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
        // Legacy API: intentionally does not start the interactive agent runtime.
        agentRuntimeEnabled = false;
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

    private void handleHeldPlayerMovement() {
        Direction direction = playerMoveController.nextMove(
                StdDraw.isKeyPressed(KeyEvent.VK_W),
                StdDraw.isKeyPressed(KeyEvent.VK_S),
                StdDraw.isKeyPressed(KeyEvent.VK_A),
                StdDraw.isKeyPressed(KeyEvent.VK_D),
                System.nanoTime());
        if (direction != null && player != null && player.isAlive()) {
            movePlayer(player, direction);
        }
    }

    private void initKeyBindings() {
        keyBindings = new HashMap<>();

        // 1. 基础移动 (利用 Lambda 表达式)
        keyBindings.put('w', () -> movePlayer(player, Direction.UP));
        keyBindings.put('s', () -> movePlayer(player, Direction.DOWN));
        keyBindings.put('a', () -> movePlayer(player, Direction.LEFT));
        keyBindings.put('d', () -> movePlayer(player, Direction.RIGHT));

        // 2. 攻击动作（空格键蓄力攻击）
        keyBindings.put(' ', () -> attackPlayer());
    }

    private void movePlayer(Player player, Direction direction) {
        Position oldPos = player.getPosition();
        player.move(direction, world, entityMgr);
        if (!player.getPosition().equals(oldPos)) {
            Position newPos = player.getPosition();
            pickUpHealthPack(newPos);
            if (world[newPos.x][newPos.y] == Tileset.STAIRS) {
                nextFloor();
            }
        }
    }

    /**
     * 玩家进入苹果格且缺血时治疗并消耗；满血时不消耗。敌人移动不触发此流程。
     * 治疗结果通过 Logger 记录恢复量与治疗后生命值。
     */
    private void pickUpHealthPack(Position pos) {
        if (HealthPackPickup.tryPickup(world, player, pos,
                gameConfig.healthPackHealAmount, healthPackPositions)) {
            Logger.info("Picked up health pack at %s: +%d HP -> %d/%d",
                    pos, gameConfig.healthPackHealAmount,
                    player.getHp(), player.getMaxHp());
        } else if (world[pos.x][pos.y] == Tileset.APPLE) {
            Logger.debug("Full HP: health pack at %s not consumed", pos);
        }
    }

    private void attackPlayer() {
        if (player == null || !player.isAlive()) {
            return;
        }
        if (!player.canAttack()) {
            Logger.info("Charge not ready! (%d/%d)", player.getCharge(), player.getMaxCharge());
            return;
        }
        AttackAction action = new AttackAction(entityMgr, new Random(seed.hashCode()));
        action.execute(world, player);
        attackFrame = frameCounter;
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
        frame[p.x][p.y] = player.getDisplayTile();
        for (Entity e : entityMgr.getAllEntities()) {
            if (e != player && e.isAlive()) {
                Position ep = e.getPosition();
                frame[ep.x][ep.y] = e.getTile();
            }
        }

        // --- FOV 可视化（debug）---
        if (gameConfig != null && gameConfig.debugShowEnemyFov) {
            for (Entity e : entityMgr.getAllEntities()) {
                if (e instanceof Enemy enemy && e.isAlive()) {
                    boolean[][] mask = enemy.getVisibleMask();
                    if (mask == null) continue;
                    for (int x = 0; x < frame.length && x < mask.length; x++) {
                        for (int y = 0; y < frame[0].length && y < mask[x].length; y++) {
                            if (mask[x][y] && frame[x][y] == Tileset.FLOOR) {
                                frame[x][y] = Tileset.FLOOR_FOV;
                            }
                        }
                    }
                }
            }
        }
        // --- end FOV ---

        // 攻击动画：周围8格替换为橙色闪光瓦片，持续8帧
        if (attackFrame >= 0 && frameCounter - attackFrame < 8) {
            int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
            int[] dy = {-1, -1, -1, 0, 0, 1, 1, 1};
            for (int i = 0; i < 8; i++) {
                int nx = p.x + dx[i];
                int ny = p.y + dy[i];
                if (nx >= 0 && nx < frame.length && ny >= 0 && ny < frame[0].length) {
                    frame[nx][ny] = Tileset.ATTACK_FLASH;
                }
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
     * 用 seed 生成指定楼层的地牢世界。
     * @return 生成结果（含世界地图和房间列表）
     */
    private WorldGenResult generateWorld(String seed, int floor) {
        this.seed = seed;
        String floorSeed = seed + "_F" + floor;
        WorldGenResult result = WorldGenerator.RandomSquareRoomWrd(createEmptyWorld(), floorSeed);
        world = result.getWorld();
        return result;
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
     * @param floor 当前楼层
     * @return 创建的 Player 对象
     */
    private Player spawnPlayer(String seed, int floor) {
        Player p = new Player(new Position(0, 0), gameConfig);
        Player.initPlayer(p, world, seed + "_F" + floor);
        return p;
    }

    /** 在当前楼层最远房间放置传送方块，并返回类型化放置结果。 */
    private StairPlacement placeStairs(WorldGenResult result, Position playerPos, int floor) {
        List<SquareRoom> rooms = result.getRooms();
        if (rooms.isEmpty()) {
            return null;
        }
        Random random = new Random((seed + "_F" + floor + "_stairs").hashCode());
        RoomGraph roomGraph = new RoomGraph(new ArrayList<>(rooms));
        Room farthest = roomGraph.findFarthestRoom(playerPos, rooms);
        if (farthest instanceof SquareRoom sq) {
            List<Position> floors = sq.getFloorPositions();
            if (!floors.isEmpty()) {
                Position stairsPos = floors.get(random.nextInt(floors.size()));
                world[stairsPos.x][stairsPos.y] = Tileset.STAIRS;
                Logger.info("Stairs placed at %s (farthest room)", stairsPos);
                return new StairPlacement(stairsPos, sq);
            }
        }
        return null;
    }

    /** 在当前楼层放置苹果，并记录剩余位置供拾取与存档使用。 */
    private void placeHealthPacks(WorldGenResult result, StairPlacement stairs) {
        SquareRoom spawnRoom = RoomLocator.roomContaining(
                result.getRooms(), player.getPosition());
        List<Position> placed = HealthPackGenerator.place(
                world, result.getRooms(), spawnRoom,
                stairs == null ? null : stairs.room(), entityMgr,
                new HealthPackConfig(gameConfig.healthPackMinCount,
                        gameConfig.healthPackMaxCount,
                        gameConfig.healthPackHealAmount),
                (seed + "_F" + floorLevel + "_healthpack").hashCode());
        healthPackPositions.clear();
        healthPackPositions.addAll(placed);
    }

    /** 进入下一层：楼层+1、重新生成世界、重生玩家和敌人、放置传送门。 */
    private void nextFloor() {
        if (agentRuntimeEnabled) {
            closeAllEnemyRuntimes();
        }
        // 换层边界：只保留整局 HP；蓄力、动画计时和悬停等状态不跨层。
        PlayerRunState runState = PlayerRunState.capture(player);
        floorLevel++;
        Logger.section("Entering Floor " + floorLevel);

        WorldGenResult result = generateWorld(this.seed, floorLevel);
        entityMgr = new EntityManager();
        player = FloorTransitionService.createForNextFloor(
                runState, gameConfig, new Position(0, 0));
        Player.initPlayer(player, world, this.seed + "_F" + floorLevel);
        addEntity(player);

        List<Enemy> enemies = Enemy.spawnEnemies(world, this.seed, player.getPosition(), floorLevel - 1, floorLevel, gameConfig);
        for (Enemy e : enemies) {
            addEntity(e);
        }

        StairPlacement stairs = placeStairs(result, player.getPosition(), floorLevel);
        placeHealthPacks(result, stairs);
        if (agentRuntimeEnabled) {
            attachEnemySessions();
            primeEnemyObservations();
        }
        frameCounter = 0;
        attackFrame = -1;
    }

    /**
     * 收集当前游戏状态为类型化快照并保存到当前命名世界。
     * 运行期状态（Session、queue、Lease、hover、动画计时）永不进入存档。
     */
    private void saveGameState() {
        if (worldId == null || worldId.isEmpty()) {
            Logger.error("Cannot save: no active world.");
            return;
        }
        Logger.section("Save Game");
        Logger.info("Saving game (world=%s)...", worldName);
        GameSaveData data = captureSaveData();
        SaveResult result = worldRepository.save(data);
        if (!result.success()) {
            Logger.error("Save failed: %s", result.failureReason());
            return;
        }
        // 只有保存成功才清空覆盖 marker；失败时保留，避免旧档丢失 marker。
        pendingReplacesWorldId = null;
        if (result.warning() != null) {
            Logger.error("Save warning: %s", result.warning());
        } else {
            Logger.info("Game saved successfully (world=%s).", worldId);
        }
    }

    /** 从权威世界状态构建类型化存档快照。 */
    private GameSaveData captureSaveData() {
        GameSaveData data = new GameSaveData();
        data.setWorldId(worldId);
        data.setWorldName(worldName);
        data.setReplacesWorldId(pendingReplacesWorldId);
        data.setSeed(seed);
        data.setFloorLevel(floorLevel);
        data.setDifficulty(difficulty.getKey());
        data.setVisionMode(visionMode);
        data.setRunCurrentHp(PlayerRunState.capture(player).getCurrentHp());
        data.setFloorPlayerPosition(player.getPosition());
        data.setFloorPlayerCharge(player.getCharge());
        Position stairs = findStairsPosition();
        if (stairs != null) {
            data.setStairsPosition(stairs);
        } else {
            data.setStairsPosition(new Position(-1, -1));
        }
        for (Position hp : healthPackPositions) {
            data.addHealthPack(hp);
        }
        for (Entity e : entityMgr.getAllEntities()) {
            if (e instanceof Enemy enemy) {
                EnemySaveData es = new EnemySaveData();
                es.setAgentId(enemy.getAgentId());
                es.setPosition(enemy.getPosition());
                es.setAlive(enemy.isAlive());
                es.setHp(enemy.getHp());
                es.setMaxHp(enemy.getMaxHp());
                es.setSightRange(enemy.getSightRange());
                es.setAttackDamage(enemy.getAttackDamage());
                es.setDamageVariance(enemy.getDamageVariance());
                es.setMoveInterval(enemy.getMoveInterval());
                es.setFacing(enemy.getFacing() == null
                        ? null : enemy.getFacing().name());
                es.setPatrolState(enemy.getPatrolState());
                data.getEnemyStates().add(es);
            }
        }
        return data;
    }

    /** 扫描当前世界中的楼梯坐标；未放置时返回 null。 */
    private Position findStairsPosition() {
        for (int x = 0; x < world.length; x++) {
            for (int y = 0; y < world[0].length; y++) {
                if (world[x][y] == Tileset.STAIRS) {
                    return new Position(x, y);
                }
            }
        }
        return null;
    }

    /**
     * 坏档隔离：存档中所有坐标必须落在世界内（楼梯 (-1,-1) 哨兵除外）。
     * 任一越界即拒绝整个加载，不做半恢复世界。
     */
    private boolean allPositionsWithinWorld(GameSaveData data) {
        if (!withinWorld(data.getFloorPlayerPosition())) {
            return false;
        }
        Position stairs = data.getStairsPosition();
        if (stairs.x >= 0 && stairs.y >= 0
                && !withinWorld(stairs)) {
            return false;
        }
        for (HealthPackPosition hp : data.getHealthPacks()) {
            if (!withinWorld(hp.toPosition())) {
                return false;
            }
        }
        for (EnemySaveData enemy : data.getEnemyStates()) {
            if (!withinWorld(enemy.getPosition())) {
                return false;
            }
        }
        return true;
    }

    private boolean withinWorld(Position p) {
        return p.x >= 0 && p.x < WIDTH && p.y >= 0 && p.y < HEIGHT;
    }

    /**
     * 读取并恢复指定命名世界；失败返回 false 并留在列表界面。
     * 恢复顺序：验证 DTO → 生成基础世界 → 放楼梯 → 恢复苹果 → 恢复敌人
     * → 创建新 runId/Session。
     */
    private boolean loadGameState(String loadWorldId) {
        LoadResult loaded = worldRepository.load(loadWorldId);
        if (!loaded.success()) {
            Logger.error("Load failed: %s", loaded.failureReason());
            return false;
        }
        GameSaveData data = loaded.data();
        // 坏档隔离：坐标必须落在世界内，否则拒绝整个加载。
        if (!allPositionsWithinWorld(data)) {
            Logger.error("Load rejected: world %s has out-of-bounds positions",
                    loadWorldId);
            return false;
        }
        worldId = data.getWorldId();
        worldName = data.getWorldName();
        pendingReplacesWorldId = data.getReplacesWorldId();
        seed = data.getSeed();
        floorLevel = data.getFloorLevel();
        difficulty = Difficulty.fromKey(data.getDifficulty());
        gameConfig = new GameConfig(difficulty);
        applyLoadedGameConfig();
        visionMode = VisionMode.valueOf(data.getVisionMode());

        if (agentRuntimeEnabled) {
            closeAllEnemyRuntimes();
        }
        WorldGenResult result = generateWorld(seed, floorLevel);
        entityMgr = new EntityManager();

        // 玩家：整局 HP + 当前楼层 charge 恢复。
        player = new Player(data.getFloorPlayerPosition(), gameConfig);
        PlayerRunState runState = new PlayerRunState();
        runState.setCurrentHp(data.getRunCurrentHp());
        runState.restoreInto(player, gameConfig.playerHp);
        player.restoreCharge(data.getFloorPlayerCharge());
        addEntity(player);

        // 楼梯与苹果：读档恢复剩余苹果，不重新掷位置。
        Position stairs = data.getStairsPosition();
        if (stairs.x >= 0 && stairs.y >= 0) {
            world[stairs.x][stairs.y] = Tileset.STAIRS;
        }
        healthPackPositions.clear();
        for (HealthPackPosition hp : data.getHealthPacks()) {
            Position p = hp.toPosition();
            world[p.x][p.y] = Tileset.APPLE;
            healthPackPositions.add(p);
        }

        // 敌人：恢复身体状态（facing/patrol 由后续增量恢复）。
        for (EnemySaveData es : data.getEnemyStates()) {
            Random random = new Random(data.getSeed().hashCode());
            Enemy enemy = new Enemy(es.getPosition(), Tileset.ENEMY,
                    es.getHp(), es.getSightRange(), es.getMoveInterval(),
                    es.getAttackDamage(), es.getDamageVariance(),
                    random, es.getAgentId(),
                    gameConfig.agentActionQueueLowWater,
                    gameConfig.agentActionQueueHighWater);
            enemy.setPerceptionEnabled(true);
            enemy.setMaxHp(es.getMaxHp());
            enemy.setAttackInterval(gameConfig.enemyAttackInterval);
            if (es.getFacing() != null) {
                enemy.setFacing(Facing.valueOf(es.getFacing()));
            }
            enemy.setPatrolState(es.getPatrolState());
            if (!es.isAlive()) {
                enemy.die();
            }
            addEntity(enemy);
        }

        if (agentRuntimeEnabled) {
            beginAgentRun();
        }
        Logger.section("Game started (loaded world " + worldName
                + ", floor " + floorLevel + ").");
        return true;
    }

    /** 按显示名查找已有世界 ID；不存在返回 null。 */
    private String findWorldIdByName(String name) {
        String key = WorldName.comparisonKey(name);
        for (WorldSaveEntry entry : worldRepository.list()) {
            if (entry.isReadable()
                    && WorldName.comparisonKey(entry.summary().worldName())
                    .equals(key)) {
                return entry.summary().worldId();
            }
        }
        return null;
    }

    /** 从世界列表按页内索引选择并加载；失败返回 false。 */
    private boolean selectWorldToLoad(int index) {
        List<WorldSaveEntry> entries = worldRepository.list();
        if (index < 0 || index >= entries.size()) {
            Logger.info("No world at index %d.", index);
            return false;
        }
        WorldSaveEntry entry = entries.get(index);
        if (!entry.isReadable()) {
            Logger.error("Selected world is unreadable.");
            return false;
        }
        return loadGameState(entry.summary().worldId());
    }

    /**
     * 创建新的运行身份、重置逻辑时钟并初始化敌人观察。
     */
    private void beginAgentRun() {
        runId = "run-" + UUID.randomUUID();
        logicalTick = 0;
        attachEnemySessions();
        primeEnemyObservations();
    }

    /**
     * Creates one independent enabled session for every living enemy.
     */
    private void attachEnemySessions() {
        if (agentSessionConfig == null || !agentSessionConfig.isEnabled()) {
            return;
        }
        ensureRunIdentity();
        for (Enemy enemy : snapshotEnemies()) {
            if (!enemy.isAlive()) {
                continue;
            }
            AgentSessionConfig enemyConfig = agentSessionConfig.toBuilder()
                    .capabilities(
                            agentSessionConfig.getCapabilities()
                                    .supportedSkills(),
                            enemy.getSightRange(),
                            enemy.getAttackDamage(),
                            enemy.getMoveInterval())
                    .build();
            AgentProtocol.Identity identity = new AgentProtocol.Identity(
                    worldId, runId, floorLevel, enemy.getAgentId(), 0, 0);
            AgentSession session = new AgentSession(
                    enemyConfig, identity, MonotonicClock.systemClock());
            try {
                enemy.attachAgentSession(session);
            } catch (RuntimeException exception) {
                session.close();
                Logger.error(
                        "Failed to attach agent session for %s: %s",
                        enemy.getAgentId(), exception.getMessage());
            }
        }
    }

    /**
     * Cold-start observations are created only after the initial world has
     * been fully assembled. This preserves moveInterval==1 cadence without
     * allowing executeOneAction to read a live Player reference.
     */
    private void primeEnemyObservations() {
        if (world == null || entityMgr == null || player == null) {
            return;
        }
        ensureRunIdentity();
        entityMgr.flushPendingChanges();
        entityMgr.removeDeadEntities();
        AiTickContext context =
                new AiTickContext(runId, floorLevel, logicalTick);
        for (Enemy enemy : snapshotEnemies()) {
            if (enemy.isAlive()) {
                enemy.setWorldId(worldId);
                enemy.setVisionMode(visionMode);
                enemy.setPatrolSeedKey((seed + "_patrol").hashCode());
                enemy.collectAgentUpdates(
                        context, world, entityMgr, player);
            }
        }
    }

    private List<Enemy> snapshotEnemies() {
        if (entityMgr == null) {
            return new ArrayList<>();
        }
        return AiTickLoop.snapshot(entityMgr);
    }

    private void closeAllEnemyRuntimes() {
        for (Enemy enemy : snapshotEnemies()) {
            enemy.closeAgentRuntime();
        }
    }

    private void ensureRunIdentity() {
        if (runId == null || runId.isEmpty()) {
            runId = "run-" + UUID.randomUUID();
        }
    }

    /** Applies file-backed Agent settings unless a caller injected an override. */
    private void applyLoadedGameConfig() {
        if (!agentSessionConfigInjected) {
            agentSessionConfig = gameConfig.toAgentSessionConfig();
        }
    }

    public String getRunId() {
        return runId;
    }

    public long getLogicalTick() {
        return logicalTick;
    }
}
