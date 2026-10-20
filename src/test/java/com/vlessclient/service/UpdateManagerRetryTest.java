package com.vlessclient.service;

import com.vlessclient.platform.PendingUpdate;
import com.vlessclient.platform.UpdateApplier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.vlessclient.service.SignedTestRelease.checkWith;
import static com.vlessclient.service.SignedTestRelease.ed25519;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A download that fails on the way is tried again on its own.
 *
 * <p>It used to wait for the next check: six hours, or the tunnel coming up
 * again, while the Dashboard said the app would try again and Settings said it
 * could not download. The commonest failure is the network's, a tunnel
 * switching over at startup or a link dropping mid-transfer, and a try minutes
 * later gets through. A download a check refused is not tried again: the same
 * bytes would be refused again.</p>
 */
class UpdateManagerRetryTest {

    private static final String VERSION = "999.0.0";

    /** An applier that installs updates itself, whatever host the test runs on. */
    private static final UpdateApplier SELF_UPDATING = new UpdateApplier() {
        @Override
        public Outcome apply(PendingUpdate update) {
            return Outcome.FAILED;
        }

        @Override
        public boolean selfUpdates() {
            return true;
        }
    };

    @TempDir
    Path dir;

    private KeyPair key;
    private SignedTestRelease.ServingClient client;
    private SignedTestRelease release;
    private UpdateStaging staging;
    private ManualTimer timer;
    private UpdateManager manager;

    @BeforeEach
    void setUp() throws Exception {
        key = ed25519();
        client = new SignedTestRelease.ServingClient();
        release = new SignedTestRelease(VERSION, key.getPrivate(), client);
        UpdateStaging.ReleaseCheck check = checkWith(key);
        staging = new UpdateStaging(dir.resolve("staging"), check);
        timer = new ManualTimer();
        manager = new UpdateManager(client, staging, () -> SELF_UPDATING, check, timer);
    }

    @Test
    void aDownloadThatFailsOnTheWayIsTriedAgainAndStaged() {
        client.failNext(release.url, 1, new IOException("EOF reached while reading"));
        manager.processReleaseResponse(release.releaseJson());

        manager.autoDownloadIfAllowed();

        assertThat(manager.hasStagedUpdate()).isFalse();
        assertThat(timer.delays).containsExactly(UpdateDownloadPolicy.retryDelay(1));
        assertThat(manager.retryPending()).isTrue();

        timer.fire();

        assertThat(manager.hasStagedUpdate()).as("the retry got through").isTrue();
        assertThat(staging.pending()).map(PendingUpdate::version).contains(VERSION);
        assertThat(timer.delays).as("nothing more to try").hasSize(1);
        assertThat(manager.retryPending()).isFalse();
    }

    @Test
    void eachFailureInARowWaitsWhatThePolicySays() {
        client.failNext(release.url, 3, new IOException("closed"));
        manager.processReleaseResponse(release.releaseJson());

        manager.autoDownloadIfAllowed();
        timer.fire();
        timer.fire();
        timer.fire();

        assertThat(timer.delays).containsExactly(
                UpdateDownloadPolicy.retryDelay(1),
                UpdateDownloadPolicy.retryDelay(2),
                UpdateDownloadPolicy.retryDelay(3));
        assertThat(manager.hasStagedUpdate()).isTrue();
        assertThat(client.requestsTo(release.url)).isEqualTo(4);
    }

    @Test
    void aServerErrorIsTriedAgain() {
        client.answerNext(release.url, 1, 503);
        manager.processReleaseResponse(release.releaseJson());

        manager.autoDownloadIfAllowed();
        timer.fire();

        assertThat(timer.delays).hasSize(1);
        assertThat(manager.hasStagedUpdate()).isTrue();
    }

    /**
     * A signature that could not be fetched is the network's failure, not the
     * release's: the release publishes one beside every installer.
     */
    @Test
    void aSignatureThatCouldNotBeFetchedIsTriedAgain() {
        client.answerNext(release.signatureUrl(), 1, 502);
        manager.processReleaseResponse(release.releaseJson());

        manager.autoDownloadIfAllowed();

        assertThat(manager.hasStagedUpdate()).isFalse();
        assertThat(timer.delays).hasSize(1);

        timer.fire();

        assertThat(manager.hasStagedUpdate()).isTrue();
    }

    @Test
    void anInstallerWhoseDigestDoesNotMatchIsNotTriedAgain() {
        client.serve(release.url, "not the release you were promised"
                .getBytes(StandardCharsets.UTF_8));
        manager.processReleaseResponse(release.releaseJson());

        manager.autoDownloadIfAllowed();

        assertThat(manager.hasStagedUpdate()).isFalse();
        assertThat(timer.delays).isEmpty();
        assertThat(manager.retryPending()).isFalse();
    }

