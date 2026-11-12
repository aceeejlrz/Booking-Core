package com.ticketdrop.booking;

import java.util.List;
import java.util.UUID;

/**
 * The three operations that change a seat's state. The rules they follow are in section 6 of the
 * design spec (docs/specs).
 *
 * <p><b>What callers guarantee.</b> The event exists. {@code buyerId} is not null. {@code seatIds}
 * holds between 1 and the configured maximum of distinct, non-null values, in the order the client
 * sent them. Implementations do not re-check any of this.
 *
 * <p><b>What implementations must do.</b> Follow the rules of the spec, return seat IDs in
 * ascending order, and signal failure only with the exceptions named below. They know nothing
 * about HTTP.
 */
public interface BookingService {

    /**
     * Holds all of the seats for the buyer, or none of them.
     *
     * @throws SeatsNotFoundException    if any seat is not part of the event; nothing is held
     * @throws SeatsUnavailableException if any seat is not free; nothing is held
     */
    Hold hold(long eventId, UUID buyerId, List<Long> seatIds);

    /**
     * Frees the seats that are still held under this hold. Seats that were sold, or that another
     * hold has since taken, are left alone. An unknown, expired, released or bought hold is not an
     * error: the call simply changes nothing.
     */
    void release(UUID holdId);

    /**
     * Buys every seat of the hold, or none of them. Buying a hold that was already bought returns
     * the original order with {@code created = false}.
     *
     * @throws HoldNotFoundException  if no hold with this ID ever existed
     * @throws HoldNotActiveException if the hold has expired, was released, or has lost a seat
     */
    PurchaseResult buy(UUID holdId);
}
