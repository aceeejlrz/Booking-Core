package com.ticketdrop.booking;

import java.util.UUID;

/** The hold exists but can no longer be bought: it expired, was released, or lost a seat. */
public class HoldNotActiveException extends RuntimeException {

    private final UUID holdId;

    public HoldNotActiveException(UUID holdId) {
        super("Hold " + holdId + " is no longer active");
        this.holdId = holdId;
    }

    public UUID holdId() {
        return holdId;
    }
}
