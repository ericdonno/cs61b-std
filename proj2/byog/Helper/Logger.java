package byog.Helper;

public class Logger {
    public enum Level {
        DEBUG,
        INFO,
        ERROR,
        OFF
    }

    private static final String SEPARATOR = "=".repeat(50);
    private static final String SUB_SEPARATOR = "-".repeat(30);
    private static Level level = parseLevel(
            System.getProperty("dungeonmind.log.level", "DEBUG"));

    public static void debug(String format, Object... args) {
        if (isEnabled(Level.DEBUG)) {
            System.out.println("[DEBUG] " + String.format(format, args));
        }
    }

    public static void info(String format, Object... args) {
        if (isEnabled(Level.INFO)) {
            System.out.println("[INFO] " + String.format(format, args));
        }
    }

    public static void error(String format, Object... args) {
        if (isEnabled(Level.ERROR)) {
            System.err.println("[ERROR] " + String.format(format, args));
        }
    }

    public static void section(String title) {
        if (isEnabled(Level.INFO)) {
            System.out.println();
            System.out.println(SEPARATOR);
            System.out.println("  " + title);
            System.out.println(SEPARATOR);
        }
    }

    public static void subsection(String title) {
        if (isEnabled(Level.INFO)) {
            System.out.println();
            System.out.println(SUB_SEPARATOR);
            System.out.println("  " + title);
            System.out.println(SUB_SEPARATOR);
        }
    }

    public static Level getLevel() {
        return level;
    }

    public static void setLevel(Level newLevel) {
        if (newLevel == null) {
            throw new IllegalArgumentException("newLevel must not be null");
        }
        level = newLevel;
    }

    private static boolean isEnabled(Level messageLevel) {
        return level != Level.OFF && messageLevel.ordinal() >= level.ordinal();
    }

    private static Level parseLevel(String value) {
        try {
            return Level.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Level.DEBUG;
        }
    }
}
