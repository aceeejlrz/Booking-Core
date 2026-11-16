package com.ticketdrop.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.ticketdrop.support.ApiClient;
import com.ticketdrop.support.ApiResponse;
import com.ticketdrop.support.ConcurrentCalls;
import com.ticketdrop.support.IntegrationTest;
import com.ticketdrop.support.Invariants;
import com.ticketdrop.support.SeatRow;
import com.ticketdrop.support.TestData;

/**
 * The race tests R1-R5 of section 10 of the design spec. Each one fires many requests at the running
 * service at the same instant. They are not tied to a strategy: they run against whichever one the
 * {@code ticketdrop.booking-strategy} setting selects, so they pass against the atomic strategy and
 * fail against the naive one.
 */
@IntegrationTest
@Tag("race")
class BookingRaceTest {

    private static final int ROUNDS = 20;
    private static final Duration ROUND_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How far ahead R4's hold expires once the race is set up, by round: 2, 5, 8, 11, 14 ms, repeated.
     * On the machine this was tuned on, 2 ms made every buy lose, 15 ms made every buy win and 5-8 ms
     * gave a mix. Spreading the delay keeps the moment of expiry inside the race on slower or faster
     * machines too.
     */
    private static Duration r4ExpiresIn(int round) {
        return Duration.ofMillis(2 + 3 * (round % 5));
    }

    /** R5's buyers give up after this long, so a stalled storm fails with numbers instead of a timeout. */
    private static final Duration R5_BUYER_DEADLINE = Duration.ofSeconds(60);
    private static final Duration R5_TIMEOUT = Duration.ofSeconds(90);

    @Autowired private ApiClient api;
    @Autowired private TestData data;
    @Autowired private Invariants invariants;
    @Autowired private JdbcClient jdbc;

