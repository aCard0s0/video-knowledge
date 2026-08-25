package com.tradinglabs.common.logging.throttle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logback {@link TurboFilter} that silences a noisy logger and emits one summary line
 * per configurable interval.
 *
 * <p>Typical use: the OpenTelemetry HTTP exporter spams a multi-line {@code UnknownHostException}
 * stack every few seconds when the OTel collector / lgtm sidecar isn't running. That
 * floods the console and {@code platform.log}. This filter drops every matching event,
 * counts how many were dropped, and once per {@code intervalMinutes} emits a single
 * compact {@code WARN} on a dedicated summary logger:
 *
 * <pre>{@code
 *   <turboFilter class="com.tradinglabs.common.logging.throttle.RateLimitedLoggerFilter">
 *       <loggerPrefix>io.opentelemetry.exporter</loggerPrefix>
 *       <intervalMinutes>30</intervalMinutes>
 *       <summaryLoggerName>tradinglabs.throttled.otel-exporter</summaryLoggerName>
 *       <summaryMessage>OTel exporter unreachable; suppressed {} events in the last {} min.</summaryMessage>
 *   </turboFilter>
 * }</pre>
 *
 * <p>The summary message takes two SLF4J placeholders ({@code {}}) — the suppressed count
 * and the interval in minutes, in that order.
 *
 * <p><b>Re-entry safety</b>: the summary logger name must not start with {@code loggerPrefix}
 * (otherwise the summary call itself would be silenced or recurse). Defaults are safe.
 */
public class RateLimitedLoggerFilter extends TurboFilter {

    private String loggerPrefix = "io.opentelemetry.exporter";
    private long intervalMinutes = 30;
    private String summaryLoggerName = "tradinglabs.throttled.otel-exporter";
    private String summaryMessage =
            "OTel exporter unreachable; suppressed {} log events in the last {} min "
            + "(likely 'lgtm' / OTel collector sidecar not running).";

    private final AtomicLong lastEmitMs = new AtomicLong(0);
    private final AtomicLong suppressedCount = new AtomicLong(0);

    private org.slf4j.Logger summaryLoggerRef;

    @Override
    public void start() {
        if (loggerPrefix == null || loggerPrefix.isBlank()) {
            addError("loggerPrefix must be set");
            return;
        }
        if (intervalMinutes <= 0) {
            addError("intervalMinutes must be > 0");
            return;
        }
        if (summaryLoggerName == null || summaryLoggerName.isBlank()) {
            addError("summaryLoggerName must be set");
            return;
        }
        if (summaryLoggerName.startsWith(loggerPrefix)) {
            addError("summaryLoggerName must NOT start with loggerPrefix (would self-recurse)");
            return;
        }
        this.summaryLoggerRef = LoggerFactory.getLogger(summaryLoggerName);
        super.start();
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (!isStarted() || logger == null) {
            return FilterReply.NEUTRAL;
        }
        String name = logger.getName();
        if (name == null || !name.startsWith(loggerPrefix)) {
            return FilterReply.NEUTRAL;
        }

        long intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes);
        long now = System.currentTimeMillis();
        long last = lastEmitMs.get();
        // Count the suppression first so the summary line includes itself.
        suppressedCount.incrementAndGet();

        if (now - last >= intervalMs && lastEmitMs.compareAndSet(last, now)) {
            long count = suppressedCount.getAndSet(0);
            try {
                summaryLoggerRef.warn(summaryMessage, count, intervalMinutes);
            } catch (Throwable ignored) {
                // never let the throttle filter throw — it would re-enter the log pipeline
            }
        }
        return FilterReply.DENY;
    }

    // --- XML setters ---

    public void setLoggerPrefix(String loggerPrefix) {
        this.loggerPrefix = loggerPrefix;
    }

    public void setIntervalMinutes(long intervalMinutes) {
        this.intervalMinutes = intervalMinutes;
    }

    public void setSummaryLoggerName(String summaryLoggerName) {
        this.summaryLoggerName = summaryLoggerName;
    }

    public void setSummaryMessage(String summaryMessage) {
        this.summaryMessage = summaryMessage;
    }
}
