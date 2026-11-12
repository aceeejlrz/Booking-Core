package com.ticketdrop.booking;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** The body of POST /api/orders. */
public record BuyRequest(@NotNull UUID holdId) {
}
