package com.ticketdrop.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Import this to put a {@link FakeBookingService} in front of the real booking strategy. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeBookingConfig {

    @Bean
    @Primary
    FakeBookingService fakeBookingService() {
        return new FakeBookingService();
    }
}
