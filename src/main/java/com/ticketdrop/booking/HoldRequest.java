package com.ticketdrop.booking;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of POST /api/events/{eventId}/holds. The upper limit on seats and the ban on duplicates
 * are checked in the controller, because the limit comes from a setting.
 */
public record HoldRequest(
        @NotNull UUID buyerId,
        @NotNull @Size(min = 1) List<@NotNull Long> seatIds) {
}
