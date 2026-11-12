package com.ticketdrop.booking;

import java.util.UUID;

/** No hold with this ID ever existed. */
public class HoldNotFoundException extends RuntimeException {

    private final UUID holdId;

    public HoldNotFoundException(UUID holdId) {
        super("There is no hold " + holdId);
        this.holdId = holdId;
    }

    public UUID holdId() {
        return holdId;
    }
}
