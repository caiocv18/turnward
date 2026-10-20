package io.github.caiocv18.turnward.adapter.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

/**
 * Smoke test for resilience4j-spring-boot4 2.4.0 on Boot 4.1: the auto-configuration starts,
 * reads the instance from application.yml and the annotation aspect opens the breaker.
 */
@SpringBootTest
class CircuitBreakerSmokeTest {

    private static final String INSTANCE = "smoke";

    // Matches minimumNumberOfCalls in src/test/resources/application.yml.
    private static final int CALLS_TO_OPEN = 4;

    @Autowired
    private CircuitBreakerRegistry registry;

    @Autowired
    private FlakyLlmClient client;

    @Test
    void registersTheConfiguredInstance() {
        assertThat(registry.circuitBreaker(INSTANCE).getCircuitBreakerConfig().getMinimumNumberOfCalls())
                .isEqualTo(CALLS_TO_OPEN);
    }

    @Test
    void opensAfterTheConfiguredFailuresAndStopsCallingTheClient() {
        assertThat(AopUtils.isAopProxy(client)).isTrue();

        for (int i = 0; i < CALLS_TO_OPEN; i++) {
            assertThatThrownBy(client::complete).isInstanceOf(IllegalStateException.class);
        }

        assertThat(registry.circuitBreaker(INSTANCE).getState()).isEqualTo(State.OPEN);
        assertThatThrownBy(client::complete).isInstanceOf(CallNotPermittedException.class);
        assertThat(client.calls()).isEqualTo(CALLS_TO_OPEN);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class SmokeConfiguration {

        @Bean
        FlakyLlmClient flakyLlmClient() {
            return new FlakyLlmClient();
        }
    }

    /** Stands in for a chat model that is down: every call fails. */
    static class FlakyLlmClient {

        private final AtomicInteger calls = new AtomicInteger();

        @CircuitBreaker(name = INSTANCE)
        public String complete() {
            calls.incrementAndGet();
            throw new IllegalStateException("model unavailable");
        }

        // Public so the CGLIB proxy delegates it to the target, where the counter lives.
        public int calls() {
            return calls.get();
        }
    }
}
