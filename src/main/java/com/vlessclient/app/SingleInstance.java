package com.vlessclient.app;

import com.vlessclient.platform.SecureFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps one copy of the app running per data directory.
 *
 * <p>A second launch used to start a second copy against the same files. Its
 * startup cleanup saw its own idle engine, took the running copy's system
 * proxy for one a crash had left behind and turned it off, so the browser went
 * around the tunnel while the first window still said Connected; after that
 * both copies wrote the same JSON files from their own memory. Now the first
 * copy holds a lock on a file in the data directory for as long as it runs,
 * and a second launch that cannot take it asks the running copy to show its
 * window and leaves before touching anything.</p>
 *
 * <p>The request travels over a loopback socket. The port and a random token
 * are written owner-only into the data directory, which is itself owner-only,
 * so another user of the machine can find the port but cannot use it. The
 * listener waits in {@code accept}, which costs nothing while nobody calls.
 * The operating system releases the lock when the process ends, however it
 * ends, so a crash never leaves the directory claimed.</p>
 */
final class SingleInstance {

    private static final Logger log = LoggerFactory.getLogger(SingleInstance.class);

    /** The file whose lock the running copy holds. */
    static final String LOCK_FILE = "instance.lock";

    /** Where the running copy leaves the loopback port and token a second launch uses. */
    static final String PORT_FILE = "instance.port";

    private static final String SHOW = "show";
    private static final String OPEN = "open";
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 2_000;

    /**
     * A request is a 64-digit token, a space and {@code show}, or
     * {@code open} and a link; anything longer is not one.
     */
    private static final int MAX_REQUEST_BYTES = 128 + DeepLinks.MAX_LENGTH * 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile SingleInstance current;

    /** What trying to take the data directory came to. */
    enum Claim {
        /** This process holds the directory. */
        CLAIMED,
        /** Another copy of the app holds it. */
        HELD_ELSEWHERE,
        /** No lock could be taken at all; the app starts unguarded, as it used to. */
        UNAVAILABLE
    }

    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;
    private final String token;
    private final Path portFile;
    private volatile Runnable showAction;
    private volatile Consumer<String> openAction;

