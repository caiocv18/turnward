package io.github.caiocv18.turnward;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.caiocv18.turnward.application.port.EventStore;
import io.github.caiocv18.turnward.domain.TurnId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("worker")
@Import(PostgresTestConfiguration.class)
class WorkerProfileTest {

    @Autowired
    private Environment environment;

    @Autowired
    private EventStore eventStore;

    @Test
    void startsWithTheWorkerRoleAndNoWebServer() {
        assertThat(environment.getProperty("turnward.role")).isEqualTo("worker");
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
    }

    @Test
    void wiresTheEventStoreOnTheMigratedDatabase() {
        assertThat(eventStore.load(TurnId.of("00000000-0000-7000-8000-000000000000"))).isEmpty();
    }
}
