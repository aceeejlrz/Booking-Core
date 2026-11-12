package com.ticketdrop.support;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Puts the database into states that tests need but the API cannot produce on demand, such as a
 * hold that has already expired. Everything here goes straight to PostgreSQL.
 */
public final class TestData {

    private final JdbcClient jdbc;

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * PostgreSQL's clock. It is read as epoch seconds, so no timezone conversion is involved and a
     * timestamp-handling bug in the service cannot cancel itself out in a comparison.
     */
    public Instant dbNow() {
        BigDecimal epochSeconds = jdbc.sql("select extract(epoch from now())")
                .query(BigDecimal.class).single();
        return Instant.ofEpochMilli(epochSeconds.movePointRight(3).longValue());
    }

    /**
     * Holds one seat under a new 1-seat hold that expires {@code expiresFromNow} from now.
     * A negative duration creates a hold that has already expired.
     */
    public void markHeld(long seatId, Duration expiresFromNow) {
        UUID hold = insertHold(seatId, expiresFromNow);
        jdbc.sql("""
                update seats
                set status = 'HELD', hold_id = :hold,
                    hold_expires_at = (select expires_at from holds where id = :hold)
                where id = :seat""")
                .param("hold", hold).param("seat", seatId).update();
    }

    /** Sells one seat: creates a 1-seat hold and its order, and marks the seat SOLD to that order. */
    public void markSold(long seatId) {
        UUID hold = insertHold(seatId, Duration.ofMinutes(5));
        UUID order = UUID.randomUUID();
        jdbc.sql("""
                insert into orders (id, event_id, buyer_id, hold_id)
                select :order, event_id, buyer_id, id from holds where id = :hold""")
                .param("order", order).param("hold", hold).update();
        jdbc.sql("""
                update seats
                set status = 'SOLD', hold_id = null, hold_expires_at = null, order_id = :order
                where id = :seat""")
                .param("order", order).param("seat", seatId).update();
    }

    /** Makes a hold expired: its expiry, and its seats' copy of it, move one second into the past. */
    public void expireHold(UUID holdId) {
        jdbc.sql("update holds set expires_at = now() - interval '1 second' where id = :hold")
                .param("hold", holdId).update();
        jdbc.sql("update seats set hold_expires_at = now() - interval '1 second' where hold_id = :hold")
                .param("hold", holdId).update();
    }

    /**
     * Takes one seat away from whatever hold has it and gives it to a fresh 1-seat hold of a random
     * buyer, expiring in 5 minutes. The original hold stays unexpired but is no longer whole.
     */
    public void stealSeat(long seatId) {
        markHeld(seatId, Duration.ofMinutes(5));
    }

    private UUID insertHold(long seatId, Duration expiresFromNow) {
        UUID hold = UUID.randomUUID();
        jdbc.sql("""
                insert into holds (id, event_id, buyer_id, seat_count, expires_at)
                select :hold, event_id, :buyer, 1, now() + make_interval(secs => :seconds)
                from seats where id = :seat""")
                .param("hold", hold)
                .param("buyer", UUID.randomUUID())
                .param("seconds", expiresFromNow.toMillis() / 1000.0)
                .param("seat", seatId)
                .update();
        return hold;
    }
}
