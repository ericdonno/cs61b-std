package byog.Test;

import byog.AI.PatrolState;
import byog.Common.VisionMode;
import byog.IO.Clock;
import byog.IO.EnemySaveData;
import byog.IO.FileWorldSaveRepository;
import byog.IO.GameSaveData;
import byog.IO.HealthPackPosition;
import byog.IO.LoadResult;
import byog.IO.SaveResult;
import byog.IO.WorldName;
import byog.IO.WorldSaveEntry;
import byog.IO.WorldSaveRepository;
import byog.IO.WorldSaveSummary;
import byog.lab5.Position;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 命名世界存档：创建、列表、覆盖、原子写、坏档隔离与路径安全。
 *
 * <p>覆盖 Spec 存档验收：不限数量、摘要与稳定排序、大小写无关判重、
 * 新 worldId 覆盖、失败不破坏旧档、单坏档不阻断列表、状态完整恢复、
 * 路径 confinement、注入时钟决定保存时间、被替换世界隐藏。</p>
 */
public class WorldSaveRepositoryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final long FIXED_EPOCH = 1750000000000L;

    private Clock fixedClock() {
        return () -> FIXED_EPOCH;
    }

    private WorldSaveRepository repo(Path root) {
        return new FileWorldSaveRepository(root, fixedClock());
    }

    private GameSaveData sampleData(String worldId, String name,
                                    int hp, int floor,
                                    String difficulty) {
        GameSaveData d = new GameSaveData();
        d.setWorldId(worldId);
        d.setWorldName(name);
        d.setSeed("seed-1");
        d.setFloorLevel(floor);
        d.setDifficulty(difficulty);
        d.setSavedAtEpochMillis(FIXED_EPOCH);
        d.setVisionMode(VisionMode.DIRECTIONAL);
        d.setRunCurrentHp(hp);
        d.setFloorPlayerPosition(new Position(3, 3));
        d.setFloorPlayerCharge(25);
        d.setStairsPosition(new Position(12, 8));
        d.addHealthPack(new Position(5, 5));
        return d;
    }

    private GameSaveData sampleDataWithEnemy(String worldId, String name) {
        GameSaveData d = sampleData(worldId, name, 60, 2, "balanced");
        EnemySaveData enemy = new EnemySaveData();
        enemy.setAgentId("guard-a");
        enemy.setPosition(new Position(9, 9));
        enemy.setAlive(true);
        enemy.setHp(14);
        enemy.setMaxHp(20);
        enemy.setSightRange(7);
        enemy.setAttackDamage(10);
        enemy.setDamageVariance(3);
        enemy.setMoveInterval(5);
        enemy.setFacing("EAST");
        PatrolState patrol = new PatrolState();
        patrol.setMode(PatrolState.Mode.TRAVELING);
        patrol.setTarget(new Position(4, 4));
        patrol.setBlockedAttempts(1);
        patrol.setSelectionOrdinal(2);
        enemy.setPatrolState(patrol);
        d.getEnemyStates().add(enemy);
        return d;
    }

    // ---------- P25-SAVE-01 多世界、摘要与稳定排序 ----------

    @Test
    public void multipleWorldsListWithStableSummarySort() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        assertTrue(repo.save(sampleData("world-b", "Bravo", 90, 1, "easy"))
                .success());
        assertTrue(repo.save(sampleData("world-a", "alpha", 50, 3, "hardcore"))
                .success());
        assertTrue(repo.save(sampleData("world-c", "Charlie", 70, 2, "balanced"))
                .success());

        List<WorldSaveEntry> entries = repo.list();
        assertEquals(3, entries.size());
        // 排序：comparisonKey(name) 再 worldId → alpha, Bravo, Charlie
        assertEquals("alpha", entries.get(0).summary().worldName());
        assertEquals("Bravo", entries.get(1).summary().worldName());
        assertEquals("Charlie", entries.get(2).summary().worldName());
        WorldSaveSummary s = entries.get(0).summary();
        assertEquals("world-a", s.worldId());
        assertEquals(3, s.floorLevel());
        assertEquals(50, s.playerHp());
        assertEquals("hardcore", s.difficulty());
        assertEquals(FIXED_EPOCH, s.savedAtEpochMillis());
        // 稳定排序：再次调用结果一致
        assertEquals(entries, repo.list());
    }

    // ---------- P25-SAVE-02 大小写无关判重与覆盖新 ID ----------

    @Test
    public void overwriteGeneratesNewWorldIdAndDeletesOldFile() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        repo.save(sampleData("world-old", "Tower", 80, 1, "balanced"));
        assertTrue(repo.nameExists(
                WorldName.comparisonKey("tower"))); // 大小写无关
        assertFalse(repo.nameExists("other"));

        GameSaveData overwrite = sampleData(
                "world-new", "Tower", 45, 4, "hardcore");
        overwrite.setReplacesWorldId("world-old");
        SaveResult result = repo.save(overwrite);
        assertTrue(result.success());
        assertEquals("world-new", result.worldId());

        // 旧档已删除；列表只显示新世界
        assertFalse(Files.exists(
                folder.getRoot().toPath().resolve("world-old.ser")));
        List<WorldSaveEntry> entries = repo.list();
        assertEquals(1, entries.size());
        assertEquals("world-new", entries.get(0).summary().worldId());
    }

    // ---------- P25-SAVE-03 首次保存立即落盘 ----------

    @Test
    public void firstSavePersistsAndReloads() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        SaveResult result = repo.save(
                sampleDataWithEnemy("world-a", "First"));
        assertTrue(result.success());
        LoadResult loaded = repo.load("world-a");
        assertTrue("load failed: " + loaded.failureReason(), loaded.success());
        assertEquals("First", loaded.data().getWorldName());
    }

    // ---------- P25-SAVE-04 原子失败不破坏旧文件 ----------

    @Test
    public void writeFailureKeepsExistingWorldIntact() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        assertTrue(repo.save(
                sampleData("world-a", "Alpha", 80, 1, "balanced")).success());

        // 坏根目录：其父路径是文件，createDirectories 必然失败
        Path blocker = folder.newFile("blocker").toPath();
        WorldSaveRepository badRepo =
                repo(blocker.resolve("sub"));

        SaveResult failed = badRepo.save(
                sampleData("world-b", "Beta", 50, 1, "balanced"));
        assertFalse(failed.success());
        assertNotNull(failed.failureReason());

        // 旧世界未受影响
        LoadResult loaded = repo.load("world-a");
        assertTrue("load failed: " + loaded.failureReason(), loaded.success());
        assertEquals(80, loaded.data().getRunCurrentHp());
    }

    @Test
    public void successfulSaveLeavesNoTempFiles() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        repo.save(sampleData("world-a", "Alpha", 80, 1, "balanced"));
        try (java.util.stream.Stream<Path> stream =
                     Files.list(folder.getRoot().toPath())) {
            assertTrue(stream.noneMatch(
                    p -> p.getFileName().toString().contains(".tmp-")));
        }
    }

    // ---------- P25-SAVE-05 坏档与旧档隔离 ----------

    @Test
    public void corruptFileDoesNotBreakListOrDeleteItself() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        repo.save(sampleData("world-a", "Alpha", 80, 1, "balanced"));
        Path corrupt = folder.getRoot().toPath().resolve("corrupt.ser");
        Files.write(corrupt, "not a serialized game"
                .getBytes(StandardCharsets.UTF_8));

        List<WorldSaveEntry> entries = repo.list();
        assertEquals(2, entries.size());
        boolean sawCorrupt = false;
        boolean sawAlpha = false;
        for (WorldSaveEntry entry : entries) {
            if (entry.isReadable()) {
                sawAlpha |= entry.summary().worldName().equals("Alpha");
            } else {
                sawCorrupt |= entry.failurePath().equals("corrupt.ser");
            }
        }
        assertTrue(sawAlpha);
        assertTrue(sawCorrupt);
        assertFalse("corrupt file must not be deleted",
                repo.load("corrupt").success());
        assertTrue(Files.exists(corrupt));
    }

    // ---------- P25-SAVE-06 状态完整恢复 ----------

    @Test
    public void roundTripRestoresFullTypedState() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        GameSaveData original = sampleDataWithEnemy("world-a", "RoundTrip");
        assertTrue(repo.save(original).success());

        LoadResult loaded = repo.load("world-a");
        assertTrue("load failed: " + loaded.failureReason(), loaded.success());
        GameSaveData restored = loaded.data();
        assertEquals(60, restored.getRunCurrentHp());
        assertEquals(2, restored.getFloorLevel());
        assertEquals(new Position(3, 3), restored.getFloorPlayerPosition());
        assertEquals(25, restored.getFloorPlayerCharge());
        assertEquals(new Position(12, 8), restored.getStairsPosition());
        assertEquals(1, restored.getHealthPacks().size());
        assertEquals(new HealthPackPosition(5, 5),
                restored.getHealthPacks().get(0));
        assertEquals(1, restored.getEnemyStates().size());
        EnemySaveData enemy = restored.getEnemyStates().get(0);
        assertEquals("guard-a", enemy.getAgentId());
        assertEquals(14, enemy.getHp());
        assertEquals(20, enemy.getMaxHp());
        assertEquals("EAST", enemy.getFacing());
        assertNotNull(enemy.getPatrolState());
        assertEquals(PatrolState.Mode.TRAVELING,
                enemy.getPatrolState().getMode());
        assertEquals(new Position(4, 4),
                enemy.getPatrolState().getTarget());
        assertEquals(1, enemy.getPatrolState().getBlockedAttempts());
        assertEquals(2, enemy.getPatrolState().getSelectionOrdinal());
    }

    // ---------- P25-SAVE-07 路径安全 ----------

    @Test
    public void invalidWorldIdsCannotEscapeSaveRoot() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        for (String evil : List.of(
                "../evil", "a\\b", "a/b", "C:evil", "", "..", ".")) {
            SaveResult result = repo.save(sampleData(evil, "Evil", 1, 1,
                    "balanced"));
            assertFalse("worldId '" + evil + "' must be rejected",
                    result.success());
            assertFalse("load '" + evil + "' must be rejected",
                    repo.load(evil).success());
        }
        // 根目录内没有被写入任何文件
        try (java.util.stream.Stream<Path> stream =
                     Files.list(folder.getRoot().toPath())) {
            assertTrue(stream.findAny().isEmpty());
        }
    }

    // ---------- P25-SAVE-08 保存时间来自注入时钟 ----------

    @Test
    public void savedAtComesFromInjectedClock() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        repo.save(sampleData("world-a", "Alpha", 80, 1, "balanced"));
        LoadResult loaded = repo.load("world-a");
        assertEquals(FIXED_EPOCH,
                loaded.data().getSavedAtEpochMillis());
        assertEquals(FIXED_EPOCH,
                repo.list().get(0).summary().savedAtEpochMillis());
    }

    // ---------- P25-SAVE-09 覆盖清理失败：新档保留、旧项隐藏 ----------

    @Test
    public void leftoverReplacedWorldIsHiddenFromList() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        repo.save(sampleData("world-old", "Tower", 80, 1, "balanced"));
        GameSaveData overwrite = sampleData(
                "world-new", "Tower", 45, 4, "hardcore");
        overwrite.setReplacesWorldId("world-old");
        assertTrue(repo.save(overwrite).success());
        // 正常情况旧档已删除
        assertFalse(Files.exists(
                folder.getRoot().toPath().resolve("world-old.ser")));

        // 模拟删除失败后的残留：把旧档内容手工写回
        GameSaveData leftover = sampleData("world-old", "Tower", 80, 1,
                "balanced");
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream(folder.getRoot()
                        .toPath().resolve("world-old.ser").toFile()))) {
            out.writeObject(leftover);
        }

        // 新世界的 replacement marker 仍指向旧档 → 列表只显示新世界
        List<WorldSaveEntry> entries = repo.list();
        assertEquals(1, entries.size());
        assertEquals("world-new", entries.get(0).summary().worldId());
        // 第一次覆盖保存时旧档仍在 → marker 保留并完成删除
        GameSaveData refresh = sampleData(
                "world-new", "Tower", 40, 5, "hardcore");
        refresh.setReplacesWorldId("world-old");
        assertTrue(repo.save(refresh).success());
        LoadResult loaded = repo.load("world-new");
        assertEquals("world-old", loaded.data().getReplacesWorldId());
        // 旧档已确认删除 → 下一次保存清空 marker
        GameSaveData refresh2 = sampleData(
                "world-new", "Tower", 40, 5, "hardcore");
        refresh2.setReplacesWorldId("world-old");
        assertTrue(repo.save(refresh2).success());
        assertEquals(null,
                repo.load("world-new").data().getReplacesWorldId());
    }

    // ---------- 反序列化白名单 ----------

    @Test
    public void deserializationRejectsUnknownClasses() throws Exception {
        WorldSaveRepository repo = repo(folder.getRoot().toPath());
        Path evil = folder.getRoot().toPath().resolve("evil.ser");
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream(evil.toFile()))) {
            out.writeObject(new EvilPayload());
        }

        LoadResult rejected = repo.load("evil");
        assertFalse(rejected.success());
        assertTrue(rejected.failureReason().contains("class not allowed"));
        // 坏档不阻断其他世界
        assertTrue(repo.save(sampleData("world-a", "Alpha", 80, 1,
                "balanced")).success());
        assertTrue(repo.load("world-a").success());
    }

    /** 未知类负载：白名单必须拒绝，防止反序列化 gadget。 */
    private static final class EvilPayload implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
    }

    // ---------- 世界名校验 ----------

    @Test
    public void worldNameValidationAndComparisonKey() {
        assertEquals("Alpha World",
                WorldName.validateForDisplay("  Alpha World  "));
        assertEquals("世界",
                WorldName.validateForDisplay("世界"));
        for (String invalid : List.of("", "   ",
                "a\nb", "a\rb", "c\u0000d",
                "x".repeat(33))) {
            try {
                WorldName.validateForDisplay(invalid);
                assertTrue("must reject: '" + invalid + "'", false);
            } catch (IllegalArgumentException expected) {
                // 期望的拒绝
            }
        }
        assertEquals("alpha world",
                WorldName.comparisonKey("Alpha World"));
        assertEquals("alpha world",
                WorldName.comparisonKey("ALPHA WORLD"));
    }
}
