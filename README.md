# TicketDrop

An event-ticketing service built to prove one thing: it never sells the same seat twice, even when
hundreds of buyers go for it at the same instant. The proof lives in this repository: race tests, a
load test, and the numbers they produce.

**Status: work in progress.** The booking core is being built piece by piece. The design is in
[docs/specs](docs/specs/2026-11-11-booking-core-design.md).

## Run the tests

You need JDK 25 or newer and a running Docker. The tests start a real PostgreSQL in a container.

```bash
./mvnw verify
```

On Windows PowerShell, use `.\mvnw.cmd verify`.
