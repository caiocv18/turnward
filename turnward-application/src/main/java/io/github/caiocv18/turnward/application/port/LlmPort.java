package io.github.caiocv18.turnward.application.port;

import io.github.caiocv18.turnward.application.llm.LlmRequest;
import io.github.caiocv18.turnward.application.llm.LlmResponse;

/**
 * A chat model that answers and may ask for tools. It never runs a tool: what the model asks for goes back in the
 * response, so every call passes through the decider and is recorded (ADR-0011).
 */
public interface LlmPort {

    /** The model's name, recorded in {@code LlmCallStarted} before the call is made. */
    String model();

    /**
     * Calls the model once.
     *
     * @throws RuntimeException when the call fails; the caller records it as {@code LlmCallFailed}
     */
    LlmResponse call(LlmRequest request);
}
