package com.aresstack.audio.launcher;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.JOptionPane;

/**
 * Entry point of the runnable JAR.
 *
 * <p>Audio Studio is compiled for Java 21. This class and {@link JavaRuntimeFinder}
 * are compiled <em>for Java 8</em> and have no compile-time dependency on the studio:
 * a class file carries its own version, and a JVM only minds the version of the
 * classes it loads. Started by Java 21 or newer, the launcher starts the studio in
 * the same JVM. Started by an older Java, it looks for a Java 21 on this computer
 * and starts that one with this same JAR. So the JAR runs with a plain
 * {@code java -jar} or a double-click, whichever Java the computer starts JARs with.
 * The code is a copy of the launcher of 3d4j, with its own settings; the two
 * programs share nothing at run time.</p>
 *
 * <p>The Java 21 to use can be named with the system property {@code audio.java}
 * or the environment variable {@code AUDIO_STUDIO_JAVA} (its folder or its
 * {@code java} executable).</p>
 */
public final class Launcher {
    /** The Java feature version the studio is compiled for. */
    private static final int REQUIRED_JAVA = 21;
    private static final String MAIN = "com.aresstack.audio.studio.ui.AudioStudioMain";
    private static final String RELAUNCHED_PROPERTY = "audio.relaunched";

    private Launcher() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            fail(t.toString(), true);
        }
    }

    private static void run(String[] args) throws Throwable {
        int java = JavaRuntimeFinder.majorOf(System.getProperty("java.specification.version"));
        if (java < REQUIRED_JAVA) {
            if (Boolean.getBoolean(RELAUNCHED_PROPERTY)) {
                throw new IllegalStateException("The Java found for the restart is Java " + java);
            }
            File memory = new File(new File(new File(System.getProperty("user.home"), ".aresstack"), "audio"),
                    "launcher.properties");
            File found = new JavaRuntimeFinder(REQUIRED_JAVA, memory).find();
            if (found == null) {
                fail("Audio Studio braucht Java " + REQUIRED_JAVA + ", gestartet wurde es mit Java " + java + ",\n"
                        + "und auf diesem Rechner wurde kein Java " + REQUIRED_JAVA + " gefunden.\n\n"
                        + "Gesucht wurde in JAVA_HOME, im PATH und in den üblichen Installationsordnern.\n"
                        + "Ein Java " + REQUIRED_JAVA + " an anderer Stelle nennt die Umgebungsvariable "
                        + "AUDIO_STUDIO_JAVA\n(oder -Daudio.java=...).", false);
            }
            // Errors of the second JVM are reported by that JVM; only its exit code is passed on.
            System.exit(relaunch(sameKind(found), args));
        }
        Class<?> mainClass = Class.forName(MAIN, true, Launcher.class.getClassLoader());
        Method main = mainClass.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Reports a fatal error and exits. A JAR started by double-click runs on
     * {@code javaw}, which has no console, so the message is also shown in a dialog there.
     */
    private static void fail(String message, boolean alreadyPrinted) {
        if (!alreadyPrinted) {
            System.err.println(message);
        }
        if (windowless() && !GraphicsEnvironment.isHeadless()) {
            try {
                JOptionPane.showMessageDialog(null, message, "Audio Studio", JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) {
                // No display available: the message on stderr has to do.
            }
        }
        System.exit(1);
    }

    /** Starts the found Java with this JAR and waits for it to finish. */
    private static int relaunch(String executable, String[] args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(executable);
        for (String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // Another Java may not know this one's options; system properties it always takes.
            if (jvmArg.startsWith("-D")) {
                command.add(jvmArg);
            }
        }
        command.add("-D" + RELAUNCHED_PROPERTY + "=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Launcher.class.getName());
        command.addAll(Arrays.asList(args));

        final Process child = new ProcessBuilder(command).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                child.destroy();
            }
        }, "audio-launcher-cleanup"));
        return child.waitFor();
    }

    /** For a found installation: javaw.exe if this JVM runs without a console, so that none opens. */
    private static String sameKind(File java) {
        if (windowless()) {
            File javaw = new File(java.getParentFile(), "javaw.exe");
            if (javaw.isFile()) {
                return javaw.getPath();
            }
        }
        return java.getPath();
    }

    /** Whether this JVM was started without a console, as a double-click on a JAR does. */
    private static boolean windowless() {
        String command = ownCommand();
        if (command != null) {
            return command.toLowerCase(Locale.ROOT).matches(".*javaw(\\.exe)?");
        }
        // Java 8 cannot say what it was started with: on Windows, no console is the sign.
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && System.console() == null;
    }

    /** The path of this JVM's executable; {@code null} on Java 8, which has no ProcessHandle. */
    private static String ownCommand() {
        try {
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object info = handleClass.getMethod("info").invoke(handleClass.getMethod("current").invoke(null));
            Object command = Class.forName("java.lang.ProcessHandle$Info").getMethod("command").invoke(info);
            Object path = command.getClass().getMethod("orElse", Object.class).invoke(command, (Object) null);
            return path == null ? null : path.toString();
        } catch (Throwable notAvailable) {
            return null;
        }
    }
}
