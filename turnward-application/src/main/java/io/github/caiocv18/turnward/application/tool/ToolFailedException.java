package io.github.caiocv18.turnward.application.tool;

/** The tool certainly did not take effect, so the failure can be told to the model. Any other exception is unknown. */
public final class ToolFailedException extends RuntimeException {

    public ToolFailedException(String message) {
        super(message);
    }
}
