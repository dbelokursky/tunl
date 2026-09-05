package com.vlessclient.ui.view.dashboard;

import com.vlessclient.app.I18n;
import com.vlessclient.app.ServiceLocator;
import com.vlessclient.model.AppSettings;
import com.vlessclient.model.ConnectionState;
import com.vlessclient.service.DaemonThreads;
import com.vlessclient.service.TrafficMonitor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formats {@link TrafficMonitor} readings into the Dashboard's speed and
 * session-total readouts, and starts/stops the monitor as the tunnel goes up
 * and down. Extracted from
 * {@link com.vlessclient.ui.view.DashboardViewController}, which stays the
 * FXML endpoint and passes its injected controls in.
 *
 * <p>The readouts live in the hero card's status row since the standalone
 * "Real-time traffic" section and its mirrored chart were retired: the chart
 * auto-scaled each half to its own rolling maximum and carried no axis, so a
 * 3 KB/s blip and a 30 MB/s burst drew the same mountain and 150px of card
 * said nothing the two numbers did not.</p>
 */
public final class TrafficDisplayBinder {

    private static final Logger log = LoggerFactory.getLogger(TrafficDisplayBinder.class);

    /**
     * One direction's pair of controls: the chevron and the speed next to it.
     * They are painted together — an idle direction mutes both — so they
     * travel together rather than as four loose labels.
     *
     * @param icon the direction chevron, styled by its style class alone
     * @param speed the speed readout
     */
    public record Readout(Label icon, Label speed) { }

    private final TrafficMonitor trafficMonitor;
    private final Readout upload;
    private final Readout download;
    private final Label sessionTotalLabel;
    private final Node trafficSummary;

    /**
     * Last totals seen, kept so the session line can be re-rendered when the
     * language changes rather than only when the next sample lands — a locale
     * switch on an idle tunnel would otherwise leave the old wording on
     * screen until traffic resumed.
     */
    private long lastTotalUpload;
    private long lastTotalDownload;

    /**
     * Serialises start and stop off the FX thread.
     *
     * <p>{@code TrafficMonitor.stop()} joins its streaming thread for up to two
     * seconds, and this class is driven from connection-state changes on the FX
     * thread — so a disconnect froze the window for as long as the core took to
     * let go. Handing the call to another thread alone would be worse: on a
     * reconnect the pending stop can overtake the following start and kill the
     * monitor that just came up. One ordered queue removes both problems.</p>
     *
     * <p>Daemon and never shut down on purpose: it owns no resource beyond its
     * thread, the JVM does not wait for it, and {@code ServiceLocator.shutdown}
     * stops the monitor synchronously on the way out.</p>
     */
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(
            DaemonThreads.factory("traffic-display-lifecycle"));

    /**
     * Creates the binder over the hero card's traffic readouts.
     * {@code trafficMonitor} may be null when the service is unavailable;
     * {@link #onConnectionStateChanged} is then a no-op and
     * {@link #bindLabels()} must not be called, mirroring the original
     * controller guards.
     *
     * @param trafficMonitor the monitor to mirror, or null when unavailable
     * @param upload the upload chevron and speed readout
     * @param download the download chevron and speed readout
     * @param sessionTotalLabel the combined "N this session" line
     * @param trafficSummary the container shown only while a tunnel is up
     */
    public TrafficDisplayBinder(TrafficMonitor trafficMonitor,
                                Readout upload, Readout download,
                                Label sessionTotalLabel, Node trafficSummary) {
        this.trafficMonitor = trafficMonitor;
        this.upload = upload;
        this.download = download;
        this.sessionTotalLabel = sessionTotalLabel;
        this.trafficSummary = trafficSummary;
    }

