package com.ticketdrop.config;

/** Which implementation of the booking rules the service runs. */
public enum BookingStrategy {

    /** The safe implementation. The default. */
    ATOMIC,

    /** A deliberately unsafe baseline that double-books under load. For demonstration only. */
    NAIVE
}
