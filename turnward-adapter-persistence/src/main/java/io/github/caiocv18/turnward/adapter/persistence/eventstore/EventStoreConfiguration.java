package io.github.caiocv18.turnward.adapter.persistence.eventstore;

import io.github.caiocv18.turnward.application.port.EventStore;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/** Wires the event store on the auto-configured {@code JdbcClient} and transaction template. */
@Configuration(proxyBeanMethods = false)
public class EventStoreConfiguration {

    @Bean
    EventStore eventStore(JdbcClient jdbc, TransactionOperations transactions, ObjectProvider<Clock> clock) {
        return new JdbcEventStore(jdbc, transactions, clock.getIfAvailable(Clock::systemUTC));
    }
}
