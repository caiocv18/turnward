package io.github.caiocv18.turnward.domain.turn;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The alert a turn was started for, as the monitoring system described it.
 *
 * @param name what fired, for example {@code HighErrorRate}
 * @param startsAt when this firing began; it stays the same across redeliveries
 */
public record AlertContext(String name, Map<String, String> labels, Map<String, String> annotations,
        Instant startsAt) {

    public AlertContext {
        Objects.requireNonNull(name, "name");
        labels = Map.copyOf(Objects.requireNonNull(labels, "labels"));
        annotations = Map.copyOf(Objects.requireNonNull(annotations, "annotations"));
        Objects.requireNonNull(startsAt, "startsAt");
    }
}
