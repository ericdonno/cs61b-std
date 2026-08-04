package byog.IO;

import byog.lab5.Position;

import java.io.Serializable;

/**
 * 可序列化的苹果坐标。DTO 不依赖 lab5 Position 的序列化契约。
 */
public record HealthPackPosition(int x, int y) implements Serializable {
    private static final long serialVersionUID = 1L;

    public Position toPosition() {
        return new Position(x, y);
    }

    public static HealthPackPosition from(Position p) {
        if (p == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        return new HealthPackPosition(p.x, p.y);
    }
}
