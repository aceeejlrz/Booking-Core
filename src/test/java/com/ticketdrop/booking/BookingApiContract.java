package com.ticketdrop.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticketdrop.support.ApiClient;
import com.ticketdrop.support.ApiResponse;
import com.ticketdrop.support.IntegrationTest;
import com.ticketdrop.support.SeatRow;
import com.ticketdrop.support.TestData;

/**
 * The behaviour every booking strategy must have, one request at a time, written from sections 6
 * and 7 of the design spec. A strategy is tested by subclassing this class and selecting the
 * strategy with a property. Nothing here involves concurrency: that is what the race tests are for.
 */
@IntegrationTest
abstract class BookingApiContract {
    @Autowired protected ApiClient api;
    @Autowired protected TestData data;

    // ---- hold ----
    @Test void holdReturns201WithTheHold() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event); UUID b = buyer();
        ApiResponse r = api.hold(event, b, List.of(s.get(0), s.get(1)));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.uuid("holdId")).isNotNull();
        assertThat(r.longValue("eventId")).isEqualTo(event);
        assertThat(r.uuid("buyerId")).isEqualTo(b);
        assertThat(r.longs("seatIds")).containsExactly(s.get(0), s.get(1));
        assertThat(r.instant("expiresAt"))
            .isCloseTo(data.dbNow().plus(Duration.ofMinutes(5)), within(5, ChronoUnit.SECONDS));
        assertThat(statusOf(event, s.get(0))).isEqualTo("HELD");
        assertThat(statusOf(event, s.get(1))).isEqualTo("HELD");
        assertThat(statusOf(event, s.get(2))).isEqualTo("AVAILABLE");
    }

    @Test void holdReturnsSeatIdsAscending() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(2), s.get(0)));
        assertThat(r.longs("seatIds")).containsExactly(s.get(0), s.get(2));
    }

    @Test void heldSeatIs409AndIsNamed() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        held(event, buyer(), s.get(0));
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("error")).isEqualTo("SEATS_UNAVAILABLE");
        assertThat(r.longs("unavailableSeatIds")).containsExactly(s.get(0));
    }

    @Test void multiSeatHoldIsAllOrNothing() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        held(event, buyer(), s.get(1));
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0), s.get(1), s.get(2)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.longs("unavailableSeatIds")).containsExactly(s.get(1));
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
        assertThat(statusOf(event, s.get(2))).isEqualTo("AVAILABLE");
    }

    @Test void aBuyerCannotReholdTheirOwnSeat() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event); UUID b = buyer();
        held(event, b, s.get(0));
        ApiResponse r = api.hold(event, b, List.of(s.get(0)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.longs("unavailableSeatIds")).containsExactly(s.get(0));
    }

    @Test void unknownSeatIs404AndNothingIsHeld() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0), 999_999_999L));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("error")).isEqualTo("SEAT_NOT_FOUND");
        assertThat(r.longs("unknownSeatIds")).containsExactly(999_999_999L);
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
    }

    @Test void seatFromAnotherEventIs404AndIsNotHeld() {
        long eventA = newEvent(1, 2); long eventB = newEvent(1, 2);
        long foreign = seatIds(eventB).get(0);
        ApiResponse r = api.hold(eventA, buyer(), List.of(seatIds(eventA).get(0), foreign));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.longs("unknownSeatIds")).containsExactly(foreign);
        assertThat(statusOf(eventB, foreign)).isEqualTo("AVAILABLE");
        assertThat(statusOf(eventA, seatIds(eventA).get(0))).isEqualTo("AVAILABLE");
    }

    @Test void unknownSeatWinsOverUnavailableSeat() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        held(event, buyer(), s.get(0));
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0), 999_999_999L));
        assertThat(r.status()).isEqualTo(404);
    }

    @Test void anExpiredHoldCanBeTakenOver() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID first = held(event, buyer(), s.get(0));
        data.expireHold(first);
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
        assertThat(api.hold(event, buyer(), List.of(s.get(0))).status()).isEqualTo(201);
        assertThat(api.buy(first).status()).isEqualTo(410);
    }

    // ---- release ----
    @Test void releaseFreesTheSeats() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0), s.get(1));
        assertThat(api.release(hold).status()).isEqualTo(204);
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
        assertThat(statusOf(event, s.get(1))).isEqualTo("AVAILABLE");
        assertThat(api.hold(event, buyer(), List.of(s.get(0))).status()).isEqualTo(201);
    }

    @Test void releaseIsAlways204() {
        long event = newEvent(1, 4);
        UUID hold = held(event, buyer(), seatIds(event).get(0));
        assertThat(api.release(UUID.randomUUID()).status()).isEqualTo(204);
        assertThat(api.release(hold).status()).isEqualTo(204);
        assertThat(api.release(hold).status()).isEqualTo(204);
    }

    @Test void releaseAfterASaleChangesNothing() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0));
        api.buy(hold);
        assertThat(api.release(hold).status()).isEqualTo(204);
        assertThat(statusOf(event, s.get(0))).isEqualTo("SOLD");
    }

    @Test void releasingAnExpiredHoldLeavesSeatsTakenByAnotherHoldAlone() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID first = held(event, buyer(), s.get(0), s.get(1));
        data.expireHold(first);
        UUID second = held(event, buyer(), s.get(0));
        assertThat(api.release(first).status()).isEqualTo(204);
        assertThat(statusOf(event, s.get(0))).isEqualTo("HELD");
        assertThat(statusOf(event, s.get(1))).isEqualTo("AVAILABLE");
        assertThat(api.buy(second).status()).isEqualTo(201);
    }

    // ---- buy ----
    @Test void buyReturns201WithTheOrderAndSellsTheSeats() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event); UUID b = buyer();
        UUID hold = held(event, b, s.get(0), s.get(1));
        ApiResponse r = api.buy(hold);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.uuid("orderId")).isNotNull();
        assertThat(r.longValue("eventId")).isEqualTo(event);
        assertThat(r.uuid("buyerId")).isEqualTo(b);
        assertThat(r.uuid("holdId")).isEqualTo(hold);
        assertThat(r.longs("seatIds")).containsExactly(s.get(0), s.get(1));
        assertThat(r.instant("createdAt")).isCloseTo(data.dbNow(), within(5, ChronoUnit.SECONDS));
        assertThat(statusOf(event, s.get(0))).isEqualTo("SOLD");
        assertThat(statusOf(event, s.get(1))).isEqualTo("SOLD");
        assertThat(api.summary(event).intValue("sold")).isEqualTo(2);
        assertThat(api.summary(event).intValue("orders")).isEqualTo(1);
    }

    @Test void buyingTheSameHoldAgainIs200WithTheSameOrder() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0), s.get(1));
        ApiResponse first = api.buy(hold);
        ApiResponse repeat = api.buy(hold);
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.uuid("orderId")).isEqualTo(first.uuid("orderId"));
        assertThat(repeat.longs("seatIds")).containsExactly(s.get(0), s.get(1));
        assertThat(api.summary(event).intValue("orders")).isEqualTo(1);
    }

    @Test void buyAfterExpiryIs410AndSellsNothing() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0));
        data.expireHold(hold);
        ApiResponse r = api.buy(hold);
        assertThat(r.status()).isEqualTo(410);
        assertThat(r.text("error")).isEqualTo("HOLD_NOT_ACTIVE");
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
        assertThat(api.summary(event).intValue("orders")).isEqualTo(0);
    }

    @Test void buyAfterReleaseIs410() {
        long event = newEvent(1, 4);
        UUID hold = held(event, buyer(), seatIds(event).get(0));
        api.release(hold);
        assertThat(api.buy(hold).status()).isEqualTo(410);
    }

    @Test void buyingAnUnknownHoldIs404() {
        ApiResponse r = api.buy(UUID.randomUUID());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("error")).isEqualTo("HOLD_NOT_FOUND");
    }

    @Test void aSoldSeatCannotBeHeld() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        api.buy(held(event, buyer(), s.get(0)));
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.longs("unavailableSeatIds")).containsExactly(s.get(0));
    }

    @Test void aHoldThatLostASeatIsNotBoughtInPart() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0), s.get(1), s.get(2));
        data.stealSeat(s.get(1));        // the hold is unexpired but no longer whole
        assertThat(api.buy(hold).status()).isEqualTo(410);
        assertThat(statusOf(event, s.get(0))).isEqualTo("HELD");
        assertThat(statusOf(event, s.get(2))).isEqualTo("HELD");
        assertThat(api.summary(event).intValue("sold")).isEqualTo(0);
        assertThat(api.summary(event).intValue("orders")).isEqualTo(0);
    }

    // ---- helpers ----
    protected long newEvent(int rows, int seatsPerRow) {
        ApiResponse r = api.createEvent("Contract", rows, seatsPerRow);
        assertThat(r.status()).isEqualTo(201);
        return r.longValue("id");
    }

    /** The event's seat IDs in listing order: by row, then by number. */
    protected List<Long> seatIds(long eventId) {
        return api.listSeats(eventId).seats().stream().map(SeatRow::id).toList();
    }

    /** The effective status the seat listing reports for one seat. */
    protected String statusOf(long eventId, long seatId) {
        return api.listSeats(eventId).seats().stream()
                .filter(seat -> seat.id() == seatId)
                .map(SeatRow::status)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Seat " + seatId + " is not in event " + eventId));
    }

    /** Holds the seats, insists that it worked, and returns the hold ID. */
    protected UUID held(long eventId, UUID buyer, Long... seats) {
        ApiResponse r = api.hold(eventId, buyer, List.of(seats));
        assertThat(r.status()).as("hold of seats %s answered: %s", List.of(seats), r.rawBody()).isEqualTo(201);
        return r.uuid("holdId");
    }

    protected UUID buyer() {
        return UUID.randomUUID();
    }
}