    private SingleInstance(FileChannel channel, FileLock lock, ServerSocket server,
                           String token, Path portFile) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
        this.token = token;
        this.portFile = portFile;
    }

    /**
     * Tries to take the data directory for this process.
     *
     * @param dataDir the data directory this copy of the app works in
     * @return whether it was taken, is held by another copy, or cannot be locked
     */
    static synchronized Claim claim(Path dataDir) {
        FileChannel channel = null;
        try {
            SecureFiles.createPrivateDir(dataDir);
            channel = FileChannel.open(dataDir.resolve(LOCK_FILE),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = tryLock(channel);
            if (lock == null) {
                channel.close();
                return Claim.HELD_ELSEWHERE;
            }
            // The directory is this copy's from here on. The listener only lets a
            // second launch bring this window forward: a failure to start it
            // used to fall through to the catch below, which let the lock go
            // and left this copy running unguarded.
            String token = newToken();
            Path portFile = dataDir.resolve(PORT_FILE);
            ServerSocket server = startListener(portFile, token);
            SingleInstance instance = new SingleInstance(channel, lock, server, token, portFile);
            if (server != null) {
                instance.listen();
            }
            releaseOnExit(instance);
            current = instance;
            return Claim.CLAIMED;
        } catch (IOException e) {
            // A filesystem that cannot lock must not keep the app from starting:
            // it goes on unguarded, as every copy did before this existed.
            log.warn("Could not claim the data directory for this copy of the app: {}",
                    e.getMessage());
            closeQuietly(channel);
            return Claim.UNAVAILABLE;
        }
    }

    /**
     * Opens the loopback listener a second launch signals, and tells where it
     * is; null when it cannot be started, which leaves a second launch unable
     * to bring this window forward but changes nothing else.
     */
    private static ServerSocket startListener(Path portFile, String token) {
        ServerSocket server = null;
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            SecureFiles.writePrivately(portFile, (server.getLocalPort() + " " + token + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return server;
        } catch (IOException e) {
            log.warn("A second launch will not be able to bring this window forward: {}",
                    e.getMessage());
            closeQuietly(server);
            return null;
        }
    }

    /**
     * Takes the data directory for this process.
     *
     * @param dataDir the data directory this copy of the app works in
     * @return this process's hold on it, or empty when it could not be taken
     */
    static Optional<SingleInstance> acquire(Path dataDir) {
        return claim(dataDir) == Claim.CLAIMED ? Optional.ofNullable(current) : Optional.empty();
    }

    /**
     * The hold this process has on its data directory.
     *
     * @return the hold, or empty when this process has none
     */
    static Optional<SingleInstance> current() {
        return Optional.ofNullable(current);
    }

    /**
     * Asks the copy of the app that holds the data directory to show its window.
     *
     * @param dataDir the data directory the running copy holds
     * @return whether the request was delivered
     */
    static boolean signalRunning(Path dataDir) {
        return signalRunning(dataDir, null);
    }

    /**
     * Asks the copy of the app that holds the data directory to show its
     * window and, when {@code link} is given, to open it.
     *
     * @param dataDir the data directory the running copy holds
     * @param link    a link in Tunl's scheme to open, or null
     * @return whether the request was delivered
     */
    static boolean signalRunning(Path dataDir, String link) {
        String request = DeepLinks.isLink(link) ? OPEN + " " + link : SHOW;
        try {
            String[] parts = Files.readString(dataDir.resolve(PORT_FILE), StandardCharsets.US_ASCII)
                    .strip().split(" ");
            if (parts.length != 2) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        Integer.parseInt(parts[0])), CONNECT_TIMEOUT_MS);
                try (OutputStream out = socket.getOutputStream()) {
                    out.write((parts[1] + " " + request + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            return true;
        } catch (IOException | NumberFormatException e) {
            log.debug("No running copy of the app answered: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Sets what a second launch's request does: bring the window forward.
     * It runs on the listener thread, so an action that touches the scene has
     * to hand itself to the JavaFX thread.
     *
     * @param action what to do when a second launch asks
     */
    void onShowRequest(Runnable action) {
        this.showAction = action;
    }

    /**
     * Sets what a second launch's request to open a link does. It runs on
     * the listener thread, after the show request's action.
     *
     * @param action what to do with the link
     */
    void onOpenRequest(Consumer<String> action) {
        this.openAction = action;
    }

    /** Lets go of the data directory. Safe to call more than once. */
    void close() {
        closeQuietly(server);
        try {
            Files.deleteIfExists(portFile);
        } catch (IOException e) {
            log.debug("Could not remove {}: {}", portFile, e.getMessage());
        }
        closeQuietly(lock);
        closeQuietly(channel);
        synchronized (SingleInstance.class) {
            if (current == this) {
                current = null;
            }
        }
    }

    private void listen() {
        Thread.ofPlatform().daemon().name("tunl-single-instance").start(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(READ_TIMEOUT_MS);
                    handle(request(socket.getInputStream()));
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        log.debug("Ignoring a request that did not arrive whole: {}",
                                e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * What a request asks for once its token checks out: {@code show}, or
     * {@code open} and a link; null for anything else.
     */
    private String request(InputStream in) throws IOException {
        String text = new String(in.readNBytes(MAX_REQUEST_BYTES), StandardCharsets.UTF_8).strip();
        int space = text.indexOf(' ');
        if (space < 0 || !MessageDigest.isEqual(
                text.substring(0, space).getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return text.substring(space + 1);
    }

    private void handle(String request) {
        if (request == null) {
            return;
        }
        boolean open = request.startsWith(OPEN + " ");
        if (!open && !request.equals(SHOW)) {
            return;
        }
        Runnable show = showAction;
        if (show != null) {
            show.run();
        }
        String link = open ? request.substring(OPEN.length() + 1) : null;
        Consumer<String> opener = openAction;
        if (link != null && DeepLinks.isLink(link) && opener != null) {
            opener.accept(link);
        }
    }

    /** The lock, or null when another holder has it, in this process or another one. */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException heldInThisProcess) {
            return null;
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void releaseOnExit(SingleInstance instance) {
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(instance::close, "tunl-single-instance-release"));
        } catch (IllegalStateException | SecurityException e) {
            log.debug("The data directory is released by the OS at exit instead: {}",
                    e.getMessage());
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("Could not close {}: {}", closeable, e.getMessage());
        }
    }
}
