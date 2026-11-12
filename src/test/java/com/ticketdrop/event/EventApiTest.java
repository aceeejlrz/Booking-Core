package com.ticketdrop.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticketdrop.support.ApiClient;
import com.ticketdrop.support.ApiResponse;
import com.ticketdrop.support.IntegrationTest;
import com.ticketdrop.support.SeatRow;
import com.ticketdrop.support.TestData;

@IntegrationTest
class EventApiTest {

    @Autowired
    ApiClient api;

    @Autowired
    TestData data;

    @Test
    void createsAnEventWithAllItsSeats() {
        ApiResponse r = api.createEvent("Launch Night", 20, 25);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("name")).isEqualTo("Launch Night");
        assertThat(r.intValue("rows")).isEqualTo(20);
        assertThat(r.intValue("seatsPerRow")).isEqualTo(25);
        assertThat(r.intValue("seatCount")).isEqualTo(500);
        assertThat(api.listSeats(r.longValue("id")).seats()).hasSize(500);
    }

    @Test
    void listsSeatsByRowThenNumberAllAvailable() {
        long event = api.createEvent("E", 2, 3).longValue("id");
        List<SeatRow> seats = api.listSeats(event).seats();
        assertThat(seats).extracting(SeatRow::row, SeatRow::number).containsExactly(
                tuple(1, 1), tuple(1, 2), tuple(1, 3), tuple(2, 1), tuple(2, 2), tuple(2, 3));
        assertThat(seats).extracting(SeatRow::status).containsOnly("AVAILABLE");
    }

    @ParameterizedTest
    @CsvSource({ "0,5", "101,5", "5,0", "5,101" })
    void rejectsSizesOutsideOneToOneHundred(int rows, int seatsPerRow) {
        ApiResponse r = api.createEvent("E", rows, seatsPerRow);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void rejectsBlankAndOverlongNames() {
        assertThat(api.createEvent("", 1, 1).status()).isEqualTo(400);
        assertThat(api.createEvent("x".repeat(101), 1, 1).status()).isEqualTo(400);
        assertThat(api.createEvent("x".repeat(100), 1, 1).status()).isEqualTo(201);
    }

    @Test
    @Timeout(10)
    void acceptsTheLargestEvent() {
        ApiResponse r = api.createEvent("Big", 100, 100);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.intValue("seatCount")).isEqualTo(10_000);
    }

    @Test
    void unknownEventIs404() {
        assertThat(api.listSeats(999_999_999L).status()).isEqualTo(404);
        assertThat(api.listSeats(999_999_999L).text("error")).isEqualTo("EVENT_NOT_FOUND");
        assertThat(api.summary(999_999_999L).text("error")).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void reportsEffectiveStatus() {
        long event = api.createEvent("E", 1, 4).longValue("id");
        List<SeatRow> s = api.listSeats(event).seats();
        data.markHeld(s.get(0).id(), Duration.ofMinutes(5));   // active hold
        data.markHeld(s.get(1).id(), Duration.ofSeconds(-1));  // expired hold
        data.markSold(s.get(2).id());
        assertThat(api.listSeats(event).seats()).extracting(SeatRow::status)
                .containsExactly("HELD", "AVAILABLE", "SOLD", "AVAILABLE");
        ApiResponse summary = api.summary(event);
        assertThat(summary.intValue("seatCount")).isEqualTo(4);
        assertThat(summary.intValue("available")).isEqualTo(2);
        assertThat(summary.intValue("held")).isEqualTo(1);
        assertThat(summary.intValue("sold")).isEqualTo(1);
        assertThat(summary.intValue("orders")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{not json", "{\"name\":\"E\",\"rows\":\"many\",\"seatsPerRow\":5}", "{}" })
    void malformedRequestsAre400(String body) {
        ApiResponse r = api.send("POST", "/api/events", body);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void missingBodyAndNonNumericIdAre400() {
        assertThat(api.send("POST", "/api/events", null).text("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(api.send("GET", "/api/events/abc/seats", null).status()).isEqualTo(400);
        assertThat(api.send("GET", "/api/events/abc/seats", null).text("error")).isEqualTo("VALIDATION_FAILED");
    }
}
