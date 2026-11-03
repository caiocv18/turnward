package io.github.caiocv18.turnward.application.tool;

import java.util.Map;
import java.util.Objects;

/**
 * One run of a tool.
 *
 * @param idempotencyKey {@code streamId:toolCallId}, the same on every attempt of the same call
 */
public record ToolInvocation(String tool, Map<String, String> args, String idempotencyKey) {

    public ToolInvocation {
        Objects.requireNonNull(tool, "tool");
        args = Map.copyOf(Objects.requireNonNull(args, "args"));
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
