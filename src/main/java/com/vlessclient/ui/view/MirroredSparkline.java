package com.vlessclient.ui.view;

import java.util.ArrayDeque;
import java.util.Deque;
import javafx.animation.AnimationTimer;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * A two-series mirrored sparkline for the Dashboard's real-time traffic
 * readout: upload samples plot upward from a shared horizontal axis and
 * download samples plot downward, so the direction a curve travels matches
 * the word naming it. (nload and many router pages mirror a duplex link the
 * other way round; the plain reading of "up" won here.) Each half auto-scales
 * to its own rolling maximum so a busy downlink cannot flatten the (typically
 * much smaller) uplink.
 *
 * <p>Rendering follows the retired single-series {@code Sparkline}: a rolling
 * buffer of up to {@link #maxSamples} sample pairs drawn on a {@link Canvas}
 * in one {@code redraw} pass — Catmull-Rom splines, a soft gradient fill
 * fading toward the axis, and a gently pulsing dot pinned to each series'
 * most recent value. The curve only changes when a sample arrives (1 Hz); the
 * endpoint pulse is driven by an {@link AnimationTimer} at ~35 fps that idles
 * whenever the chart is hidden or empty.</p>
 */
public class MirroredSparkline extends Region {

    private static final int DEFAULT_MAX_SAMPLES = 60;
    private static final double MIN_DISPLAY_RANGE = 1.0;
    private static final long PULSE_PERIOD_NS = 1_600_000_000L;
    private static final long FRAME_MIN_NS = 28_000_000L;
    private static final Color AXIS_COLOR = Color.gray(0.5, 0.35);
    /**
     * Outer strip, in px, kept clear on each half so a plotted curve can never
     * rise into the legend and speed-readout overlays pinned to the card's top
     * and bottom corners. Because each half scales to its own rolling max, the
     * peak sample lands exactly on this band's inner edge — so no sample, at any
     * horizontal position, reaches the text. Sized to clear the tallest overlay
     * (the speed value) plus a little slack for Catmull-Rom overshoot near a
     * spike. Raise the card's height if you want a livelier curve with the band.
     */
    private static final double LABEL_BAND = 30.0;
    /** Floor for the plotting radius when the card is too short for the band. */
    private static final double MIN_HALF_HEIGHT = 8.0;

    private final Canvas canvas = new Canvas();
    /** Rolling buffer of {download, upload} pairs, oldest first. */
    private final Deque<double[]> samples = new ArrayDeque<>();
    private final int maxSamples;

    private final ObjectProperty<Color> downLineColor =
            new SimpleObjectProperty<>(Color.web("#1565c0"));
    private final ObjectProperty<Color> downFillColor =
            new SimpleObjectProperty<>(Color.web("#1565c0", 0.18));
    private final ObjectProperty<Color> upLineColor =
            new SimpleObjectProperty<>(Color.web("#ef6c00"));
    private final ObjectProperty<Color> upFillColor =
            new SimpleObjectProperty<>(Color.web("#ef6c00", 0.18));

    private double pulse;
    private boolean pulseRunning;
    private final AnimationTimer pulseTimer = new AnimationTimer() {
        private long lastDraw;

        @Override
        public void handle(long now) {
            if (now - lastDraw < FRAME_MIN_NS) {
                return;
            }
            lastDraw = now;
            if (!canAnimate()) {
                return;
            }
            pulse = (Math.sin(now / (double) PULSE_PERIOD_NS * 2 * Math.PI) + 1) / 2;
            redraw();
        }
    };

    public MirroredSparkline() {
        this(DEFAULT_MAX_SAMPLES);
    }

    /**
     * Creates a mirrored sparkline with the given rolling-buffer capacity.
     *
     * @param maxSamples maximum number of sample pairs retained; clamped to a
     *     minimum of 8
     */
    public MirroredSparkline(int maxSamples) {
        this.maxSamples = Math.max(8, maxSamples);
        getChildren().add(canvas);
        // Keep the min above ~80px so axisY - pad - LABEL_BAND stays positive and
        // the text band survives even when a short window compresses the card.
        setMinHeight(84);
        setPrefHeight(150);
        downLineColor.addListener((obs, o, n) -> redraw());
        downFillColor.addListener((obs, o, n) -> redraw());
        upLineColor.addListener((obs, o, n) -> redraw());
        upFillColor.addListener((obs, o, n) -> redraw());
        widthProperty().addListener((obs, o, n) -> {
            canvas.setWidth(n.doubleValue());
            redraw();
        });
        heightProperty().addListener((obs, o, n) -> {
            canvas.setHeight(n.doubleValue());
            redraw();
        });
        // Pause the pulse when detached from a scene; resume if we still hold data.
        sceneProperty().addListener((obs, o, n) -> {
            if (n == null) {
                stopPulse();
            } else if (!samples.isEmpty()) {
                startPulse();
            }
        });
    }

    /**
     * Appends one download/upload sample pair and redraws. Non-finite or
     * negative values clamp to 0.
     */
    public void addSample(double download, double upload) {
        samples.addLast(new double[] {clean(download), clean(upload)});
        while (samples.size() > maxSamples) {
            samples.removeFirst();
        }
        startPulse();
        redraw();
    }

    /** Clears both series and redraws the empty axis. */
    public void clear() {
        samples.clear();
        stopPulse();
        pulse = 0;
        redraw();
    }

    public ObjectProperty<Color> downLineColorProperty() {
        return downLineColor;
    }

    public void setDownLineColor(Color c) {
        downLineColor.set(c);
    }

    public ObjectProperty<Color> downFillColorProperty() {
        return downFillColor;
    }

    public void setDownFillColor(Color c) {
        downFillColor.set(c);
    }

    public ObjectProperty<Color> upLineColorProperty() {
        return upLineColor;
    }

    public void setUpLineColor(Color c) {
        upLineColor.set(c);
    }

    public ObjectProperty<Color> upFillColorProperty() {
        return upFillColor;
    }

    public void setUpFillColor(Color c) {
        upFillColor.set(c);
    }

    private static double clean(double value) {
        return Double.isFinite(value) && value > 0 ? value : 0.0;
    }

    private void startPulse() {
        if (!pulseRunning) {
            pulseRunning = true;
            pulseTimer.start();
        }
    }

    private void stopPulse() {
        if (pulseRunning) {
            pulseRunning = false;
            pulseTimer.stop();
        }
    }

    /** True only when visible in a showing window with lines to draw. */
    private boolean canAnimate() {
        Scene s = getScene();
        return isVisible() && getWidth() > 0 && samples.size() >= 2
                && s != null && s.getWindow() != null && s.getWindow().isShowing();
    }

    private void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        if (w <= 0 || h <= 0) {
            return;
        }

        double pad = 2;
        double axisY = Math.round(h / 2) + 0.5;

        // The shared axis is part of the empty state too: it shows the card's
        // structure before the first samples arrive.
        g.setStroke(AXIS_COLOR);
        g.setLineWidth(1);
        g.setLineDashes(4, 6);
        g.strokeLine(pad, axisY, w - pad, axisY);
        g.setLineDashes((double[]) null);

        if (samples.size() < 2) {
            return;
        }

        // Each half scales to its own max, padded by a hair so the peak
        // doesn't kiss the edge.
        double maxDown = MIN_DISPLAY_RANGE;
        double maxUp = MIN_DISPLAY_RANGE;
        for (double[] s : samples) {
            if (s[0] > maxDown) {
                maxDown = s[0];
            }
            if (s[1] > maxUp) {
                maxUp = s[1];
            }
        }
        maxDown *= 1.1;
        maxUp *= 1.1;

        double chartW = w - pad * 2;
        // Plot into a reduced radius so the rolling max lands on the reserved
        // band's inner edge instead of the card edge, keeping the curve out of
        // the corner overlays. Only a card too short to hold the band falls back
        // to the full half-height.
        double halfH = axisY - pad - LABEL_BAND;
        if (halfH < MIN_HALF_HEIGHT) {
            halfH = axisY - pad;
        }
        int count = samples.size();
        double stepX = chartW / (maxSamples - 1);
        // Right-align the most recent sample so older points scroll off the
        // left edge instead of squishing.
        double startX = pad + chartW - stepX * (count - 1);

        double[] xs = new double[count];
        double[] downYs = new double[count];
        double[] upYs = new double[count];
        int i = 0;
        for (double[] s : samples) {
            xs[i] = startX + stepX * i;
            upYs[i] = axisY - (s[1] / maxUp) * halfH;
            downYs[i] = axisY + (s[0] / maxDown) * halfH;
            i++;
        }

        drawHalf(g, xs, upYs, count, axisY, axisY - halfH,
                upLineColor.get(), upFillColor.get());
        drawHalf(g, xs, downYs, count, axisY, axisY + halfH,
                downLineColor.get(), downFillColor.get());
    }

    /**
     * Draws one series: a gradient fill between the spline and the axis
     * (strongest at the outer edge {@code farY}, fading to almost nothing at
     * the axis), the spline stroke, and the pulsing endpoint dot.
     */
    private void drawHalf(GraphicsContext g, double[] xs, double[] ys, int count,
                          double axisY, double farY, Color line, Color fill) {
        g.beginPath();
        traceSpline(g, xs, ys, count);
        g.lineTo(xs[count - 1], axisY);
        g.lineTo(xs[0], axisY);
        g.closePath();
        LinearGradient gradient = new LinearGradient(
                0, farY, 0, axisY, false, CycleMethod.NO_CYCLE,
                new Stop(0, fill),
                new Stop(1, fill.deriveColor(0, 1, 1, 0.05))
        );
        g.setFill(gradient);
        g.fill();

        g.beginPath();
        traceSpline(g, xs, ys, count);
        g.setStroke(line);
        g.setLineWidth(2.0);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.stroke();

        drawEndpoint(g, xs[count - 1], ys[count - 1], line);
    }

    /**
     * Live endpoint marker: a solid core with a halo that pulses — the radius
     * grows as the alpha fades. The pulse phase is advanced by pulseTimer while
     * on screen and simply holds its last value otherwise.
     */
    private void drawEndpoint(GraphicsContext g, double ex, double ey, Color line) {
        double haloR = 4.0 + pulse * 4.0;
        double haloAlpha = 0.45 * (1 - pulse) + 0.10;
        RadialGradient halo = new RadialGradient(
                0, 0, ex, ey, haloR, false, CycleMethod.NO_CYCLE,
                new Stop(0, alpha(line, haloAlpha)),
                new Stop(1, alpha(line, 0))
        );
        g.setFill(halo);
        g.fillOval(ex - haloR, ey - haloR, haloR * 2, haloR * 2);
        g.setFill(line);
        g.fillOval(ex - 2.6, ey - 2.6, 5.2, 5.2);
    }

    /** Moves to the first point and traces Catmull-Rom cubic segments through the rest. */
    private void traceSpline(GraphicsContext g, double[] xs, double[] ys, int count) {
        g.moveTo(xs[0], ys[0]);
        for (int i = 0; i < count - 1; i++) {
            int i0 = i > 0 ? i - 1 : i;
            int i3 = i + 2 < count ? i + 2 : i + 1;
            double c1x = xs[i] + (xs[i + 1] - xs[i0]) / 6.0;
            double c1y = ys[i] + (ys[i + 1] - ys[i0]) / 6.0;
            double c2x = xs[i + 1] - (xs[i3] - xs[i]) / 6.0;
            double c2y = ys[i + 1] - (ys[i3] - ys[i]) / 6.0;
            g.bezierCurveTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1]);
        }
    }

    private static Color alpha(Color base, double a) {
        double clamped = Math.max(0, Math.min(1, a));
        return Color.color(base.getRed(), base.getGreen(), base.getBlue(), clamped);
    }
}
