package com.tradinglabs.common.logging;

import ch.qos.logback.core.joran.spi.NoAutoStart;
import ch.qos.logback.core.rolling.helper.FileNamePattern;
import ch.qos.logback.core.rolling.TimeBasedFileNamingAndTriggeringPolicyBase;
import ch.qos.logback.core.rolling.helper.TimeBasedArchiveRemover;
import ch.qos.logback.core.rolling.helper.CompressionMode;

import java.io.File;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Custom Logback policy to trigger rollover on fixed-hour boundaries.
 * Default interval is 6 hours, resulting in rollovers at 00:00, 06:00, 12:00 and 18:00.
 */
@NoAutoStart
public class FixedIntervalTimeBasedFileNamingAndTriggeringPolicy<E>
        extends TimeBasedFileNamingAndTriggeringPolicyBase<E> {

    private static final int DEFAULT_INTERVAL_HOURS = 6;
    private static final int HOURS_PER_DAY = 24;

    private int intervalHours = DEFAULT_INTERVAL_HOURS;
    private FileNamePattern archiveFileNamePatternWithoutCompressionSuffix;

    @Override
    public void start() {
        super.start();
        if (!super.isErrorFree()) {
            return;
        }
        String configuredPattern = tbrp.getFileNamePattern();
        FileNamePattern archiveFileNamePattern = new FileNamePattern(configuredPattern, context);
        archiveFileNamePatternWithoutCompressionSuffix =
                new FileNamePattern(removeCompressionSuffix(configuredPattern), context);

        if (archiveFileNamePattern.hasIntegerTokenCOnverter()) {
            addError("Filename pattern [" + configuredPattern
                    + "] contains an integer token converter (%i), incompatible with this policy.");
            return;
        }
        if (intervalHours <= 0 || HOURS_PER_DAY % intervalHours != 0) {
            addError("intervalHours must be a positive divisor of 24. Provided: " + intervalHours);
            return;
        }

        archiveRemover = new TimeBasedArchiveRemover(archiveFileNamePattern, rc);
        archiveRemover.setContext(context);

        long currentTime = getCurrentTime();
        setDateInCurrentPeriod(computeCurrentPeriodStart(currentTime));
        atomicNextCheck.set(computeNextCheck(currentTime));
        started = true;
    }

    @Override
    protected long computeNextCheck(long timestamp) {
        ZonedDateTime now = Instant.ofEpochMilli(timestamp).atZone(zoneId);
        ZonedDateTime boundary = truncateToHour(now);

        int currentHour = boundary.getHour();
        int nextBoundaryHour = ((currentHour / intervalHours) + 1) * intervalHours;

        if (nextBoundaryHour >= HOURS_PER_DAY) {
            boundary = boundary.plusDays(1).withHour(0);
        } else {
            boundary = boundary.withHour(nextBoundaryHour);
        }

        return boundary.toInstant().toEpochMilli();
    }

    private long computeCurrentPeriodStart(long timestamp) {
        ZonedDateTime now = Instant.ofEpochMilli(timestamp).atZone(zoneId);
        ZonedDateTime boundary = truncateToHour(now);
        int periodStartHour = (boundary.getHour() / intervalHours) * intervalHours;
        return boundary.withHour(periodStartHour).toInstant().toEpochMilli();
    }

    private ZonedDateTime truncateToHour(ZonedDateTime dateTime) {
        return dateTime.withMinute(0).withSecond(0).withNano(0);
    }

    @Override
    public boolean isTriggeringEvent(File activeFile, E event) {
        long currentTime = getCurrentTime();
        long localNextCheck = atomicNextCheck.get();
        if (currentTime >= localNextCheck) {
            atomicNextCheck.set(computeNextCheck(currentTime));
            Instant instantOfElapsedPeriod = dateInCurrentPeriod;
            elapsedPeriodsFileName =
                    archiveFileNamePatternWithoutCompressionSuffix.convert(instantOfElapsedPeriod);
            setDateInCurrentPeriod(currentTime);
            return true;
        }
        return false;
    }

    private String removeCompressionSuffix(String fileNamePattern) {
        CompressionMode compressionMode = tbrp.getCompressionMode();
        return switch (compressionMode) {
            case GZ -> fileNamePattern.endsWith(".gz")
                    ? fileNamePattern.substring(0, fileNamePattern.length() - 3)
                    : fileNamePattern;
            case ZIP -> fileNamePattern.endsWith(".zip")
                    ? fileNamePattern.substring(0, fileNamePattern.length() - 4)
                    : fileNamePattern;
            default -> fileNamePattern;
        };
    }

    public int getIntervalHours() {
        return intervalHours;
    }

    public void setIntervalHours(int intervalHours) {
        this.intervalHours = intervalHours;
    }

    @Override
    public String toString() {
        return "com.tradinglabs.common.logging.FixedIntervalTimeBasedFileNamingAndTriggeringPolicy";
    }
}
