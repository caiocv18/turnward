package io.github.caiocv18.turnward;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("api")
class ApiProfileTest {

    @Autowired
    private Environment environment;

    @Test
    void startsWithTheApiRole() {
        assertThat(environment.getProperty("turnward.role")).isEqualTo("api");
    }
}
