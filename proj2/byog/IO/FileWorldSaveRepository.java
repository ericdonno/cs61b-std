package byog.IO;

import byog.Helper.Logger;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 文件系统命名世界存档实现。
 *
 * <p>所有 resolved path 都保持在注入根目录内：根目录先 absolute+normalize，
 * opaque worldId 通过正则校验，目标文件 parent 必须等于根目录。保存采用
 * sibling temp + atomic replace，失败不破坏旧文件；列表逐文件隔离，单个
 * 坏档不阻断其他世界且不自动删除。覆盖流程先写新档，成功后才删除旧档；
 * 删除失败时保留 replacement marker 并隐藏被替换入口。</p>
 */
public class FileWorldSaveRepository implements WorldSaveRepository {

    private static final Pattern OPAQUE_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*$");
    private static final String SUFFIX = ".ser";

    private final Path root;
    private final Clock clock;

    /** 生产根目录。 */
    public static final String DEFAULT_ROOT = "save/worlds";

    public FileWorldSaveRepository(Path root, Clock clock) {
        if (root == null || clock == null) {
            throw new IllegalArgumentException(
                    "root and clock must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
        this.clock = clock;
    }

    @Override
    public List<WorldSaveEntry> list() {
        List<WorldSaveEntry> entries = new ArrayList<>();
        Path dir = root;
        if (!Files.isDirectory(dir)) {
            return entries;
        }
        List<Path> files;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(p -> p.getFileName().toString()
                            .endsWith(SUFFIX))
                    .sorted(Comparator.comparing(
                            p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            Logger.error("World save list failed: %s", e.getMessage());
            return entries;
        }

        Map<String, WorldSaveSummary> byId = new HashMap<>();
        List<WorldSaveEntry> unreadable = new ArrayList<>();
        for (Path file : files) {
            try {
                GameSaveData data = readData(file);
                data.validate();
                String fileName = file.getFileName().toString();
                String fileWorldId = fileName.substring(
                        0, fileName.length() - SUFFIX.length());
                if (!fileWorldId.equals(data.getWorldId())) {
                    throw new IOException(
                            "worldId mismatch: file=" + fileWorldId
                                    + " content=" + data.getWorldId());
                }
                byId.put(data.getWorldId(), data.toSummary());
            } catch (IOException | ClassNotFoundException
                     | RuntimeException e) {
                Logger.error("Unreadable world save %s: %s",
                        file.getFileName(), e.getMessage());
                unreadable.add(WorldSaveEntry.unreadable(
                        file.getFileName().toString()));
            }
        }

        // 覆盖清理失败时：被替换世界文件仍存在，但被新世界明确 supersede。
        for (Map.Entry<String, WorldSaveSummary> entry
                : new HashMap<>(byId).entrySet()) {
            WorldSaveSummary summary = entry.getValue();
            String replaced = findReplacedId(summary);
            if (replaced != null && byId.containsKey(replaced)) {
                Logger.error(
                        "World '%s' supersedes unremoved '%s'; hiding old entry",
                        summary.worldName(), replaced);
                byId.remove(replaced);
            }
        }

        List<WorldSaveEntry> readable = new ArrayList<>();
        byId.values().stream()
                .sorted(Comparator.comparing(
                                (WorldSaveSummary s) -> WorldName
                                        .comparisonKey(s.worldName()))
                        .thenComparing(WorldSaveSummary::worldId))
                .forEach(s -> readable.add(WorldSaveEntry.readable(s)));

        readable.addAll(unreadable);
        return readable;
    }

    /** 从摘要反查被替换的旧 worldId（仅当新档仍携带 marker 且旧文件存在）。 */
    private String findReplacedId(WorldSaveSummary summary) {
        try {
            GameSaveData data = readData(root.resolve(
                    summary.worldId() + SUFFIX));
            return data.getReplacesWorldId();
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public SaveResult save(GameSaveData data) {
        if (data == null) {
            return SaveResult.failure("data must not be null");
        }
        String worldId = data.getWorldId();
        if (!isValidWorldId(worldId)) {
            return SaveResult.failure("invalid opaque worldId");
        }
        try {
            data.validate();
        } catch (IllegalArgumentException e) {
            return SaveResult.failure("invalid save data: " + e.getMessage());
        }
        Path target = resolveSibling(worldId + SUFFIX);
        if (target == null) {
            return SaveResult.failure("worldId escapes save root");
        }
        String replaced = data.getReplacesWorldId();
        if (replaced != null && isValidWorldId(replaced)) {
            Path old = resolveSibling(replaced + SUFFIX);
            if (old == null || !Files.exists(old)) {
                // 旧档已确认不存在：本次保存清空 replacement marker。
                data.setReplacesWorldId(null);
            }
        }
        Path temp = target.resolveSibling(
                worldId + SUFFIX + ".tmp-" + UUID.randomUUID());
        try {
            writeData(data, temp);
            moveAtomically(temp, target);
        } catch (IOException e) {
            Logger.error("Save failed for %s: %s", worldId, e.getMessage());
            return SaveResult.failure("write failed: " + e.getMessage());
        } finally {
            deleteQuietly(temp);
        }

        if (replaced != null && isValidWorldId(replaced)) {
            Path old = resolveSibling(replaced + SUFFIX);
            if (old != null) {
                try {
                    Files.deleteIfExists(old);
                } catch (IOException e) {
                    String warning = "old world " + replaced
                            + " cleanup failed: " + e.getMessage();
                    Logger.error(warning);
                    return SaveResult.okWithWarning(worldId, warning);
                }
            }
        }
        return SaveResult.ok(worldId);
    }

    @Override
    public LoadResult load(String worldId) {
        if (!isValidWorldId(worldId)) {
            return LoadResult.failure("invalid opaque worldId");
        }
        Path target = resolveSibling(worldId + SUFFIX);
        if (target == null || !Files.isRegularFile(target)) {
            return LoadResult.failure("world not found: " + worldId);
        }
        try {
            GameSaveData data = readData(target);
            if (!worldId.equals(data.getWorldId())) {
                return LoadResult.failure(
                        "worldId mismatch: file=" + worldId
                                + " content=" + data.getWorldId());
            }
            data.validate();
            return LoadResult.ok(data);
        } catch (IOException | ClassNotFoundException e) {
            return LoadResult.failure(
                    "read failed: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return LoadResult.failure(
                    "validation failed: " + e.getMessage());
        }
    }

    @Override
    public boolean nameExists(String normalizedName) {
        if (normalizedName == null) {
            return false;
        }
        for (WorldSaveEntry entry : list()) {
            if (entry.isReadable()
                    && WorldName.comparisonKey(entry.summary().worldName())
                    .equals(normalizedName)) {
                return true;
            }
        }
        return false;
    }

    // ---- internals ----

    /** 反序列化白名单：只接受存档 DTO 及其嵌套类型，拒绝其他类的 gadget。 */
    private static final java.util.Set<String> ALLOWED_CLASSES =
            java.util.Set.of(
                    "byog.IO.GameSaveData",
                    "byog.IO.WorldSaveSummary",
                    "byog.IO.EnemySaveData",
                    "byog.IO.HealthPackPosition",
                    "byog.IO.PlayerFloorState",
                    "byog.AI.PatrolState",
                    "byog.AI.PatrolState$Mode",
                    "java.lang.Enum",
                    "java.lang.String",
                    "java.lang.Integer",
                    "java.lang.Long",
                    "java.lang.Boolean",
                    "java.util.ArrayList",
                    "java.util.List",
                    "java.util.Collections$UnmodifiableList",
                    "java.util.Collections$UnmodifiableRandomAccessList");

    private GameSaveData readData(Path file)
            throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(
                new FileInputStream(file.toFile())) {
            @Override
            protected Class<?> resolveClass(
                    java.io.ObjectStreamClass descriptor)
                    throws IOException, ClassNotFoundException {
                if (!ALLOWED_CLASSES.contains(descriptor.getName())) {
                    throw new IOException(
                            "class not allowed in world save: "
                                    + descriptor.getName());
                }
                return super.resolveClass(descriptor);
            }
        }) {
            Object value = in.readObject();
            if (!(value instanceof GameSaveData data)) {
                throw new IOException(
                        "unexpected object type: "
                                + value.getClass().getName());
            }
            return data;
        }
    }

    private void writeData(GameSaveData data, Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ObjectOutputStream out = new ObjectOutputStream(
                new FileOutputStream(file.toFile()))) {
            out.writeObject(data);
            out.flush();
        }
    }

    private void moveAtomically(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 诊断由调用方完成；temp 残留不影响已提交的目标文件。
        }
    }

    /**
     * 由已校验 opaque ID 解析根目录内目标；越出根目录返回 null。
     */
    private Path resolveSibling(String fileName) {
        Path candidate = root.resolve(fileName).normalize();
        if (!candidate.getParent().equals(root)) {
            return null;
        }
        return candidate;
    }

    /** opaque ID 校验：拒绝路径分隔符、绝对路径、点号与空值。 */
    private static boolean isValidWorldId(String worldId) {
        return worldId != null && OPAQUE_ID.matcher(worldId).matches()
                && !worldId.contains("..")
                && !worldId.contains("/")
                && !worldId.contains("\\");
    }
}
