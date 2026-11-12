package com.ticketdrop.event;

/** An event and the size of its rectangular block of seats. */
public record Event(long id, String name, int rows, int seatsPerRow, int seatCount) {
}
