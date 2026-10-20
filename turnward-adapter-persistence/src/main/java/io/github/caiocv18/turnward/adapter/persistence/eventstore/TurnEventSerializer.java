package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import io.github.caiocv18.turnward.domain.turn.TurnEvent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Turns an event into the {@code type} and {@code payload} columns and back. The payload holds only the record's
 * components; the class is found through {@link EventTypeRegistry}, never through a Java name in the JSON.
 *
 * <p>Postgres {@code jsonb} refuses U+0000 (SQLSTATE 22P05), and free text such as a tool's stderr or a model's
 * output can carry it. Every NUL in a string or map key is written as U+FFFD, so the event is stored, lossily,
 * instead of leaving the turn unable to record what happened.
 */
final class TurnEventSerializer {

    static final char NUL = '\u0000';
    static final char REPLACEMENT = '�';

    // Strict on read: a payload with a field the record does not have is a bug, not something to drop silently.
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .addModule(new SimpleModule("jsonb-safe-strings")
                    .addSerializer(String.class, new NulFreeValue())
                    .addKeySerializer(String.class, new NulFreeKey()))
            .build();

    String typeOf(TurnEvent event) {
        return EventTypeRegistry.nameOf(event);
    }

    String payloadOf(TurnEvent event) {
        return mapper.writeValueAsString(event);
    }

    TurnEvent read(String type, String payload) {
        return mapper.readValue(payload, EventTypeRegistry.classOf(type));
    }

    private static String withoutNul(String value) {
        return value.replace(NUL, REPLACEMENT);
    }

    private static final class NulFreeValue extends StdSerializer<String> {
        NulFreeValue() {
            super(String.class);
        }

        @Override
        public void serialize(String value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(withoutNul(value));
        }
    }

    private static final class NulFreeKey extends StdSerializer<String> {
        NulFreeKey() {
            super(String.class);
        }

        @Override
        public void serialize(String value, JsonGenerator generator, SerializationContext context) {
            generator.writeName(withoutNul(value));
        }
    }
}
