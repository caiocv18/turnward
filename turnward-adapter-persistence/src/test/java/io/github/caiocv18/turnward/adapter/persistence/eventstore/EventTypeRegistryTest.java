package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventTypeRegistryTest {

    @Test
    void namesEveryEventOfTheSealedHierarchy() {
        List<Class<?>> named = List.copyOf(EventTypeRegistry.all().values());

        assertThat(named).containsExactlyInAnyOrder(TurnEvent.class.getPermittedSubclasses());
    }

    @Test
    void namesAreUniqueAndRoundTrip() {
        EventTypeRegistry.all().forEach((name, type) ->
                assertThat(EventTypeRegistry.classOf(name)).isEqualTo(type));
        assertThat(SampleEvents.oneOfEach())
                .extracting(EventTypeRegistry::nameOf)
                .doesNotHaveDuplicates()
                .hasSize(TurnEvent.class.getPermittedSubclasses().length);
    }

    @Test
    void rejectsANameItDoesNotKnow() {
        assertThatThrownBy(() -> EventTypeRegistry.classOf("TurnExploded"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TurnExploded");
    }
}
