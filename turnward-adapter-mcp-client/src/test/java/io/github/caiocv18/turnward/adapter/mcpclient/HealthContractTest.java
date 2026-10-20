package io.github.caiocv18.turnward.adapter.mcpclient;

import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Smoke contract for Pact JVM 4.7.5 on the Boot-managed JUnit 6 and Groovy 5: the consumer side
 * runs and writes target/pacts/turnward-log-analyst.json. The real MCP contracts land in S3.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "log-analyst", pactVersion = PactSpecVersion.V4)
class HealthContractTest {

    @Pact(consumer = "turnward")
    V4Pact healthIsUp(PactDslWithProvider builder) {
        return builder
                .uponReceiving("a health check")
                .path("/health")
                .method("GET")
                .willRespondWith()
                .status(200)
                .body(new PactDslJsonBody().stringValue("status", "UP"))
                .toPact(V4Pact.class);
    }

    @Test
    void readsTheHealthOfTheProvider(MockServer mockServer) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(mockServer.getUrl() + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\"").contains("\"UP\"");
        }
    }
}
