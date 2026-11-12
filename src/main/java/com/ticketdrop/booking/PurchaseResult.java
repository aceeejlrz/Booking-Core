package com.ticketdrop.booking;

/**
 * The outcome of buying a hold.
 *
 * @param order   the order for the hold
 * @param created true if this call created the order; false if the hold had already been bought
 *                and this is the original order being returned again
 */
public record PurchaseResult(Order order, boolean created) {
}
