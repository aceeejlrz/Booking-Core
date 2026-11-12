package com.ticketdrop.event;

/**
 * A seat as the API reports it. {@code status} is the effective status: a seat whose hold has
 * expired is reported as AVAILABLE, whatever its row still says.
 */
public record SeatView(long id, int row, int number, String status) {
}
