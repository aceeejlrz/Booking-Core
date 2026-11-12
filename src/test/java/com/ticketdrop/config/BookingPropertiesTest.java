package com.ticketdrop.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class BookingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProps.class);

    @Test
    void defaults() {
        runner.run(context -> {
            BookingProperties props = context.getBean(BookingProperties.class);
            assertThat(props.holdDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.maxSeatsPerHold()).isEqualTo(4);
            assertThat(props.bookingStrategy()).isEqualTo(BookingStrategy.ATOMIC);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ticketdrop.hold-duration=0s", "ticketdrop.hold-duration=-1m",
            "ticketdrop.max-seats-per-hold=0", "ticketdrop.booking-strategy=fast" })
    void invalidSettingsStopStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BookingProperties.class)
    static class EnableProps {
    }
}
