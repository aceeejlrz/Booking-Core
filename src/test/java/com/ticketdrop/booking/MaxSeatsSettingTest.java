package com.ticketdrop.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.ticketdrop.support.ApiClient;
import com.ticketdrop.support.FakeBookingConfig;
import com.ticketdrop.support.FakeBookingService;
import com.ticketdrop.support.IntegrationTest;

/** The most seats one hold may take comes from the setting, not from a constant in the code. */
@IntegrationTest
@Import(FakeBookingConfig.class)
@TestPropertySource(properties = "ticketdrop.max-seats-per-hold=2")
class MaxSeatsSettingTest {

    static final UUID BUYER = UUID.fromString("6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11");

    @Autowired ApiClient api;
    @Autowired FakeBookingService fake;
    long event;

    @BeforeEach
    void freshFakeAndEvent() {
        fake.reset();
        event = api.createEvent("E", 1, 4).longValue("id");
    }

    @Test
    void theLimitFollowsTheSetting() {
        fake.holdReturns(new Hold(UUID.randomUUID(), event, BUYER, List.of(1L, 2L), Instant.now()));
        assertThat(api.hold(event, BUYER, List.of(1L, 2L, 3L)).status()).isEqualTo(400);
        assertThat(api.hold(event, BUYER, List.of(1L, 2L)).status()).isEqualTo(201);
    }
}
