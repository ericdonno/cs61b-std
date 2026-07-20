package byog.Common;

public enum Difficulty {
    EASY("easy"),
    BALANCED("balanced"),
    HARDCORE("hardcore");

    private final String key;

    Difficulty(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    /** 从配置 key 解析，未知 key 默认返回 BALANCED */
    public static Difficulty fromKey(String key) {
        if (key == null) {
            return BALANCED;
        }
        for (Difficulty d : values()) {
            if (d.key.equalsIgnoreCase(key)) {
                return d;
            }
        }
        return BALANCED;
    }
}
