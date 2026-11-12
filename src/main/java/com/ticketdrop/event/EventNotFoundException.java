package com.ticketdrop.event;

public class EventNotFoundException extends RuntimeException {

    private final long eventId;

    public EventNotFoundException(long eventId) {
        super("There is no event " + eventId);
        this.eventId = eventId;
    }

    public long eventId() {
        return eventId;
    }
}
