package com.ticketdrop.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A completed purchase: the seats of one hold, sold to its buyer. */
public record Order(UUID orderId, long eventId, UUID buyerId, UUID holdId, List<Long> seatIds, Instant createdAt) {
}
