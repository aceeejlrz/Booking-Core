package com.ticketdrop.booking;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketdrop.config.BookingProperties;

/**
 * The safe implementation of the booking rules, and the default strategy. It follows section 6 of
 * the design spec, including its concurrency requirements.
 *
 * <p>Every operation has the same shape: LOCK the seat rows it is about to change, in ascending ID
 * order, with {@code select ... order by id for update}; DECIDE using what the locked rows say
 * now; then WRITE with statements that re-state the condition they depend on.
 *
 * <ul>
 *   <li><b>Why locking makes the decision safe.</b> At READ COMMITTED, a {@code for update} that
 *       meets a row another transaction has changed waits for that transaction to finish, then
 *       re-checks its {@code where} clause against the row as it was committed and returns that
 *       version. So once the lock is held, what the query returned is the current truth and nobody
 *       else can change it until this transaction ends.</li>
 *   <li><b>Why ascending order.</b> Two requests that want overlapping seats both lock the lowest
 *       shared seat first. One waits for the other instead of each holding a seat the other needs,
 *       which is the cycle that makes a deadlock. A multi-row {@code update} on its own would lock
 *       rows in whatever order the scan visits them, so it is never the statement that takes the
 *       locks here.</li>
 *   <li><b>Database time only.</b> "Free" and "active" are decided with {@code now()}, the start
 *       time of the current transaction.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "ticketdrop.booking-strategy", havingValue = "atomic", matchIfMissing = true)
public class AtomicBookingService implements BookingService {

    /** A seat is free if nobody has it, or if the hold on it has run out (section 6, Definitions). */
    private static final String FREE = "(status = 'AVAILABLE' or (status = 'HELD' and hold_expires_at <= now()))";

    private final JdbcClient jdbc;
    private final BookingProperties properties;

    public AtomicBookingService(JdbcClient jdbc, BookingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Hold hold(long eventId, UUID buyerId, List<Long> seatIds) {
        List<Long> wanted = seatIds.stream().sorted().toList();

        // LOCK the requested seats of this event in ascending ID order, and read whether each one is
        // free. Because the row is locked before "free" is returned, the answer cannot go stale.
        Map<Long, Boolean> freeBySeatId = jdbc.sql("""
                select id, %s as free
                from seats
                where event_id = :event and id in (:ids)
                order by id
                for update""".formatted(FREE))
                .param("event", eventId).param("ids", wanted)
                .query((rs, rowNum) -> Map.entry(rs.getLong("id"), rs.getBoolean("free")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // DECIDE. Unknown seats are reported before unavailable ones (section 6, Hold, step 1).
        List<Long> unknown = wanted.stream().filter(id -> !freeBySeatId.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new SeatsNotFoundException(unknown);
        }
        List<Long> notFree = wanted.stream().filter(id -> !freeBySeatId.get(id)).toList();
        if (!notFree.isEmpty()) {
            throw new SeatsUnavailableException(notFree);   // rolls back: nothing changes
        }

        // WRITE. The hold row comes first, because seats.hold_id refers to it.
        UUID holdId = UUID.randomUUID();
        OffsetDateTime expiresAt = jdbc.sql("""
                insert into holds (id, event_id, buyer_id, seat_count, expires_at)
                values (:hold, :event, :buyer, :seatCount, now() + make_interval(secs => :seconds))
                returning expires_at""")
                .param("hold", holdId).param("event", eventId).param("buyer", buyerId)
                .param("seatCount", wanted.size())
                .param("seconds", properties.holdDuration().toMillis() / 1000.0)
                .query(OffsetDateTime.class).single();

        // The update re-states "free" in its condition. The locks already guarantee it, so the count
        // always matches; if it ever did not, failing (and rolling back) beats a partial hold.
        int taken = jdbc.sql("""
                update seats
                set status = 'HELD', hold_id = :hold, hold_expires_at = :expiresAt
                where id in (:ids) and %s""".formatted(FREE))
                .param("hold", holdId).param("expiresAt", expiresAt).param("ids", wanted)
                .update();
        if (taken != wanted.size()) {
            throw new IllegalStateException("Hold " + holdId + " took " + taken + " of " + wanted.size()
                    + " locked free seats");
        }

        return new Hold(holdId, eventId, buyerId, wanted, expiresAt.toInstant());
    }

    @Override
    @Transactional
    public void release(UUID holdId) {
        // LOCK the seats that still carry this hold, in ascending ID order. Expired ones are
        // included: they are free anyway, and clearing them is harmless. Sold seats and seats another
        // hold has taken no longer carry this hold's ID, so they are not touched.
        List<Long> stillHeld = jdbc.sql("""
                select id from seats
                where hold_id = :hold and status = 'HELD'
                order by id
                for update""")
                .param("hold", holdId)
                .query(Long.class).list();
        if (stillHeld.isEmpty()) {
            return;   // unknown, already released or already bought: not an error (always 204)
        }

        jdbc.sql("""
                update seats
                set status = 'AVAILABLE', hold_id = null, hold_expires_at = null
                where id in (:ids) and hold_id = :hold and status = 'HELD'""")
                .param("ids", stillHeld).param("hold", holdId)
                .update();
    }

    @Override
    @Transactional
    public PurchaseResult buy(UUID holdId) {
        // The hold row is never changed after it is written, so it needs no lock.
        Optional<HoldRecord> found = jdbc.sql("select event_id, buyer_id, seat_count from holds where id = :hold")
                .param("hold", holdId)
                .query((rs, rowNum) -> new HoldRecord(
                        rs.getLong("event_id"), rs.getObject("buyer_id", UUID.class), rs.getInt("seat_count")))
                .optional();
        if (found.isEmpty()) {
            throw new HoldNotFoundException(holdId);
        }
        HoldRecord hold = found.get();

        // LOCK the seats that are still actively held under this hold, in ascending ID order.
        // A simultaneous second buy of the same hold waits here; when the first commits, the seats
        // are SOLD and no longer carry the hold, so the second locks nothing.
        List<Long> activeSeats = jdbc.sql("""
                select id from seats
                where hold_id = :hold and status = 'HELD' and hold_expires_at > now()
                order by id
                for update""")
                .param("hold", holdId)
                .query(Long.class).list();

        // DECIDE. The hold is bought whole or not at all: "at least one seat is still mine" is not
        // enough, because a seat can be lost to another buyer at the moment of expiry.
        if (activeSeats.size() != hold.seatCount()) {
            // A new statement, so it sees an order that a simultaneous buy has just committed.
            return existingOrder(holdId, hold)
                    .map(order -> new PurchaseResult(order, false))
                    .orElseThrow(() -> new HoldNotActiveException(holdId));
        }

        // WRITE. The order row comes first, because seats.order_id refers to it. The unique
        // constraint on orders.hold_id is a last line of defence against a second order.
        UUID orderId = UUID.randomUUID();
        OffsetDateTime createdAt = jdbc.sql("""
                insert into orders (id, event_id, buyer_id, hold_id)
                values (:order, :event, :buyer, :hold)
                returning created_at""")
                .param("order", orderId).param("event", hold.eventId()).param("buyer", hold.buyerId())
                .param("hold", holdId)
                .query(OffsetDateTime.class).single();
        int sold = jdbc.sql("""
                update seats
                set status = 'SOLD', hold_id = null, hold_expires_at = null, order_id = :order
                where id in (:ids) and hold_id = :hold and status = 'HELD'""")
                .param("order", orderId).param("ids", activeSeats).param("hold", holdId)
                .update();
        if (sold != activeSeats.size()) {
            throw new IllegalStateException("Order " + orderId + " sold " + sold + " of " + activeSeats.size()
                    + " locked seats");
        }

        Order order = new Order(orderId, hold.eventId(), hold.buyerId(), holdId, activeSeats, createdAt.toInstant());
        return new PurchaseResult(order, true);
    }

    /** The order already created for this hold, if there is one, with the seats it sold. */
    private Optional<Order> existingOrder(UUID holdId, HoldRecord hold) {
        return jdbc.sql("select id, created_at from orders where hold_id = :hold")
                .param("hold", holdId)
                .query((rs, rowNum) -> Map.entry(
                        rs.getObject("id", UUID.class), rs.getObject("created_at", OffsetDateTime.class)))
                .optional()
                .map(row -> {
                    List<Long> soldSeats = jdbc.sql("select id from seats where order_id = :order order by id")
                            .param("order", row.getKey())
                            .query(Long.class).list();
                    return new Order(row.getKey(), hold.eventId(), hold.buyerId(), holdId, soldSeats,
                            row.getValue().toInstant());
                });
    }

    /** The columns of a row of the holds table that buying needs. */
    private record HoldRecord(long eventId, UUID buyerId, int seatCount) {
    }
}
