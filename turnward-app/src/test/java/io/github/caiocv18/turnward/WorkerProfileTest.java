package io.github.caiocv18.turnward;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("worker")
class WorkerProfileTest {

    @Autowired
    private Environment environment;

    @Test
    void startsWithTheWorkerRoleAndNoWebServer() {
        assertThat(environment.getProperty("turnward.role")).isEqualTo("worker");
        assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
    }
}
