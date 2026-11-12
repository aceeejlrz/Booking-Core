-- Data model of the booking core.
-- Design: docs/specs/2026-11-11-booking-core-design.md, section 5.

create table events (
    id            bigint generated always as identity primary key,
    name          text        not null,
    row_count     integer     not null check (row_count between 1 and 100),
    seats_per_row integer     not null check (seats_per_row between 1 and 100),
    created_at    timestamptz not null default now()
);

-- One row per successful hold. Never updated or deleted.
create table holds (
    id         uuid        primary key,
    event_id   bigint      not null references events (id),
    buyer_id   uuid        not null,
    seat_count integer     not null check (seat_count >= 1),
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

create table orders (
    id         uuid        primary key,
    event_id   bigint      not null references events (id),
    buyer_id   uuid        not null,
    hold_id    uuid        not null references holds (id),
    created_at timestamptz not null default now(),
    -- A hold can produce at most one order, whatever the application code does (invariant V4).
    constraint orders_one_per_hold unique (hold_id)
);

create table seats (
    id              bigint generated always as identity primary key,
    event_id        bigint      not null references events (id),
    row_no          integer     not null check (row_no >= 1),
    seat_no         integer     not null check (seat_no >= 1),
    status          text        not null default 'AVAILABLE',
    hold_id         uuid        references holds (id),
    -- A copy of the hold's expires_at, so "is this seat free?" can be answered from this row alone.
    hold_expires_at timestamptz,
    order_id        uuid        references orders (id),
    constraint seats_position_unique unique (event_id, row_no, seat_no),
    -- The other columns must agree with the status, so an impossible seat cannot be stored
    -- (invariant V1). A status outside the three names matches no branch and is rejected too.
    constraint seats_columns_agree_with_status check (
           (status = 'AVAILABLE' and hold_id is null     and hold_expires_at is null     and order_id is null)
        or (status = 'HELD'      and hold_id is not null and hold_expires_at is not null and order_id is null)
        or (status = 'SOLD'      and hold_id is null     and hold_expires_at is null     and order_id is not null)
    )
);

create index seats_hold_idx  on seats (hold_id)  where hold_id  is not null;
create index seats_order_idx on seats (order_id) where order_id is not null;