    /** R1: 200 buyers request the same single seat. Exactly one gets 201; the rest get 409. */
    @Test
    void r1_twoHundredBuyersOneSeat() {
        for (int round = 0; round < ROUNDS; round++) {
            long event = api.createEvent("R1", 1, 1).longValue("id");
            long seat = api.listSeats(event).seats().get(0).id();
            List<Callable<ApiResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                calls.add(() -> api.hold(event, UUID.randomUUID(), List.of(seat)));
            }
            List<ApiResponse> responses = ConcurrentCalls.runAll(calls, ROUND_TIMEOUT);

            Map<Integer, Long> statuses = histogram(responses);
            assertThat(statuses)
                    .as("R1 round %d: statuses of 200 holds of one seat", round)
                    .isEqualTo(Map.of(201, 1L, 409, 199L));
            invariants.assertNone(event);
        }
    }

    /**
     * R2: 200 buyers each request a random 2-4 seats from the same block of 10, with the IDs in random
     * order. Only 201 and 409 occur, no seat is in two 201 responses, and afterwards the seats of each
     * 201 response are held under that response's hold ID and no other seat is held.
     */
    @Test
    void r2_overlappingGroups() {
        for (int round = 0; round < ROUNDS; round++) {
            long event = api.createEvent("R2", 1, 10).longValue("id");
            List<Long> block = api.listSeats(event).seats().stream().map(SeatRow::id).toList();

            List<Callable<ApiResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                List<Long> wanted = randomSeats(block, 2 + ThreadLocalRandom.current().nextInt(3));
                calls.add(() -> api.hold(event, UUID.randomUUID(), wanted));
            }
            List<ApiResponse> responses = ConcurrentCalls.runAll(calls, ROUND_TIMEOUT);

            String context = "R2 round " + round + ", statuses " + histogram(responses);
            assertThat(histogram(responses).keySet()).as(context).isSubsetOf(201, 409);

            Map<Long, UUID> promisedTo = new HashMap<>();
            for (ApiResponse response : responses) {
                if (response.status() != 201) {
                    continue;
                }
                UUID hold = response.uuid("holdId");
                for (long seat : response.longs("seatIds")) {
                    UUID earlier = promisedTo.put(seat, hold);
                    assertThat(earlier)
                            .as("%s: seat %d is in two 201 responses (holds %s and %s)", context, seat, earlier, hold)
                            .isNull();
                }
            }

            Map<Long, SeatState> inDatabase = seatStates(event);
            for (long seat : block) {
                SeatState state = inDatabase.get(seat);
                UUID promised = promisedTo.get(seat);
                if (promised != null) {
                    assertThat(state)
                            .as("%s: seat %d was promised to hold %s", context, seat, promised)
                            .isEqualTo(new SeatState("HELD", promised, null));
                } else {
                    assertThat(state.status())
                            .as("%s: seat %d is in no 201 response, so it must not be held", context, seat)
                            .isNotEqualTo("HELD");
                }
            }
            invariants.assertNone(event);
        }
    }

    /**
     * R3: the same hold is bought by 50 callers. Exactly one gets 201 and the rest get 200, all with the
     * same order ID, and one order row exists.
     */
    @Test
    void r3_oneHoldBoughtFiftyTimes() {
        for (int round = 0; round < ROUNDS; round++) {
            long event = api.createEvent("R3", 1, 3).longValue("id");
            List<Long> seats = api.listSeats(event).seats().stream().map(SeatRow::id).toList();
            ApiResponse held = api.hold(event, UUID.randomUUID(), seats);
            assertThat(held.status()).as("R3 round %d: setting up the hold", round).isEqualTo(201);
            UUID hold = held.uuid("holdId");

            List<Callable<ApiResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                calls.add(() -> api.buy(hold));
            }
            List<ApiResponse> responses = ConcurrentCalls.runAll(calls, ROUND_TIMEOUT);

            String context = "R3 round " + round + ", statuses " + histogram(responses);
            assertThat(histogram(responses)).as(context).isEqualTo(Map.of(201, 1L, 200, 49L));
            Set<UUID> orderIds = responses.stream().map(r -> r.uuid("orderId")).collect(Collectors.toSet());
            assertThat(orderIds).as("%s: order IDs in the responses", context).hasSize(1);
            assertThat(invariants.orders(event)).as("%s: order rows", context).isEqualTo(1);
            assertThat(invariants.soldSeats(event)).as("%s: sold seats", context).isEqualTo(3);
            invariants.assertNone(event);
        }
    }

    /**
     * R4: a 3-seat hold is set to expire a few milliseconds ahead. Its buyer buys while 30 other buyers
     * each try to hold one of its seats. Either the buy returns 201 and all three seats are sold to that
     * order, or it returns 410 and none are. Never one or two.
     */
    @Test
    void r4_buyAtTheMomentOfExpiry() {
        int bought = 0;
        int lost = 0;
        for (int round = 0; round < ROUNDS; round++) {
            long event = api.createEvent("R4", 1, 3).longValue("id");
            List<Long> seats = api.listSeats(event).seats().stream().map(SeatRow::id).toList();
            ApiResponse held = api.hold(event, UUID.randomUUID(), seats);
            assertThat(held.status()).as("R4 round %d: setting up the hold", round).isEqualTo(201);
            UUID hold = held.uuid("holdId");

            List<Callable<ApiResponse>> calls = new ArrayList<>();
            calls.add(() -> api.buy(hold));
            for (int i = 0; i < 30; i++) {
                long seat = seats.get(i % seats.size());
                calls.add(() -> api.hold(event, UUID.randomUUID(), List.of(seat)));
            }
            // The expiry is set last, just before the calls are released, so it lands while they are in
            // flight. r4ExpiresIn was tuned so that both outcomes occur over the 20 rounds; the split is
            // printed below. If every round goes the same way, this test is no longer testing the edge.
            data.expireHoldIn(hold, r4ExpiresIn(round));
            List<ApiResponse> responses = ConcurrentCalls.runAll(calls, ROUND_TIMEOUT);

            ApiResponse buy = responses.get(0);
            List<ApiResponse> holds = responses.subList(1, responses.size());
            String context = "R4 round " + round + ", buy " + buy.status() + ", holds " + histogram(holds);
            assertThat(histogram(holds).keySet()).as(context).isSubsetOf(201, 409);
            assertThat(buy.status()).as(context).isIn(201, 410);

            Map<Long, SeatState> inDatabase = seatStates(event);
            if (buy.status() == 201) {
                bought++;
                UUID order = buy.uuid("orderId");
                for (long seat : seats) {
                    assertThat(inDatabase.get(seat))
                            .as("%s: seat %d after the buy returned 201", context, seat)
                            .isEqualTo(new SeatState("SOLD", null, order));
                }
            } else {
                lost++;
                assertThat(invariants.soldSeats(event))
                        .as("%s: seats sold after the buy returned 410", context)
                        .isZero();
            }
            invariants.assertNone(event);
        }
        System.out.printf("R4: the buy won %d of %d rounds and lost %d%n", bought, ROUNDS, lost);
    }

    /**
     * R5, the storm, run once: on a 50-seat event, 200 buyers repeat "hold 1-4 random seats, then buy
     * (80%) or release (20%)" until the event is sold out. All 50 seats end up sold, no 5xx occurs, and
     * the invariants pass, including V5 (accounting) and V6 (kept promises).
     */
    @Test
    void r5_storm() {
        long event = api.createEvent("R5", 5, 10).longValue("id");
        long deadline = System.nanoTime() + R5_BUYER_DEADLINE.toNanos();

        List<Callable<Tally>> buyers = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            buyers.add(() -> stormBuyer(event, deadline));
        }
        Tally total = ConcurrentCalls.runAll(buyers, R5_TIMEOUT).stream().reduce(Tally.NONE, Tally::plus);

        int sold = invariants.soldSeats(event);
        int orders = invariants.orders(event);
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(sold).as("R5: seats sold out of 50").isEqualTo(50);
        softly.assertThat(total.seatsBought())
                .as("V5: seats in 201 buy responses (%d) vs SOLD seats (%d)", total.seatsBought(), sold)
                .isEqualTo(sold);
        softly.assertThat(total.bought())
                .as("V5: 201 buy responses (%d) vs orders (%d)", total.bought(), orders)
                .isEqualTo(orders);
        softly.assertThat(total.brokenPromises()).as("V6: buys answered 410 (broken promises)").isZero();
        softly.assertThat(total.serverErrors()).as("R5: responses with a 5xx status").isZero();
        softly.assertThat(total.unexpected()).as("R5: other unexpected responses").isEmpty();
        softly.assertThat(invariants.violations(event)).as("R5: invariants V1-V4").isEmpty();
        softly.assertAll();
    }

    /**
     * One buyer of the storm. It picks seats from those the listing shows as available, so the end of
     * the run is not an endless series of 409s. A buy answered 410 is followed by a release, as a real
     * client would do, so that a broken strategy cannot stall the storm for the whole hold duration.
     */
    private Tally stormBuyer(long event, long deadline) throws InterruptedException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        UUID buyer = UUID.randomUUID();
        Tally tally = Tally.NONE;
        while (System.nanoTime() < deadline) {
            ApiResponse listing = api.listSeats(event);
            if (listing.status() != 200) {
                tally = tally.plus(Tally.unexpected("list seats", listing));
                continue;
            }
            List<SeatRow> seats = listing.seats();
            if (seats.stream().allMatch(seat -> seat.status().equals("SOLD"))) {
                return tally;
            }
            List<Long> available = seats.stream().filter(seat -> seat.status().equals("AVAILABLE"))
                    .map(SeatRow::id).toList();
            if (available.isEmpty()) {
                Thread.sleep(10);
                continue;
            }

            ApiResponse hold = api.hold(event, buyer, randomSeats(available, 1 + random.nextInt(4)));
            if (hold.status() == 409) {
                continue;
            }
            if (hold.status() != 201) {
                tally = tally.plus(Tally.unexpected("hold", hold));
                continue;
            }
            UUID holdId = hold.uuid("holdId");

            if (random.nextInt(100) >= 80) {
                ApiResponse release = api.release(holdId);
                if (release.status() != 204) {
                    tally = tally.plus(Tally.unexpected("release", release));
                }
                continue;
            }
            ApiResponse buy = api.buy(holdId);
            switch (buy.status()) {
                case 201 -> tally = tally.plus(new Tally(1, buy.longs("seatIds").size(), 0, 0, List.of()));
                case 410 -> {
                    tally = tally.plus(new Tally(0, 0, 1, 0, List.of()));
                    api.release(holdId);
                }
                default -> tally = tally.plus(Tally.unexpected("buy", buy));
            }
        }
        return tally;
    }

    /** What one storm buyer saw. {@code bought} counts 201 buys; {@code brokenPromises} counts 410 buys. */
    private record Tally(int bought, int seatsBought, int brokenPromises, int serverErrors, List<String> unexpected) {

        static final Tally NONE = new Tally(0, 0, 0, 0, List.of());

        static Tally unexpected(String call, ApiResponse response) {
            String what = call + " -> " + response.status() + " " + response.rawBody();
            return response.status() >= 500
                    ? new Tally(0, 0, 0, 1, List.of())
                    : new Tally(0, 0, 0, 0, List.of(what));
        }

        Tally plus(Tally other) {
            List<String> both = new ArrayList<>(unexpected);
            both.addAll(other.unexpected);
            return new Tally(bought + other.bought, seatsBought + other.seatsBought,
                    brokenPromises + other.brokenPromises, serverErrors + other.serverErrors, both);
        }
    }

    /** A seat's row as stored, which is more than the API shows: it includes the hold and order IDs. */
    private record SeatState(String status, UUID holdId, UUID orderId) {
    }

    private Map<Long, SeatState> seatStates(long event) {
        Map<Long, SeatState> states = new HashMap<>();
        jdbc.sql("select id, status, hold_id, order_id from seats where event_id = :event")
                .param("event", event)
                .query((row, n) -> states.put(row.getLong("id"), new SeatState(
                        row.getString("status"),
                        row.getObject("hold_id", UUID.class),
                        row.getObject("order_id", UUID.class))))
                .list();
        return states;
    }

    /** {@code count} distinct seats picked at random from {@code from}, in random order (not sorted). */
    private static List<Long> randomSeats(List<Long> from, int count) {
        List<Long> shuffled = new ArrayList<>(from);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
    }

    private static Map<Integer, Long> histogram(List<ApiResponse> responses) {
        return responses.stream().collect(Collectors.groupingBy(
                ApiResponse::status, TreeMap::new, Collectors.counting()));
    }
}
