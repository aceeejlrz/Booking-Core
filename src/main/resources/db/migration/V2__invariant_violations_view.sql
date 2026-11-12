-- A self-check for the booking invariants that constraints cannot express.
-- Every row is something that must never happen; an empty view means the data is consistent.
--
--   V2: every order has exactly as many SOLD seats pointing at it as its hold's seat_count.
--   V3: every SOLD seat points at an order for the same event.
--
-- V1 (a seat's columns agree with its status) and V4 (one order per hold) are enforced by
-- constraints in V1__schema.sql, so they cannot be violated and are not repeated here.

create view invariant_violations (event_id, invariant, detail) as
select o.event_id,
       'V2'::text,
       format('order %s has %s sold seats but its hold took %s', o.id, count(s.id), h.seat_count)
from orders o
join holds h on h.id = o.hold_id
left join seats s on s.order_id = o.id and s.status = 'SOLD'
group by o.id, o.event_id, h.seat_count
having count(s.id) <> h.seat_count

union all

select s.event_id,
       'V3'::text,
       format('seat %s of event %s is sold to order %s of event %s', s.id, s.event_id, o.id, o.event_id)
from seats s
join orders o on o.id = s.order_id
where s.status = 'SOLD'
  and o.event_id <> s.event_id;
