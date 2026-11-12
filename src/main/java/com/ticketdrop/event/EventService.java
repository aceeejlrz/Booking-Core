package com.ticketdrop.event;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates events and reads their seats. It never changes a seat's state: holding, releasing and
 * buying belong to the booking package.
 */
@Service
public class EventService {

    /**
     * What the API reports for a seat. A hold that has expired is simply treated as gone: there is
     * no background job, so the row keeps saying HELD until the next hold or release overwrites it.
     * The comparison uses PostgreSQL's clock, like every expiry decision in the service.
     */
    private static final String EFFECTIVE_STATUS =
            "case when status = 'HELD' and hold_expires_at <= now() then 'AVAILABLE' else status end";

    private final JdbcClient jdbc;

    public EventService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the event and every one of its seats, all AVAILABLE, in one transaction. */
    @Transactional
    public Event create(String name, int rows, int seatsPerRow) {
        long id = jdbc.sql("""
                insert into events (name, row_count, seats_per_row)
                values (:name, :rows, :perRow)
                returning id""")
                .param("name", name).param("rows", rows).param("perRow", seatsPerRow)
                .query(Long.class).single();

        // One set-based statement instead of one insert per seat: 10,000 seats is a single round trip.
        // Ordering the rows makes seat IDs ascend in listing order.
        jdbc.sql("""
                insert into seats (event_id, row_no, seat_no)
                select :event, r, s
                from generate_series(1, :rows) as r, generate_series(1, :perRow) as s
                order by r, s""")
                .param("event", id).param("rows", rows).param("perRow", seatsPerRow)
                .update();

        return new Event(id, name, rows, seatsPerRow, rows * seatsPerRow);
    }

    public void requireEvent(long eventId) {
        boolean exists = jdbc.sql("select exists (select 1 from events where id = :event)")
                .param("event", eventId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new EventNotFoundException(eventId);
        }
    }

    public List<SeatView> seats(long eventId) {
        requireEvent(eventId);
        return jdbc.sql("select id, row_no, seat_no, " + EFFECTIVE_STATUS + " as effective_status "
                + "from seats where event_id = :event order by row_no, seat_no")
                .param("event", eventId)
                .query((rs, rowNum) -> new SeatView(
                        rs.getLong("id"),
                        rs.getInt("row_no"),
                        rs.getInt("seat_no"),
                        rs.getString("effective_status")))
                .list();
    }

    public EventSummary summary(long eventId) {
        requireEvent(eventId);
        return jdbc.sql("select count(*) as seat_count, "
                + "count(*) filter (where effective_status = 'AVAILABLE') as available, "
                + "count(*) filter (where effective_status = 'HELD') as held, "
                + "count(*) filter (where effective_status = 'SOLD') as sold, "
                + "(select count(*) from orders where event_id = :event) as orders "
                + "from (select " + EFFECTIVE_STATUS + " as effective_status "
                + "from seats where event_id = :event) as seat")
                .param("event", eventId)
                .query((rs, rowNum) -> new EventSummary(
                        eventId,
                        rs.getInt("seat_count"),
                        rs.getInt("available"),
                        rs.getInt("held"),
                        rs.getInt("sold"),
                        rs.getInt("orders")))
                .single();
    }
}
