package com.ticketdrop.booking;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketdrop.config.BookingProperties;

/**
 * A deliberately unsafe implementation of the booking rules, kept so that the double-booking bug
 * can be reproduced on demand. Never the default strategy.
 *
 * <p>Every operation has the same shape: READ the rows it needs, DECIDE in Java, then WRITE. There
 * are no locks and the writes carry no conditions. That is correct when requests arrive one at a
 * time. When two requests overlap, both can read the same seat as free before either has written,
 * both decide "it is mine", and both write. The second write silently replaces the first, and two
 * buyers have been promised the same seat.
 */
@Service
@ConditionalOnProperty(name = "ticketdrop.booking-strategy", havingValue = "naive")
public class NaiveBookingService implements BookingService {

    private static final Logger log = LoggerFactory.getLogger(NaiveBookingService.class);

    private final JdbcClient jdbc;
    private final BookingProperties properties;

    public NaiveBookingService(JdbcClient jdbc, BookingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
        log.warn("NAIVE booking strategy is enabled. It is deliberately unsafe and will double-book "
                + "under load. Never use it outside a demonstration.");
    }

    @Override
    @Transactional
    public Hold hold(long eventId, UUID buyerId, List<Long> seatIds) {
        List<Long> wanted = seatIds.stream().sorted().toList();

        // READ: which of the wanted seats belong to this event, and which are free right now?
        // PostgreSQL decides "free", because it owns the clock that hold expiry is measured against.
        Map<Long, Boolean> freeBySeatId = jdbc.sql("""
                select id,
                       (status = 'AVAILABLE' or (status = 'HELD' and hold_expires_at <= now())) as free
                from seats
                where event_id = :event and id in (:ids)""")
                .param("event", eventId).param("ids", wanted)
                .query((rs, rowNum) -> Map.entry(rs.getLong("id"), rs.getBoolean("free")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // DECIDE, in Java, using what was just read.
        List<Long> unknown = wanted.stream().filter(id -> !freeBySeatId.containsKey(id)).toList();
        if (!unknown.isEmpty()) {
            throw new SeatsNotFoundException(unknown);
        }
        List<Long> notFree = wanted.stream().filter(id -> !freeBySeatId.get(id)).toList();
        if (!notFree.isEmpty()) {
            throw new SeatsUnavailableException(notFree);
        }

        // WRITE. This is where it goes wrong under load: the answer "free" may already be out of
        // date, because another request can have read the same seats a moment ago and be about to
        // write too. The update below does not check anything; it simply overwrites.
        UUID holdId = UUID.randomUUID();
        OffsetDateTime expiresAt = jdbc.sql("""
                insert into holds (id, event_id, buyer_id, seat_count, expires_at)
                values (:hold, :event, :buyer, :seatCount, now() + make_interval(secs => :seconds))
                returning expires_at""")
                .param("hold", holdId).param("event", eventId).param("buyer", buyerId)
                .param("seatCount", wanted.size())
                .param("seconds", properties.holdDuration().toMillis() / 1000.0)
                .query(OffsetDateTime.class).single();
        jdbc.sql("""
                update seats
                set status = 'HELD', hold_id = :hold, hold_expires_at = :expiresAt
                where id in (:ids)""")
                .param("hold", holdId).param("expiresAt", expiresAt).param("ids", wanted)
                .update();

        return new Hold(holdId, eventId, buyerId, wanted, expiresAt.toInstant());
    }

    @Override
    @Transactional
    public void release(UUID holdId) {
        // READ: the seats this hold still has. Sold seats, and seats another hold took over, are
        // no longer in this list, so they are left alone.
        List<Long> stillHeld = jdbc.sql("select id from seats where hold_id = :hold and status = 'HELD'")
                .param("hold", holdId)
                .query(Long.class).list();
        if (stillHeld.isEmpty()) {
            return;   // unknown, already released or already bought: nothing to do, and not an error
        }

        // WRITE, again with no check that the seats are still in the state that was just read.
        jdbc.sql("""
                update seats
                set status = 'AVAILABLE', hold_id = null, hold_expires_at = null
                where id in (:ids)""")
                .param("ids", stillHeld)
                .update();
    }

    @Override
    @Transactional
    public PurchaseResult buy(UUID holdId) {
        // READ the hold itself.
        Optional<HoldRecord> found = jdbc.sql("select event_id, buyer_id, seat_count from holds where id = :hold")
                .param("hold", holdId)
                .query((rs, rowNum) -> new HoldRecord(
                        rs.getLong("event_id"), rs.getObject("buyer_id", UUID.class), rs.getInt("seat_count")))
                .optional();
        if (found.isEmpty()) {
            throw new HoldNotFoundException(holdId);
        }
        HoldRecord hold = found.get();

        // READ the seats that are still actively held under it.
        List<Long> activeSeats = jdbc.sql("""
                select id from seats
                where hold_id = :hold and status = 'HELD' and hold_expires_at > now()
                order by id""")
                .param("hold", holdId)
                .query(Long.class).list();

        // DECIDE: a hold is bought whole or not at all, so every one of its seats must still be there.
        if (activeSeats.size() != hold.seatCount()) {
            // Either this hold was bought already (then answer with that order again), or it has
            // expired, been released, or lost a seat.
            return existingOrder(holdId, hold)
                    .map(order -> new PurchaseResult(order, false))
                    .orElseThrow(() -> new HoldNotActiveException(holdId));
        }

        // WRITE the order and mark the seats sold, once more without re-checking anything.
        UUID orderId = UUID.randomUUID();
        OffsetDateTime createdAt = jdbc.sql("""
                insert into orders (id, event_id, buyer_id, hold_id)
                values (:order, :event, :buyer, :hold)
                returning created_at""")
                .param("order", orderId).param("event", hold.eventId()).param("buyer", hold.buyerId())
                .param("hold", holdId)
                .query(OffsetDateTime.class).single();
        jdbc.sql("""
                update seats
                set status = 'SOLD', hold_id = null, hold_expires_at = null, order_id = :order
                where id in (:ids)""")
                .param("order", orderId).param("ids", activeSeats)
                .update();

        Order order = new Order(orderId, hold.eventId(), hold.buyerId(), holdId, activeSeats, createdAt.toInstant());
        return new PurchaseResult(order, true);
    }

    /** The order already created for this hold, if there is one, with the seats it sold. */
    private Optional<Order> existingOrder(UUID holdId, HoldRecord hold) {
        return jdbc.sql("select id, created_at from orders where hold_id = :hold")
                .param("hold", holdId)
                .query((rs, rowNum) -> {
                    UUID orderId = rs.getObject("id", UUID.class);
                    List<Long> soldSeats = jdbc.sql("select id from seats where order_id = :order order by id")
                            .param("order", orderId)
                            .query(Long.class).list();
                    return new Order(orderId, hold.eventId(), hold.buyerId(), holdId, soldSeats,
                            rs.getObject("created_at", OffsetDateTime.class).toInstant());
                })
                .optional();
    }

    /** The columns of a row of the holds table that buying needs. */
    private record HoldRecord(long eventId, UUID buyerId, int seatCount) {
    }
}
