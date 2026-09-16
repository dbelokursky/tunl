package com.vlessclient.platform;

import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The command {@link ProcessTimeoutTest} runs, in a separate JVM.
 *
 * <ul>
 *   <li>{@code hang [PIDFILE]}: writes its pid to PIDFILE when given, prints a
 *       line, then keeps its output open for half a minute, like a command
 *       stuck on a prompt nobody answers. It ends on its own, so a failing run
 *       leaves nothing behind for long.</li>
 *   <li>{@code orphan PIDFILE}: starts {@code hang PIDFILE} with its own output
 *       handed down, then exits at once and leaves that child holding it.</li>
 *   <li>{@code exit N}: prints a line and exits with code N.</li>
 *   <li>{@code flood N}: prints N bytes.</li>
 *   <li>{@code echo}: copies its input to its output.</li>
 *   <li>{@code touch PATH}: creates the file at PATH.</li>
 * </ul>
 */
final class StandInCommand {

    private StandInCommand() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "hang" -> {
                if (args.length > 1) {
                    writePid(Path.of(args[1]));
                }
                System.out.println("started");
                System.out.flush();
                Thread.sleep(30_000);
            }
            case "orphan" -> {
                new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"),
                        "-cp", System.getProperty("java.class.path"),
                        StandInCommand.class.getName(), "hang", args[1])
                        .redirectOutput(Redirect.INHERIT)
                        .redirectError(Redirect.INHERIT)
                        .start();
                System.out.println("started");
                System.out.flush();
            }
            case "exit" -> {
                System.out.println("started");
                System.out.flush();
                System.exit(Integer.parseInt(args[1]));
            }
            case "flood" -> {
                byte[] chunk = "y".repeat(8192).getBytes(StandardCharsets.US_ASCII);
                for (int left = Integer.parseInt(args[1]); left > 0; left -= chunk.length) {
                    System.out.write(chunk, 0, Math.min(left, chunk.length));
                }
                System.out.flush();
            }
            case "echo" -> {
                System.in.transferTo(System.out);
                System.out.flush();
            }
            case "touch" -> Files.createFile(Path.of(args[1]));
            default -> throw new IllegalArgumentException("unknown mode " + args[0]);
        }
    }

    /** Written whole, so a reader never sees half a pid. */
    private static void writePid(Path file) throws Exception {
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        Files.writeString(partial, Long.toString(ProcessHandle.current().pid()));
        Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
    }
}
