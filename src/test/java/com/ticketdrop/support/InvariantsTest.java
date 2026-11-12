package com.ticketdrop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class InvariantsTest {
    @Autowired ApiClient api; @Autowired TestData data; @Autowired Invariants invariants; @Autowired JdbcClient jdbc;

    @Test void aCleanEventHasNoViolationsAndCountsItsSales() {
        long event = api.createEvent("E", 1, 4).longValue("id");
        List<SeatRow> s = api.listSeats(event).seats();
        data.markSold(s.get(0).id());
        data.markSold(s.get(1).id());
        invariants.assertNone(event);
        assertThat(invariants.soldSeats(event)).isEqualTo(2);
        assertThat(invariants.orders(event)).isEqualTo(2);
    }

    @Test void aBrokenEventIsReported() {
        long event = api.createEvent("E", 1, 4).longValue("id");
        data.markSold(api.listSeats(event).seats().get(0).id());
        jdbc.sql("update holds set seat_count = 2 where event_id = :e").param("e", event).update();
        assertThat(invariants.violations(event)).hasSize(1).first().asString().startsWith("V2: ");
        assertThatThrownBy(() -> invariants.assertNone(event)).isInstanceOf(AssertionError.class);
    }

    @Test void expireHoldInMovesTheExpiry() {
        long event = api.createEvent("E", 1, 1).longValue("id");
        long seat = api.listSeats(event).seats().get(0).id();
        data.markHeld(seat, Duration.ofMinutes(5));
        UUID hold = jdbc.sql("select hold_id from seats where id = :s").param("s", seat).query(UUID.class).single();
        data.expireHoldIn(hold, Duration.ofMillis(-1));
        assertThat(api.listSeats(event).seats().get(0).status()).isEqualTo("AVAILABLE");
    }
}
