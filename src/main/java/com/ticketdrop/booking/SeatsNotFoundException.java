package com.ticketdrop.booking;

import java.util.List;

/** A hold named seats that are not part of the event. */
public class SeatsNotFoundException extends RuntimeException {

    private final List<Long> unknownSeatIds;

    public SeatsNotFoundException(List<Long> unknownSeatIds) {
        super("Seats not part of the event: " + unknownSeatIds);
        this.unknownSeatIds = List.copyOf(unknownSeatIds);
    }

    public List<Long> unknownSeatIds() {
        return unknownSeatIds;
    }
}
