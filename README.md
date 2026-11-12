# TicketDrop

An event-ticketing service built to prove one thing: it never sells the same seat twice, even when
hundreds of buyers go for it at the same instant. The proof lives in this repository: race tests, a
load test, and the numbers they produce.

**Status: work in progress.** Built so far: the data model, the events API, the booking API with
its contract tests, and the load-test harness. Still to come: the two booking strategies and the
race tests, then the first measured results. The design is in
[docs/specs](docs/specs/2026-11-11-booking-core-design.md).

## Run it

You need Docker.

```bash
docker compose up -d --build
```

The API is then on http://localhost:8080. Check it with `curl http://localhost:8080/actuator/health`
and stop it with `docker compose down -v`. Every start begins with an empty database.

> **The API has no logins yet.** Anyone who can reach it can create events and buy seats. Run it on
> your own machine only, and do not expose it publicly.

## Run the tests

You need JDK 25 or newer and a running Docker. The tests start a real PostgreSQL in a container.

```bash
./mvnw verify
```

On Windows PowerShell, use `.\mvnw.cmd verify`.

## Reproduce the bug

The repository keeps a deliberately unsafe "naive" booking strategy next to the safe one, so that
the double-booking bug can be reproduced on demand. This runs the race tests against it, and they
fail, with more than one buyer winning the same seat:

```bash
./mvnw test -Dgroups=race -Dticketdrop.booking-strategy=naive
```

In PowerShell the options need quotes:

```powershell
.\mvnw.cmd test "-Dgroups=race" "-Dticketdrop.booking-strategy=naive"
```

Without the strategy option, the same tests run against the safe strategy and pass.

## Run the load test

A simulated ticket drop: by default 2,000 buyers go for 500 seats within ten seconds. One command
starts a fresh stack, runs the drop with [k6](https://k6.io), and then verifies the result against
the database.

```bash
bash scripts/loadtest.sh
```

On Windows, run it from Git Bash. It ends with `VERIFICATION PASSED` or `VERIFICATION FAILED` and
writes the numbers to `load/results/report.txt`. The settings are environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `BUYERS` | 2000 | Number of simulated buyers |
| `ROWS`, `SEATS_PER_ROW` | 20, 25 | Size of the event |
| `BOOKING_STRATEGY` | atomic | `atomic` or `naive` |

For example, a smaller run against the unsafe strategy:

```bash
BUYERS=200 BOOKING_STRATEGY=naive bash scripts/loadtest.sh
```

What the verification checks:

- **V1-V4:** nothing in the database contradicts itself, such as an order with fewer sold seats
  than its hold took.
- **V5:** the seats buyers were told they bought are exactly the seats the database says were sold.
- **V6:** no buyer who was promised seats, and bought in time, was turned away.
- **HTTP:** the service never answered with status 500.

## Results

No runs recorded yet.

| Strategy | Buyers | Seats | Total requests | Peak requests/s | Hold p95/p99 (ms) | Buy p95/p99 (ms) | First hold to last order (s) | Holds answered 409 (%) | Broken promises | Verification |
|---|---|---|---|---|---|---|---|---|---|---|

Machine: _CPU and memory to be filled in with the first run._ Container limits: 2 CPUs and 1 GB of
memory each for the service and for PostgreSQL.