    @Test
    void anInstallerWhoseSignatureDoesNotVerifyIsNotTriedAgain() throws Exception {
        SignedTestRelease forged = new SignedTestRelease("999.0.1", ed25519().getPrivate(), client);
        manager.processReleaseResponse(forged.releaseJson());

        manager.autoDownloadIfAllowed();

        assertThat(client.requestsTo(forged.url)).isEqualTo(1);
        assertThat(manager.hasStagedUpdate()).isFalse();
        assertThat(timer.delays).isEmpty();
    }

    @Test
    void aNewerReleaseCallsOffTheRetryAndStartsTheCountOver() throws Exception {
        client.failNext(release.url, 2, new IOException("closed"));
        manager.processReleaseResponse(release.releaseJson());
        manager.autoDownloadIfAllowed();
        timer.fire();
        assertThat(timer.delays).hasSize(2);
        CompletableFuture<?> waitingForTheOldOne = timer.lastHandle;

        SignedTestRelease newer = new SignedTestRelease("999.0.1", key.getPrivate(), client);
        client.failNext(newer.url, 1, new IOException("closed"));
        manager.processReleaseResponse(newer.releaseJson());

        assertThat(waitingForTheOldOne.isCancelled()).isTrue();

        manager.autoDownloadIfAllowed();

        assertThat(timer.delays.get(2))
                .as("a new release starts at the first wait")
                .isEqualTo(UpdateDownloadPolicy.retryDelay(1));

        timer.fire();

        assertThat(staging.pending()).map(PendingUpdate::version).contains("999.0.1");
    }

    @Test
    void aWithdrawnReleaseCallsOffItsRetry() {
        client.failNext(release.url, 1, new IOException("closed"));
        manager.processReleaseResponse(release.releaseJson());
        manager.autoDownloadIfAllowed();
        assertThat(manager.retryPending()).isTrue();

        // GitHub now names a release this build is not older than.
        assertThat(manager.processReleaseResponse("{\"tag_name\":\"v0.0.0\",\"assets\":[]}"))
                .isEqualTo(UpdateManager.CheckResult.UP_TO_DATE);

        assertThat(timer.lastHandle.isCancelled()).isTrue();
        assertThat(manager.retryPending()).isFalse();
    }

    /**
     * A manager that runs no timer, as in every other service test, schedules
     * no retry: the next check tries again.
     */
    @Test
    void aManagerThatRunsNoTimerSchedulesNoRetry() {
        UpdateManager unstarted = new UpdateManager(client, staging, () -> SELF_UPDATING,
                checkWith(key));
        client.failNext(release.url, 1, new IOException("closed"));
        unstarted.processReleaseResponse(release.releaseJson());

        unstarted.autoDownloadIfAllowed();

        assertThat(unstarted.hasStagedUpdate()).isFalse();
        assertThat(unstarted.retryPending()).isFalse();
    }

    /**
     * Two callers can reach the download at once, and the second is declined
     * while the first runs. That is no failure: counting it scheduled a retry
     * of a download that was going fine.
     */
    @Test
    void aDownloadDeclinedForOneUnderWayIsNotAFailure() throws Exception {
        SignedTestRelease.Gate held = client.holdNext(release.url);
        manager.processReleaseResponse(release.releaseJson());
        CompletableFuture<Void> first = CompletableFuture.runAsync(manager::autoDownloadIfAllowed);
        held.awaitEntered();

        assertThat(manager.downloadUpdate(release.url, release.digest))
                .as("declined while the first runs").isNull();
        assertThat(timer.delays).isEmpty();

        held.open();
        first.get(10, TimeUnit.SECONDS);

        assertThat(manager.hasStagedUpdate()).isTrue();
        assertThat(timer.delays).isEmpty();
        assertThat(manager.retryPending()).isFalse();
    }

    /** Records each retry and runs the latest when the test says. */
    private static final class ManualTimer implements UpdateManager.RetryTimer {

        final List<Duration> delays = new ArrayList<>();
        private Runnable next;
        CompletableFuture<Void> lastHandle;

        @Override
        public Future<?> after(Duration delay, Runnable task) {
            delays.add(delay);
            next = task;
            lastHandle = new CompletableFuture<>();
            return lastHandle;
        }

        /** Runs the retry waiting now, as the scheduler would once its delay passed. */
        void fire() {
            assertThat(next).as("a retry is waiting").isNotNull();
            assertThat(lastHandle.isCancelled()).as("and was not called off").isFalse();
            Runnable task = next;
            CompletableFuture<Void> handle = lastHandle;
            next = null;
            task.run();
            handle.complete(null);
        }
    }
}
