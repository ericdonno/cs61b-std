package byog.Helper;

public class Logger {
    private static final String SEPARATOR = "=".repeat(50);
    private static final String SUB_SEPARATOR = "-".repeat(30);

    public static void debug(String format, Object... args) {
        System.out.println("[DEBUG] " + String.format(format, args));
    }

    public static void info(String format, Object... args) {
        System.out.println("[INFO] " + String.format(format, args));
    }

    public static void error(String format, Object... args) {
        System.err.println("[ERROR] " + String.format(format, args));
    }

    public static void section(String title) {
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  " + title);
        System.out.println(SEPARATOR);
    }

    public static void subsection(String title) {
        System.out.println();
        System.out.println(SUB_SEPARATOR);
        System.out.println("  " + title);
        System.out.println(SUB_SEPARATOR);
    }
}