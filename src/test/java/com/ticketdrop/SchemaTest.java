package com.ticketdrop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.ticketdrop.support.IntegrationTest;

/**
 * Proves what the schema itself guarantees: the constraints of the data model (invariants V1 and V4)
 * and the invariant_violations view (V2 and V3).
 */
@IntegrationTest
@Transactional // each test's rows are rolled back
class SchemaTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void heldSeatMustCarryAHold() {
        long event = insertEvent(1, 1);
        assertThatThrownBy(() -> jdbc.sql(
                "insert into seats (event_id, row_no, seat_no, status) values (:e, 1, 1, 'HELD')")
                .param("e", event).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void soldSeatMustCarryAnOrder() {
        long event = insertEvent(1, 1);
        assertThatThrownBy(() -> jdbc.sql(
                "insert into seats (event_id, row_no, seat_no, status) values (:e, 1, 1, 'SOLD')")
                .param("e", event).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void availableSeatCannotCarryAHold() {
        long event = insertEvent(1, 1);
        UUID hold = insertHold(event, 1);
        assertThatThrownBy(() -> jdbc.sql("""
                insert into seats (event_id, row_no, seat_no, status, hold_id, hold_expires_at)
                values (:e, 1, 1, 'AVAILABLE', :h, now() + interval '5 minutes')""")
                .param("e", event).param("h", hold).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seatPositionIsUniqueWithinAnEvent() {
        long event = insertEvent(1, 1);
        insertAvailableSeat(event, 1, 1);
        assertThatThrownBy(() -> insertAvailableSeat(event, 1, 1))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void eventSizeIsLimitedToOneHundred() {
        assertThatThrownBy(() -> insertEvent(101, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aHoldCanHaveOnlyOneOrder() {
        long event = insertEvent(1, 1);
        UUID hold = insertHold(event, 1);
        insertOrder(event, hold);
        assertThatThrownBy(() -> insertOrder(event, hold))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void viewIsEmptyForAConsistentSale() {
        long event = insertEvent(1, 2);
        UUID hold = insertHold(event, 2);
        UUID order = insertOrder(event, hold);
        insertSoldSeat(event, 1, 1, order);
        insertSoldSeat(event, 1, 2, order);
        assertThat(violations(event)).isEmpty();
    }

    @Test
    void viewReportsAnOrderWithTooFewSeats() {
        long event = insertEvent(1, 2);
        UUID hold = insertHold(event, 2);
        UUID order = insertOrder(event, hold);
        insertSoldSeat(event, 1, 1, order);
        assertThat(violations(event)).containsExactly("V2");
    }

    @Test
    void viewReportsASoldSeatWhoseOrderIsForAnotherEvent() {
        long eventA = insertEvent(1, 1);
        long eventB = insertEvent(1, 1);
        UUID hold = insertHold(eventB, 1);
        UUID order = insertOrder(eventB, hold);
        insertSoldSeat(eventA, 1, 1, order);
        assertThat(violations(eventA)).contains("V3");
    }

    private long insertEvent(int rowCount, int seatsPerRow) {
        return jdbc.sql("""
                insert into events (name, row_count, seats_per_row)
                values ('Schema test', :rows, :perRow) returning id""")
                .param("rows", rowCount).param("perRow", seatsPerRow)
                .query(Long.class).single();
    }

    private UUID insertHold(long event, int seatCount) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into holds (id, event_id, buyer_id, seat_count, expires_at)
                values (:id, :e, :buyer, :n, now() + interval '5 minutes')""")
                .param("id", id).param("e", event).param("buyer", UUID.randomUUID()).param("n", seatCount)
                .update();
        return id;
    }

    private UUID insertOrder(long event, UUID hold) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into orders (id, event_id, buyer_id, hold_id) values (:id, :e, :buyer, :h)")
                .param("id", id).param("e", event).param("buyer", UUID.randomUUID()).param("h", hold)
                .update();
        return id;
    }

    private void insertAvailableSeat(long event, int row, int seat) {
        jdbc.sql("insert into seats (event_id, row_no, seat_no) values (:e, :r, :s)")
                .param("e", event).param("r", row).param("s", seat).update();
    }

    private void insertSoldSeat(long event, int row, int seat, UUID order) {
        jdbc.sql("""
                insert into seats (event_id, row_no, seat_no, status, order_id)
                values (:e, :r, :s, 'SOLD', :o)""")
                .param("e", event).param("r", row).param("s", seat).param("o", order).update();
    }

    private List<String> violations(long event) {
        return jdbc.sql("select invariant from invariant_violations where event_id = :e order by invariant")
                .param("e", event).query(String.class).list();
    }
}
