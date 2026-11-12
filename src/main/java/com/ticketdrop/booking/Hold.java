package com.ticketdrop.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A successful hold: these seats belong to this buyer until {@code expiresAt}. */
public record Hold(UUID holdId, long eventId, UUID buyerId, List<Long> seatIds, Instant expiresAt) {
}