    /**
     * Test seam: waits until the queued lifecycle work has run.
     *
     * <p>The queue is single-threaded and FIFO, so an empty task completing
     * means everything submitted before it has finished.</p>
     *
     * @return false if it did not settle within the timeout
     */
    boolean awaitIdle(long millis) {
        try {
            lifecycle.submit(() -> { }).get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    /**
     * Registers the listeners that mirror the monitor's speed/total
     * properties into the labels. Call once, and only when a
     * {@link TrafficMonitor} is available.
     */
    public void bindLabels() {
        trafficMonitor.uploadSpeedProperty().addListener((obs, oldVal, newVal) ->
                paint(upload, newVal.longValue(), "speed-value-up", "stats-icon-upload"));

        trafficMonitor.downloadSpeedProperty().addListener((obs, oldVal, newVal) ->
                paint(download, newVal.longValue(), "speed-value-down", "stats-icon-download"));

        trafficMonitor.totalUploadProperty().addListener((obs, oldVal, newVal) -> {
            lastTotalUpload = newVal.longValue();
            renderSessionTotal();
        });

        trafficMonitor.totalDownloadProperty().addListener((obs, oldVal, newVal) -> {
            lastTotalDownload = newVal.longValue();
            renderSessionTotal();
        });

        I18n.localeProperty().addListener((obs, oldVal, newVal) -> renderSessionTotal());
    }

    /**
     * Writes one direction's speed and mutes the pair while it is idle.
     *
     * <p>The colour is the liveness cue the retired chart used to provide: a
     * direction moving bytes carries its own accent, one sitting at zero drops
     * to muted text, so a glance at the card says which way data is going
     * without reading a single digit. Style classes are replaced rather than
     * added, which keeps every rule a single-class selector and out of
     * JavaFX's specificity ordering.</p>
     */
    private static void paint(Readout readout, long bytesPerSec,
                              String activeSpeedClass, String activeIconClass) {
        if (readout == null) {
            return;
        }
        boolean idle = bytesPerSec == 0;
        readout.speed().setText(TrafficMonitor.formatSpeed(bytesPerSec));
        readout.speed().getStyleClass().setAll("speed-value",
                idle ? "speed-value-idle" : activeSpeedClass);
        if (readout.icon() != null) {
            readout.icon().getStyleClass().setAll(idle ? "stats-icon-idle" : activeIconClass);
        }
    }

    /**
     * Renders the one-line session total, with the per-direction split behind
     * a tooltip. The card shows a single number because the row it sits in has
     * the status text beside it; the split is a hover away rather than gone.
     */
    private void renderSessionTotal() {
        if (sessionTotalLabel == null) {
            return;
        }
        long total = lastTotalUpload + lastTotalDownload;
        sessionTotalLabel.setText(
                I18n.get("dashboard.traffic.session", TrafficMonitor.formatBytes(total)));
        sessionTotalLabel.setTooltip(new Tooltip(I18n.get("dashboard.traffic.session.split",
                TrafficMonitor.formatBytes(lastTotalUpload),
                TrafficMonitor.formatBytes(lastTotalDownload))));
    }

    /**
     * Starts the monitor when the tunnel comes up and stops it when the tunnel
     * goes down or errors out, showing and hiding the readouts with it.
     *
     * @param state the connection state just entered
     */
    public void onConnectionStateChanged(ConnectionState state) {
        if (trafficMonitor == null) {
            return;
        }
        if (state == ConnectionState.CONNECTED) {
            int port;
            String secret;
            try {
                // Read on the FX thread: AppSettings is the shared instance the
                // UI mutates, and the queue below runs elsewhere.
                AppSettings settings = ServiceLocator.get(AppSettings.class);
                port = settings.getClashApiPort();
                secret = settings.getClashApiSecret();
            } catch (IllegalArgumentException e) {
                log.warn("Could not get AppSettings for TrafficMonitor");
                return;
            }
            lifecycle.execute(() -> trafficMonitor.start(port, secret));
            setSummaryVisible(true);
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
            lifecycle.execute(trafficMonitor::stop);
            setSummaryVisible(false);
            resetReadouts();
        }
    }

    private void setSummaryVisible(boolean visible) {
        if (trafficSummary != null) {
            trafficSummary.setVisible(visible);
            trafficSummary.setManaged(visible);
        }
    }

    /**
     * Zeroes the readouts on the way down so the next session does not open on
     * the previous one's numbers for the second before its first sample lands.
     */
    private void resetReadouts() {
        paint(upload, 0, "speed-value-up", "stats-icon-upload");
        paint(download, 0, "speed-value-down", "stats-icon-download");
        lastTotalUpload = 0;
        lastTotalDownload = 0;
        renderSessionTotal();
    }
}
