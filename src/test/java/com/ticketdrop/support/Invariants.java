package com.ticketdrop.support;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Checks one event against the booking invariants, by reading the invariant_violations view, and
 * counts what was actually sold. Race tests end by calling {@link #assertNone(long)}.
 */
public final class Invariants {

    private final JdbcClient jdbc;

    public Invariants(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every violation found for the event, each as "V2: what is wrong". Empty when all is well. */
    public List<String> violations(long eventId) {
        return jdbc.sql("""
                select invariant || ': ' || detail
                from invariant_violations
                where event_id = :event
                order by 1""")
                .param("event", eventId)
                .query(String.class).list();
    }

    /** Fails the test, listing the violations, unless the event has none. */
    public void assertNone(long eventId) {
        List<String> found = violations(eventId);
        if (!found.isEmpty()) {
            throw new AssertionError("Event " + eventId + " breaks " + found.size() + " invariant(s):\n  "
                    + String.join("\n  ", found));
        }
    }

    /** How many of the event's seats are SOLD in the database. */
    public int soldSeats(long eventId) {
        return jdbc.sql("select count(*) from seats where event_id = :event and status = 'SOLD'")
                .param("event", eventId)
                .query(Integer.class).single();
    }

    /** How many orders the event has in the database. */
    public int orders(long eventId) {
        return jdbc.sql("select count(*) from orders where event_id = :event")
                .param("event", eventId)
                .query(Integer.class).single();
    }
}
