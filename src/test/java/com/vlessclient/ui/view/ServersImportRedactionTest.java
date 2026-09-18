package com.vlessclient.ui.view;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.app.ThemeCss;
import com.vlessclient.model.ServerConfig;
import com.vlessclient.service.ConfigStore;
import com.vlessclient.service.ServerBackupService;
import com.vlessclient.service.ShareLinkParser;
import com.vlessclient.service.TestConfigStores;
import com.vlessclient.testing.Await;
import com.vlessclient.testing.UiTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A malformed share link's credential stays out of {@code tunl.log} and out of
 * the dialogs: on the link dialog, which reads links the way the clipboard
 * import does and logs counts only, and on a link list imported from a file,
 * which logs why the first link failed.
 *
 * <p>Both logged the parser's message, and the parser's message quoted the
 * link: {@link java.net.URI} ends its complaint with the whole input, here
 * {@code http://<uuid-or-password>@host…}. The file import logged it through
 * the exception, so a check of the formatted message alone passed while the
 * credential sat in the stack trace printed under it. What is asserted here is
 * every event rendered the way the log file receives it, trace included.</p>
 *
 * <p>Two layers are pinned apart. The real parser must keep the credential
 * out of its message: a password with a space in it survives any scrubbing
 * afterwards, since the space is where a URL ends. And the view must scrub
 * whatever it logs regardless, which the doubles check by quoting a
 * well-formed link back, the way a JDK or library message does.</p>
 *
 * <p>Both imports end in a dialog opened with {@code showAndWait}, so the menu
 * items are fired through {@link Platform#runLater}: {@code interact} would
 * wait for that dialog to close, and closing it is this test's job.</p>
 */
@UiTest
public class ServersImportRedactionTest extends ApplicationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String UUID_CREDENTIAL = "0b7e5f2a-4c1d-4e8f-9a3b-6d2c1e0f9a8b";

    /** Malformed inside the password, where scrubbing the message cannot reach. */
    private static final String TROJAN_SPACE_IN_PASSWORD =
            "trojan://alpha7 omega9@198.51.100.8:8443#Broken";

    /** Malformed after the credential: a raw space in the path. */
    private static final String VLESS_SPACE_IN_PATH =
            "vless://" + UUID_CREDENTIAL + "@198.51.100.7:443?type=ws&path=/a b#Broken";

    /** Parses fine; the doubles quote it back in their messages. */
    private static final String WELL_FORMED =
            "vless://" + UUID_CREDENTIAL + "@198.51.100.7:443?type=tcp#Quoted";

    /** {@link #WELL_FORMED} as {@code Redact.url} reduces it. */
    private static final String WELL_FORMED_REDACTED = "vless://198.51.100.7:443/…";

    @TempDir
    static Path tempDir;

    private final List<String> dialogTexts = new ArrayList<>();
    private ConfigStore store;
    private ServersViewController controller;
    private Stage stage;

    @Override
    public void start(Stage stage) throws IOException {
        this.stage = stage;
        store = TestConfigStores.at(tempDir.resolve(UUID.randomUUID().toString()));
        ServiceLocator.register(ConfigStore.class, store);
        // Registered for every method, so a double one method installs is
        // gone by the next.
        ServiceLocator.register(ShareLinkParser.class, new ShareLinkParser());
        ServiceLocator.register(ServerBackupService.class,
                new ServerBackupService(store, new ShareLinkParser()));
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/ServersView.fxml"));
        Scene scene = new Scene(loader.load(), 900, 640);
        controller = loader.getController();
        scene.getStylesheets().addAll(ThemeCss.light());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void closeDialogs() {
        // Hiding a dialog also ends the showAndWait a failed assertion left
        // waiting on it.
        interact(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window != stage) {
                    window.hide();
                }
            }
        });
    }

    @Test
    void aMalformedLinkInTheLinkDialogStaysOutOfTheLog() {
        String log = logWhile(() -> importThroughTheLinkDialog(TROJAN_SPACE_IN_PASSWORD));

        assertNoCredential(log, "alpha7", "omega9");
        assertThat(log)
                .as("the skipped link is counted")
                .contains("Imported no servers from text: 1 link(s) skipped");
        assertThat(String.join("\n", dialogTexts))
                .as("the report says why")
                .contains("Missing password in Trojan URI");
        assertNoCredential(String.join("\n", dialogTexts), "alpha7", "omega9");
    }

    @Test
    void aFileOfMalformedLinksStaysOutOfTheLog() throws IOException {
        Path links = writeFile(TROJAN_SPACE_IN_PASSWORD + "\n" + VLESS_SPACE_IN_PATH + "\n");

        String log = logWhile(() -> importFromAFile(links));

        assertNoCredential(log, "alpha7", "omega9", UUID_CREDENTIAL);
        assertThat(log)
                .as("the failure is still logged, with the first line's reason")
                .contains("Failed to import servers")
                .contains("Illegal character in authority");
        assertNoCredential(String.join("\n", dialogTexts), "alpha7", "omega9", UUID_CREDENTIAL);
    }

    @Test
    void whatTheParserQuotesIsScrubbedBeforeTheLinkDialogShowsIt() {
        String log = logWhile(() -> {
            ServiceLocator.register(ServerBackupService.class, new ServerBackupService(store,
                    parserFailingWith(link -> new IllegalArgumentException("Cannot read " + link))));
            importThroughTheLinkDialog(WELL_FORMED);
            ServiceLocator.register(ServerBackupService.class, new ServerBackupService(store,
                    parserFailingWith(link -> new IllegalStateException("Cannot read " + link))));
            importThroughTheLinkDialog(WELL_FORMED);
        });

        assertNoCredential(log, UUID_CREDENTIAL);
        assertNoCredential(String.join("\n", dialogTexts), UUID_CREDENTIAL);
        assertThat(dialogTexts)
                .as("bad input and an unexpected failure are both reported, the link cut to its host")
                .hasSize(2)
                .allSatisfy(text -> assertThat(text)
                        .contains("Cannot read " + WELL_FORMED_REDACTED));
    }

    @Test
    void whatTheBackupServiceQuotesIsScrubbedBeforeTheFileImportLogsIt() throws IOException {
        Path anyFile = writeFile(WELL_FORMED + "\n");
        ServiceLocator.register(ServerBackupService.class,
                new ServerBackupService(store, new ShareLinkParser()) {
                    @Override
                    public ImportResult importFile(Path file) {
                        throw new IllegalArgumentException("Cannot read " + WELL_FORMED);
                    }
                });

        String log = logWhile(() -> importFromAFile(anyFile));

        assertNoCredential(log, UUID_CREDENTIAL);
        assertThat(log)
                .as("the failure is logged with its type, the link cut to its host")
                .contains("Failed to import servers: java.lang.IllegalArgumentException: "
                        + "Cannot read " + WELL_FORMED_REDACTED);
    }

    /**
     * Everything logged while {@code steps} ran, as the log file receives it:
     * each message, and under it the stack trace of any exception it carries.
     */
    private static String logWhile(Runnable steps) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger viewLog = (Logger) LoggerFactory.getLogger(ServersViewController.class);
        Logger serviceLog = (Logger) LoggerFactory.getLogger(ServerBackupService.class);
        Level viewLevel = viewLog.getLevel();
        Level serviceLevel = serviceLog.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        // Everything both classes say, debug included, whatever the logging
        // configuration of the build would have let through.
        viewLog.setLevel(Level.DEBUG);
        serviceLog.setLevel(Level.DEBUG);
        try {
            steps.run();
        } finally {
            viewLog.setLevel(viewLevel);
            serviceLog.setLevel(serviceLevel);
            root.detachAppender(appender);
        }

        // No %ex in the pattern: PatternLayout then appends the stack trace
        // itself, as it does for the file appender's pattern.
        PatternLayout layout = new PatternLayout();
        layout.setContext(root.getLoggerContext());
        layout.setPattern("%-5level %logger{36} - %msg%n");
        layout.start();
        StringBuilder rendered = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            rendered.append(layout.doLayout(event));
        }
        layout.stop();
        return rendered.toString();
    }

    /**
     * Types {@code text} into the link dialog, confirms it, and closes the
     * report of what could not be imported.
     */
    private void importThroughTheLinkDialog(String text) {
        MenuItem linkItem = menuItem("#importMenuButton", "importLinkItem");
        Platform.runLater(linkItem::fire);
        DialogPane input = awaitDialog("the link dialog",
                pane -> pane.getContent() instanceof TextArea);
        interact(() -> {
            ((TextArea) input.getContent()).setText(text);
            ((Button) input.lookupButton(ButtonType.OK)).fire();
        });
        closeError("servers.import.clipboard.nothing");
    }

    /** Imports {@code file} from the backup menu and closes the failure it ends with. */
    private void importFromAFile(Path file) {
        controller.setImportFileChooser(owner -> file.toFile());
        MenuItem importItem = menuItem("#backupMenuButton", "importServersItem");
        Platform.runLater(importItem::fire);
        closeError("servers.backup.import.failed");
    }

    /** Waits for the dialog with this header, keeps its text and closes it. */
    private void closeError(String headerKey) {
        DialogPane error = awaitDialog("the error dialog",
                pane -> I18n.get(headerKey).equals(pane.getHeaderText()));
        dialogTexts.add(error.getContentText());
        interact(() -> ((Button) error.lookupButton(ButtonType.OK)).fire());
        // The import's handler resumes once its dialog is closed; let it finish.
        WaitForAsyncUtils.waitForFxEvents();
    }

    private DialogPane awaitDialog(String what, Predicate<DialogPane> matches) {
        return Await.untilValue(what, () -> showingDialog(matches), Objects::nonNull, TIMEOUT);
    }

    private DialogPane showingDialog(Predicate<DialogPane> matches) {
        AtomicReference<DialogPane> found = new AtomicReference<>();
        interact(() -> {
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && window.getScene() != null
                        && window.getScene().getRoot().lookup(".dialog-pane")
                                instanceof DialogPane pane
                        && matches.test(pane)) {
                    found.set(pane);
                }
            }
        });
        return found.get();
    }

    private MenuItem menuItem(String menuQuery, String itemId) {
        MenuButton menu = lookup(menuQuery).query();
        return menu.getItems().stream()
                .filter(item -> itemId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(menuQuery + " has no item " + itemId));
    }

    /** A parser that fails every link with an exception quoting it. */
    private static ShareLinkParser parserFailingWith(Function<String, RuntimeException> failure) {
        return new ShareLinkParser() {
            @Override
            public ServerConfig parse(String uri) {
                throw failure.apply(uri);
            }
        };
    }

    private static void assertNoCredential(String text, String... fragments) {
        for (String fragment : fragments) {
            assertThat(text).doesNotContain(fragment);
        }
    }

    private static Path writeFile(String contents) throws IOException {
        Path file = tempDir.resolve(UUID.randomUUID() + ".txt");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
