package io.github.caiocv18.turnward.application.eventstore;

import io.github.caiocv18.turnward.domain.TurnId;
import java.util.Objects;

/**
 * The stream was not at the version the caller expected, so nothing was appended. The caller reloads, folds and
 * decides again.
 */
public final class ConcurrencyConflictException extends RuntimeException {

    private final TurnId streamId;
    private final long expectedVersion;

    public ConcurrencyConflictException(TurnId streamId, long expectedVersion, String detail) {
        super("stream %s is not at version %d: %s".formatted(
                Objects.requireNonNull(streamId, "streamId"), expectedVersion, Objects.requireNonNull(detail, "detail")));
        this.streamId = streamId;
        this.expectedVersion = expectedVersion;
    }

    public TurnId streamId() {
        return streamId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
