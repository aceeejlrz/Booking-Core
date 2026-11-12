package com.ticketdrop.booking;

import java.util.List;

/** A hold named seats that are not free. Nothing was held. */
public class SeatsUnavailableException extends RuntimeException {

    private final List<Long> unavailableSeatIds;

    public SeatsUnavailableException(List<Long> unavailableSeatIds) {
        super("Seats not available: " + unavailableSeatIds);
        this.unavailableSeatIds = List.copyOf(unavailableSeatIds);
    }

    public List<Long> unavailableSeatIds() {
        return unavailableSeatIds;
    }
}
