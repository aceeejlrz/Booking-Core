package com.ticketdrop.support;

/** One seat as the seat listing reports it. */
public record SeatRow(long id, int row, int number, String status) {
}
