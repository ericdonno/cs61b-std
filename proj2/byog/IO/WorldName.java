package byog.IO;

import java.util.Locale;

/**
 * 世界名校验与判重键。
 *
 * <p>显示名：去除首尾空白后 1–32 个 Unicode code point，允许中文和普通空格，
 * 拒绝换行与控制字符。判重键：locale-independent case fold，大小写不敏感。</p>
 */
public final class WorldName {

    public static final int MAX_CODE_POINTS = 32;

    private WorldName() {
    }

    /** 校验并返回可显示名；非法时抛出带原因的 IllegalArgumentException。 */
    public static String validateForDisplay(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("world name must not be blank");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "world name must be at most " + MAX_CODE_POINTS
                            + " code points");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\n' || c == '\r' || Character.isISOControl(c)) {
                throw new IllegalArgumentException(
                        "world name must not contain control characters");
            }
        }
        return trimmed;
    }

    /** 判重键：与大小写、语言环境无关。 */
    public static String comparisonKey(String displayName) {
        return displayName == null
                ? "" : displayName.toLowerCase(Locale.ROOT);
    }
}
