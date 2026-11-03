package io.github.caiocv18.turnward.application.port;

import io.github.caiocv18.turnward.application.tool.ToolFailedException;
import io.github.caiocv18.turnward.application.tool.ToolInvocation;

/**
 * Runs a tool once. A tool with side effects must honour {@link ToolInvocation#idempotencyKey()}, because a read-only
 * call may be repeated after a crash and an approved one must not take effect twice.
 */
public interface ToolPort {

    /**
     * @return what the tool returned, for the model to read
     * @throws ToolFailedException when the tool certainly did not take effect
     * @throws RuntimeException when the outcome is not known, such as a timeout; a call with side effects then goes
     *     to human reconciliation instead of being reported to the model as failed
     */
    String execute(ToolInvocation invocation);
}
