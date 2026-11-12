package com.ticketdrop.event;

/** Seat counts by effective status, plus the number of orders, for one event. */
public record EventSummary(long eventId, int seatCount, int available, int held, int sold, int orders) {
}
