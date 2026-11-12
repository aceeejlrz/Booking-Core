package com.ticketdrop.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The body of POST /api/events. The sizes are wrapper types so that a missing one is caught. */
public record CreateEventRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(1) @Max(100) Integer rows,
        @NotNull @Min(1) @Max(100) Integer seatsPerRow) {
}
