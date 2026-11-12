package com.ticketdrop.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;

import com.ticketdrop.support.ApiClient;
import com.ticketdrop.support.ApiResponse;
import com.ticketdrop.support.FakeBookingConfig;
import com.ticketdrop.support.FakeBookingService;
import com.ticketdrop.support.IntegrationTest;

/**
 * The HTTP layer on its own: with a scripted BookingService behind it, every outcome must map to
 * the right status code and JSON. No booking logic runs here.
 */
@IntegrationTest
@Import(FakeBookingConfig.class)
class BookingEndpointsTest {
    static final UUID BUYER = UUID.fromString("6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11");
    static final UUID HOLD_ID = UUID.fromString("b3e1f0a2-58c4-4d0e-8f6a-2c9d7e4b1a30");
    static final UUID ORDER_ID = UUID.fromString("0a7d5c1e-9f42-4b8a-b1d3-6e2c4f8a9b07");
    @Autowired ApiClient api;
    @Autowired FakeBookingService fake;
    long event;

    @BeforeEach
    void freshFakeAndEvent() {
        fake.reset();
        event = api.createEvent("E", 1, 4).longValue("id");
    }

    @Test void holdIs201WithTheHoldAsJson() {
        fake.holdReturns(new Hold(HOLD_ID, event, BUYER, List.of(11L, 12L), Instant.parse("2026-11-11T18:35:00Z")));
        ApiResponse r = api.hold(event, BUYER, List.of(12L, 11L));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.uuid("holdId")).isEqualTo(HOLD_ID);
        assertThat(r.longValue("eventId")).isEqualTo(event);
        assertThat(r.uuid("buyerId")).isEqualTo(BUYER);
        assertThat(r.longs("seatIds")).containsExactly(11L, 12L);
        assertThat(r.rawBody()).contains("\"expiresAt\":\"2026-11-11T18:35:00Z\"");
        assertThat(fake.lastHoldSeatIds()).containsExactly(12L, 11L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"buyerId\":\"not-a-uuid\",\"seatIds\":[1]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\"}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[1,2,3,4,5]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[1,1]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[1,null]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[\"a\"]}",
        "not json" })
    void invalidHoldBodiesAre400(String body) {
        ApiResponse r = api.send("POST", "/api/events/" + event + "/holds", body);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(fake.calls()).isEmpty();
    }

    @Test void holdOnAnUnknownEventIs404AndNeverReachesTheService() {
        ApiResponse r = api.hold(999_999_999L, BUYER, List.of(1L));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("error")).isEqualTo("EVENT_NOT_FOUND");
        assertThat(fake.calls()).isEmpty();
    }

    @Test void invalidInputIsRejectedBeforeAnUnknownEvent() {
        ApiResponse r = api.hold(999_999_999L, BUYER, List.of(1L, 2L, 3L, 4L, 5L));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
    }

    @Test void unknownSeatsAre404WithTheirIds() {
        fake.holdThrows(new SeatsNotFoundException(List.of(7L)));
        ApiResponse r = api.hold(event, BUYER, List.of(7L));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("error")).isEqualTo("SEAT_NOT_FOUND");
        assertThat(r.longs("unknownSeatIds")).containsExactly(7L);
    }

    @Test void unavailableSeatsAre409WithTheirIds() {
        fake.holdThrows(new SeatsUnavailableException(List.of(3L, 4L)));
        ApiResponse r = api.hold(event, BUYER, List.of(3L, 4L));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("error")).isEqualTo("SEATS_UNAVAILABLE");
        assertThat(r.longs("unavailableSeatIds")).containsExactly(3L, 4L);
    }

    @Test void releaseIs204AndPassesTheHoldId() {
        assertThat(api.release(HOLD_ID).status()).isEqualTo(204);
        assertThat(fake.lastReleasedHoldId()).isEqualTo(HOLD_ID);
    }

    @Test void releaseWithANonUuidIs400() {
        ApiResponse r = api.send("DELETE", "/api/holds/not-a-uuid", null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(fake.calls()).isEmpty();
    }

    @Test void firstBuyIs201AndARepeatIs200WithTheSameBody() {
        Order order = new Order(ORDER_ID, event, BUYER, HOLD_ID, List.of(11L, 12L), Instant.parse("2026-11-11T18:31:12Z"));
        fake.buyReturns(new PurchaseResult(order, true));
        ApiResponse first = api.buy(HOLD_ID);
        fake.buyReturns(new PurchaseResult(order, false));
        ApiResponse repeat = api.buy(HOLD_ID);
        assertThat(first.status()).isEqualTo(201);
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.rawBody()).isEqualTo(first.rawBody());
        assertThat(first.uuid("orderId")).isEqualTo(ORDER_ID);
        assertThat(first.uuid("holdId")).isEqualTo(HOLD_ID);
        assertThat(first.uuid("buyerId")).isEqualTo(BUYER);
        assertThat(first.longValue("eventId")).isEqualTo(event);
        assertThat(first.longs("seatIds")).containsExactly(11L, 12L);
        assertThat(first.rawBody()).contains("\"createdAt\":\"2026-11-11T18:31:12Z\"");
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"holdId\":\"nope\"}", "not json" })
    void invalidBuyBodiesAre400(String body) {
        ApiResponse r = api.send("POST", "/api/orders", body);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(fake.calls()).isEmpty();
    }

    @Test void buyFailuresMapToTheirStatuses() {
        fake.buyThrows(new HoldNotFoundException(HOLD_ID));
        assertThat(api.buy(HOLD_ID).status()).isEqualTo(404);
        assertThat(api.buy(HOLD_ID).text("error")).isEqualTo("HOLD_NOT_FOUND");
        fake.buyThrows(new HoldNotActiveException(HOLD_ID));
        assertThat(api.buy(HOLD_ID).status()).isEqualTo(410);
        assertThat(api.buy(HOLD_ID).text("error")).isEqualTo("HOLD_NOT_ACTIVE");
        fake.buyThrows(new CannotAcquireLockException("deadlock detected"));
        assertThat(api.buy(HOLD_ID).status()).isEqualTo(503);
        assertThat(api.buy(HOLD_ID).text("error")).isEqualTo("TRY_AGAIN");
    }

    @Test void unexpectedFailureIs500AndLeaksNothing() {
        fake.buyThrows(new IllegalStateException("secret detail"));
        ApiResponse r = api.buy(HOLD_ID);
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.text("error")).isEqualTo("INTERNAL_ERROR");
        assertThat(r.rawBody()).doesNotContain("secret detail");
    }
}
