package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.caiocv18.turnward.domain.tool.RiskLevel;
import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

class TurnEventSerializerTest {

    private final TurnEventSerializer serializer = new TurnEventSerializer();

    @Test
    void everyEventSurvivesTheRoundTrip() {
        for (TurnEvent event : SampleEvents.oneOfEach()) {
            String payload = serializer.payloadOf(event);

            assertThat(serializer.read(serializer.typeOf(event), payload)).isEqualTo(event);
        }
    }

    @Test
    void payloadCarriesNoJavaName() {
        for (TurnEvent event : SampleEvents.oneOfEach()) {
            assertThat(serializer.payloadOf(event)).doesNotContain("io.github").doesNotContain("@class");
        }
    }

    @Test
    void payloadIsTheRecordComponents() {
        assertThat(serializer.payloadOf(new TurnEvent.ApprovalGranted("tool-1", "approver-a")))
                .isEqualTo("{\"toolCallId\":\"tool-1\",\"by\":\"approver-a\"}");
    }

    @Test
    void writesNulAsTheReplacementCharacterInValuesAndKeys() {
        TurnEvent withNul = new TurnEvent.ToolCallProposed("tool-1", "run", Map.of("k\u0000ey", "std\u0000err"),
                RiskLevel.READ_ONLY);

        String payload = serializer.payloadOf(withNul);

        assertThat(payload).doesNotContain("\\u0000");
        assertThat(serializer.read("ToolCallProposed", payload)).isEqualTo(new TurnEvent.ToolCallProposed(
                "tool-1", "run", Map.of("k�ey", "std�err"), RiskLevel.READ_ONLY));
    }

    @Test
    void rejectsAPayloadWithAFieldTheRecordDoesNotHave() {
        assertThatThrownBy(() -> serializer.read("ApprovalExpired", "{\"toolCallId\":\"t\",\"extra\":1}"))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void domainValidationStillRunsOnRead() {
        assertThatThrownBy(() -> serializer.read("ApprovalExpired", "{}")).isInstanceOf(RuntimeException.class);
    }
}
