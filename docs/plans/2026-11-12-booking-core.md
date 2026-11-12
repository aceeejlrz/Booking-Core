# Booking Core Implementation Plan

**Goal:** Build the TicketDrop booking core: a Spring Boot service that holds and sells seats, with tests and a load test that prove no seat is ever sold twice.

**Architecture:** One Spring Boot service over PostgreSQL. HTTP endpoints validate input and delegate to a `BookingService` interface with two implementations chosen by a setting: a deliberately unsafe naive one and the safe atomic one. Correctness is proven three ways: contract tests that both implementations pass, race tests that only the atomic one passes, and a k6 load test followed by a verification script.

**Tech Stack:** Java 25, Spring Boot 4.1.2 (Web MVC, JDBC `JdbcClient`, Validation, Flyway, Actuator), PostgreSQL 18, Maven wrapper, JUnit and AssertJ, Testcontainers, k6 2.3.0, Docker Compose, GitHub Actions.

**Spec:** `docs/specs/2026-11-11-booking-core-design.md`. Section marks (§) below refer to it. Read it before starting any task.

## Global Constraints

- Java language level 25 (`<java.version>25</java.version>`). The build must also run on a local JDK 27.
- Spring Boot 4.1.2 through `spring-boot-starter-parent`. Dependencies are exactly what Spring Initializr generates for `web,jdbc,postgresql,flyway,validation,actuator,testcontainers`: `spring-boot-starter-webmvc`, `-jdbc`, `-validation`, `-flyway`, `-actuator`, their `-test` counterparts, `flyway-database-postgresql`, `postgresql`, `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`. Add nothing else.
- PostgreSQL 18 everywhere: image `postgres:18` in Testcontainers and in Compose.
- Database access is plain SQL through `JdbcClient`. No JPA, no Spring Data repositories.
- Tests run against a real PostgreSQL. No H2, no mocked database, no Mockito.
- Every expiry decision is made in SQL against PostgreSQL's `now()`. Java code never compares a hold's expiry with its own clock.
- Base package `com.ticketdrop`. Maven coordinates `com.ticketdrop:ticketdrop`.
- Settings and defaults: `ticketdrop.hold-duration` = `5m`, `ticketdrop.max-seats-per-hold` = `4`, `ticketdrop.booking-strategy` = `atomic` (the only other value is `naive`). The naive strategy is never the default.
- Error bodies are `{ "error": CODE, "message": text }` plus the extra field §7 names for that code. Tests assert on `error` and the extra field, never on the wording of `message`.
- Timestamps in responses are ISO-8601 in UTC ending in `Z`. `seatIds` arrays in responses are in ascending order (a plan decision; the spec's examples already show it).
- `main` always passes `./mvnw verify`. Each task is reviewed before it lands there.
- CI uses `actions/checkout@v7` and `actions/setup-java@v6` with Temurin 25.
- Task 1 needs one download: the Initializr starter archive.

## Review Focus

Conditions the spec implies that are most likely to bite someone using this software. Each is pinned by a test or a verification step in the task named.

1. **A hold names a seat that exists but belongs to another event.** Expected: 404 `SEAT_NOT_FOUND` listing it, and that seat is untouched. Pinned in Task 3, `seatFromAnotherEventIs404AndIsNotHeld`.
2. **A malformed request: no body, wrong JSON types, a non-numeric event ID, a non-UUID hold ID.** Expected: 400 `VALIDATION_FAILED` in the spec's error format, never a 500 or Spring's default error body. Pinned in Task 2, `malformedRequestsAre400`, and Task 3, `invalidHoldBodiesAre400` and `releaseWithANonUuidIs400`.
3. **The machine's timezone is not UTC, as on the development laptop.** Expected: `expiresAt` and `createdAt` are still the right instants. Pinned in Task 1 (the test JVM runs in `Asia/Kolkata`) and Task 3 (`holdReturns201WithTheHold` compares `expiresAt` with the database clock read as epoch seconds).
4. **`docker compose up` on a cold machine, where PostgreSQL is slower to accept connections than the service is to start.** Expected: the service waits and comes up healthy with no manual restart. Pinned in Task 8, Step 3.
5. **The load test is run from Git Bash on Windows, which rewrites arguments that look like Unix paths.** Expected: the runner works unchanged on Windows and Linux. Pinned in Task 8, Steps 6 and 7.

---

## Working through the plan

- Tasks 4, 6, 7 and 9 state the interface, the rules and the tests that must pass, and deliberately contain no solution code. Working the solution out is the point of them.
- Every task is reviewed against the spec before it lands on `main`.
- Tasks 4 and 5 can run at the same time. Task 8 can be written while Tasks 6 and 7 are in progress, but its Step 7 needs Task 7 finished.

A rough schedule for the two weeks: Tasks 1-5 in week one, Tasks 6-9 in week two.

## File Structure

```
pom.xml, mvnw, mvnw.cmd, .mvn/         Build (from Spring Initializr)
.gitignore, .gitattributes             LF line endings for mvnw, *.sh and load/*.js
README.md
Dockerfile, .dockerignore, compose.yaml
.github/workflows/ci.yml
load/drop.js                           k6 scenario
load/results/                          Run output, git-ignored
scripts/loadtest.sh                    Runs a whole load test and verifies it
scripts/verify.sh                      Checks V1-V6 after a run
docs/evidence/                         Saved output of the failing naive runs

src/main/java/com/ticketdrop/
  TicketDropApplication.java
  config/BookingProperties.java        The three settings
  config/BookingStrategy.java          enum ATOMIC, NAIVE
  event/                               EventController, EventService, CreateEventRequest,
                                       Event, SeatView, EventSummary, EventNotFoundException
  booking/                             BookingService, Hold, Order, PurchaseResult,
                                       SeatsNotFoundException, SeatsUnavailableException,
                                       HoldNotFoundException, HoldNotActiveException,
                                       HoldController, OrderController, HoldRequest, BuyRequest,
                                       AtomicBookingService, NaiveBookingService
  web/ApiExceptionHandler.java         Every failure -> the error format of §7
src/main/resources/
  application.yaml
  db/migration/V1__schema.sql
  db/migration/V2__invariant_violations_view.sql

src/test/java/com/ticketdrop/
  support/                             IntegrationTest, TestSupportConfig, ApiClient, ApiResponse,
                                       SeatRow, TestData, FakeBookingConfig, FakeBookingService,
                                       ConcurrentCalls, Invariants
  SmokeTest.java, SchemaTest.java
  config/BookingPropertiesTest.java
  event/EventApiTest.java
  booking/BookingEndpointsTest.java, MaxSeatsSettingTest.java
  booking/BookingApiContract.java      Abstract: the behaviour both strategies must have
  booking/NaiveBookingApiTest.java, AtomicBookingApiTest.java
  booking/BookingRaceTest.java         R1-R5
  support/ConcurrentCallsTest.java, InvariantsTest.java
```

## Prerequisites

- [ ] Docker Desktop is installed and running.
- [ ] Choose the folder the project will live in.
- [ ] Create an empty GitHub repository (no README, no licence) and add it as the `origin` remote.

---

### Task 1: Skeleton, schema, settings and CI

Branch `task-1-skeleton`.

**Files:**
- Create: `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/` (generated), `.gitignore`, `.gitattributes`, `README.md`
- Create: `src/main/java/com/ticketdrop/TicketDropApplication.java`
- Create: `src/main/java/com/ticketdrop/config/BookingProperties.java`, `BookingStrategy.java`
- Create: `src/main/resources/application.yaml`
- Create: `src/main/resources/db/migration/V1__schema.sql`, `V2__invariant_violations_view.sql`
- Create: `src/test/java/com/ticketdrop/support/IntegrationTest.java`, `TestSupportConfig.java`, `ApiClient.java`, `ApiResponse.java`, `SeatRow.java`
- Test: `src/test/java/com/ticketdrop/SmokeTest.java`, `SchemaTest.java`, `config/BookingPropertiesTest.java`
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `@IntegrationTest`: a composed annotation equal to `@SpringBootTest(webEnvironment = RANDOM_PORT)` plus `@Import(TestSupportConfig.class)`.
  - `TestSupportConfig`: one `postgres:18` Testcontainers container wired with `@ServiceConnection` and shared by the whole test run, plus a lazy `ApiClient` bean built from `${local.server.port}`. Tests get everything by `@Autowired`.
  - `ApiClient`: thread-safe, built on the JDK `HttpClient`. `ApiResponse send(String method, String path, String jsonBody)` (`jsonBody` may be null). It never throws on a 4xx or 5xx.
  - `record ApiResponse(int status, String rawBody)` with JSON accessors `String text(String field)`, `int intValue(String field)`, `long longValue(String field)`, `UUID uuid(String field)`, `Instant instant(String field)`, `List<Long> longs(String field)`, `List<SeatRow> seats()`.
  - `record SeatRow(long id, int row, int number, String status)`.
  - `record BookingProperties(Duration holdDuration, int maxSeatsPerHold, BookingStrategy bookingStrategy)` bound to prefix `ticketdrop`; `enum BookingStrategy { ATOMIC, NAIVE }`.
  - Tables `events`, `holds`, `orders`, `seats` exactly as §5. View `invariant_violations(event_id bigint, invariant text, detail text)`: one row per violation of V2 or V3, with `invariant` set to `'V2'` or `'V3'`. `event_id` is the order's event for V2 and the seat's event for V3.
  - The test JVM's default timezone is `Asia/Kolkata`.

V1 and V4 cannot be violated while the §5 constraints exist, so the view does not repeat them. `SchemaTest` proves the constraints reject them.

- [ ] **Step 1: Generate the project**

Initialise the repository with `main` as its only branch, commit the spec and this plan to it (`docs: booking core spec and plan`), push it, and create the branch `task-1-skeleton`.

Then download `https://start.spring.io/starter.zip` with `type=maven-project`, `language=java`, `bootVersion=4.1.2`, `groupId=com.ticketdrop`, `artifactId=ticketdrop`, `name=TicketDrop`, `packageName=com.ticketdrop`, `javaVersion=25`, `dependencies=web,jdbc,postgresql,flyway,validation,actuator,testcontainers`. Unpack it into the repository root. Delete the generated test classes; `TestSupportConfig` replaces the generated Testcontainers configuration.

Run: `./mvnw -v`
Expected: Maven prints its version and `Java version: 27`. If Maven fails to start on JDK 27, stop and install JDK 25 alongside it (§13).

- [ ] **Step 2: Add line-ending and ignore rules**

`.gitattributes`: `* text=auto`, and `eol=lf` for `mvnw`, `*.sh`, `load/*.js`; `eol=crlf` for `*.cmd`. `.gitignore`: add `load/results/` to the generated file.

- [ ] **Step 3: Pin the test JVM timezone**

In `pom.xml`, configure `maven-surefire-plugin` with `<argLine>-Duser.timezone=Asia/Kolkata</argLine>`.

- [ ] **Step 4: Write the failing tests**

```java
@IntegrationTest
class SmokeTest {
    @Autowired ApiClient api;

    @Test void serviceStartsAndReportsHealthy() {
        ApiResponse r = api.send("GET", "/actuator/health", null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.text("status")).isEqualTo("UP");
    }

    @Test void testsRunOutsideUtc() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Kolkata");
    }
}
```

```java
class BookingPropertiesTest {
    // EnableProps is a nested @Configuration with @EnableConfigurationProperties(BookingProperties.class)
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(EnableProps.class);

    @Test void defaults() {
        runner.run(context -> {
            BookingProperties props = context.getBean(BookingProperties.class);
            assertThat(props.holdDuration()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.maxSeatsPerHold()).isEqualTo(4);
            assertThat(props.bookingStrategy()).isEqualTo(BookingStrategy.ATOMIC);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ticketdrop.hold-duration=0s", "ticketdrop.hold-duration=-1m",
        "ticketdrop.max-seats-per-hold=0", "ticketdrop.booking-strategy=fast" })
    void invalidSettingsStopStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
```

```java
@IntegrationTest
@Transactional   // each test's rows are rolled back
class SchemaTest {
    @Autowired JdbcClient jdbc;
    // private helpers insert an event, a hold, an order and seats with plain SQL

    @Test void heldSeatMustCarryAHold() {
        long event = insertEvent(1, 1);
        assertThatThrownBy(() -> jdbc.sql(
                "insert into seats (event_id, row_no, seat_no, status) values (:e, 1, 1, 'HELD')")
            .param("e", event).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    // The next five have the same shape: set up valid rows, then one statement that must be rejected.
    @Test void soldSeatMustCarryAnOrder()          { /* a seat with status 'SOLD' and order_id null -> DataIntegrityViolationException */ }
    @Test void availableSeatCannotCarryAHold()     { /* a seat with status 'AVAILABLE' and hold_id set -> DataIntegrityViolationException */ }
    @Test void seatPositionIsUniqueWithinAnEvent() { /* the same event_id, row_no, seat_no twice -> DuplicateKeyException */ }
    @Test void eventSizeIsLimitedToOneHundred()    { /* an event with row_count 101 -> DataIntegrityViolationException */ }
    @Test void aHoldCanHaveOnlyOneOrder()          { /* two orders with the same hold_id -> DuplicateKeyException */ }

    @Test void viewIsEmptyForAConsistentSale() {
        // event, 2-seat hold, order, both seats SOLD to it
        assertThat(violations(eventId)).isEmpty();
    }
    @Test void viewReportsAnOrderWithTooFewSeats() {
        // event, hold with seat_count 2, order, only ONE seat SOLD to it
        assertThat(violations(eventId)).containsExactly("V2");
    }
    @Test void viewReportsASoldSeatWhoseOrderIsForAnotherEvent() {
        // events A and B; a seat of A is SOLD to an order whose event_id is B
        assertThat(violations(eventA)).contains("V3");
    }
    // violations(id): select invariant from invariant_violations where event_id = :id
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./mvnw test`
Expected: FAIL at test compilation (`IntegrationTest`, `ApiClient` and `BookingProperties` do not exist).

- [ ] **Step 6: Write the test support classes**

`IntegrationTest`, `TestSupportConfig`, `ApiClient`, `ApiResponse` and `SeatRow`, as described under Interfaces.

- [ ] **Step 7: Write the two migrations**

`V1__schema.sql`: the four tables, constraints and indexes of §5, including the 1-100 limits on `row_count` and `seats_per_row`. `V2__invariant_violations_view.sql`: the view described under Interfaces.

- [ ] **Step 8: Write `BookingProperties`, `BookingStrategy` and `application.yaml`**

Defaults live on the record with `@DefaultValue`, so they hold even with an empty configuration. The record rejects a non-positive `holdDuration` and a `maxSeatsPerHold` below 1. `application.yaml`: application name `ticketdrop`; datasource `${DB_URL:jdbc:postgresql://localhost:5432/ticketdrop}`, user `${DB_USER:ticketdrop}`, password `${DB_PASSWORD:ticketdrop}` (local development values); Actuator exposes only `health`.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, all tests in `SmokeTest`, `SchemaTest` and `BookingPropertiesTest` pass.

- [ ] **Step 10: Add CI and a README stub**

`ci.yml`: runs on every push and pull request; one job on `ubuntu-latest`; `actions/checkout@v7`, `actions/setup-java@v6` (Temurin 25, Maven cache), then `./mvnw -B verify`. `README.md`: the project name, two sentences on what it is, "work in progress", and how to run the tests.

- [ ] **Step 11: Commit, push and check CI**

```bash
git update-index --chmod=+x mvnw      # Windows does not record the executable bit; CI needs it
git add -A
git commit -m "chore: project skeleton, schema, settings and CI"
git push -u origin task-1-skeleton
```

Expected: the CI run for the branch is green on GitHub. Then review and merge into `main`.

---

### Task 2: Events and the error format

Branch `task-2-events`.

**Files:**
- Create: `src/main/java/com/ticketdrop/event/` (`EventController`, `EventService`, `CreateEventRequest`, `Event`, `SeatView`, `EventSummary`, `EventNotFoundException`)
- Create: `src/main/java/com/ticketdrop/web/ApiExceptionHandler.java`
- Create: `src/test/java/com/ticketdrop/support/TestData.java`
- Modify: `src/test/java/com/ticketdrop/support/ApiClient.java`, `TestSupportConfig.java`
- Test: `src/test/java/com/ticketdrop/event/EventApiTest.java`

**Interfaces:**
- Consumes: `@IntegrationTest`, `ApiClient.send`, `ApiResponse`, `SeatRow` (Task 1).
- Produces:
  - `EventService`: `Event create(String name, int rows, int seatsPerRow)`, `void requireEvent(long eventId)`, `List<SeatView> seats(long eventId)`, `EventSummary summary(long eventId)`. The last three throw `EventNotFoundException` for an unknown event.
  - `record Event(long id, String name, int rows, int seatsPerRow, int seatCount)`, `record SeatView(long id, int row, int number, String status)`, `record EventSummary(long eventId, int seatCount, int available, int held, int sold, int orders)`. `status` and the counts use effective status (§6).
  - `ApiExceptionHandler`: `EventNotFoundException` -> 404 `EVENT_NOT_FOUND`. Unreadable or missing body, failed bean validation, and a path variable of the wrong type -> 400 `VALIDATION_FAILED`. Any other exception -> 500 `INTERNAL_ERROR` with a fixed message and nothing from the exception.
  - `ApiClient`: `ApiResponse createEvent(String name, int rows, int seatsPerRow)`, `ApiResponse listSeats(long eventId)`, `ApiResponse summary(long eventId)`.
  - `TestData` (a lazy bean in `TestSupportConfig`): `Instant dbNow()` (PostgreSQL's clock read as epoch seconds, so no timezone conversion is involved), `void markHeld(long seatId, Duration expiresFromNow)` (creates a 1-seat hold; a negative duration means already expired), `void markSold(long seatId)` (creates a 1-seat hold and its order, and marks the seat `SOLD`).

- [ ] **Step 1: Write the failing tests**

```java
@IntegrationTest
class EventApiTest {
    @Autowired ApiClient api;
    @Autowired TestData data;

    @Test void createsAnEventWithAllItsSeats() {
        ApiResponse r = api.createEvent("Launch Night", 20, 25);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.text("name")).isEqualTo("Launch Night");
        assertThat(r.intValue("rows")).isEqualTo(20);
        assertThat(r.intValue("seatsPerRow")).isEqualTo(25);
        assertThat(r.intValue("seatCount")).isEqualTo(500);
        assertThat(api.listSeats(r.longValue("id")).seats()).hasSize(500);
    }

    @Test void listsSeatsByRowThenNumberAllAvailable() {
        long event = api.createEvent("E", 2, 3).longValue("id");
        List<SeatRow> seats = api.listSeats(event).seats();
        assertThat(seats).extracting(SeatRow::row, SeatRow::number).containsExactly(
            tuple(1, 1), tuple(1, 2), tuple(1, 3), tuple(2, 1), tuple(2, 2), tuple(2, 3));
        assertThat(seats).extracting(SeatRow::status).containsOnly("AVAILABLE");
    }

    @ParameterizedTest
    @CsvSource({ "0,5", "101,5", "5,0", "5,101" })
    void rejectsSizesOutsideOneToOneHundred(int rows, int seatsPerRow) {
        ApiResponse r = api.createEvent("E", rows, seatsPerRow);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
    }

    @Test void rejectsBlankAndOverlongNames() {
        assertThat(api.createEvent("", 1, 1).status()).isEqualTo(400);
        assertThat(api.createEvent("x".repeat(101), 1, 1).status()).isEqualTo(400);
        assertThat(api.createEvent("x".repeat(100), 1, 1).status()).isEqualTo(201);
    }

    @Test @Timeout(10) void acceptsTheLargestEvent() {
        ApiResponse r = api.createEvent("Big", 100, 100);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.intValue("seatCount")).isEqualTo(10_000);
    }

    @Test void unknownEventIs404() {
        assertThat(api.listSeats(999_999_999L).status()).isEqualTo(404);
        assertThat(api.listSeats(999_999_999L).text("error")).isEqualTo("EVENT_NOT_FOUND");
        assertThat(api.summary(999_999_999L).text("error")).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test void reportsEffectiveStatus() {
        long event = api.createEvent("E", 1, 4).longValue("id");
        List<SeatRow> s = api.listSeats(event).seats();
        data.markHeld(s.get(0).id(), Duration.ofMinutes(5));    // active hold
        data.markHeld(s.get(1).id(), Duration.ofSeconds(-1));   // expired hold
        data.markSold(s.get(2).id());
        assertThat(api.listSeats(event).seats()).extracting(SeatRow::status)
            .containsExactly("HELD", "AVAILABLE", "SOLD", "AVAILABLE");
        ApiResponse summary = api.summary(event);
        assertThat(summary.intValue("seatCount")).isEqualTo(4);
        assertThat(summary.intValue("available")).isEqualTo(2);
        assertThat(summary.intValue("held")).isEqualTo(1);
        assertThat(summary.intValue("sold")).isEqualTo(1);
        assertThat(summary.intValue("orders")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{not json", "{\"name\":\"E\",\"rows\":\"many\",\"seatsPerRow\":5}", "{}" })
    void malformedRequestsAre400(String body) {
        ApiResponse r = api.send("POST", "/api/events", body);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
    }

    @Test void missingBodyAndNonNumericIdAre400() {
        assertThat(api.send("POST", "/api/events", null).text("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(api.send("GET", "/api/events/abc/seats", null).status()).isEqualTo(400);
        assertThat(api.send("GET", "/api/events/abc/seats", null).text("error")).isEqualTo("VALIDATION_FAILED");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=EventApiTest`
Expected: FAIL to compile (`createEvent`, `TestData` do not exist).

- [ ] **Step 3: Write `TestData` and the three `ApiClient` methods**

- [ ] **Step 4: Write `EventService`**

`create` inserts the event and then all of its seats with one set-based statement (`generate_series`), in one transaction. Effective status is computed in SQL from `status`, `hold_expires_at` and `now()`.

- [ ] **Step 5: Write `EventController`, `CreateEventRequest` and `ApiExceptionHandler`**

Routes and bodies as §7. `CreateEventRequest(String name, Integer rows, Integer seatsPerRow)` carries the bean-validation rules: name 1-100 characters and not blank, both sizes 1-100.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: events, seat listing, summary and the API error format"
git push -u origin task-2-events
```

Then review and merge.

---

### Task 3: Booking API surface and the contract tests

Branch `task-3-booking-api`.

**Files:**
- Create: `src/main/java/com/ticketdrop/booking/` (`BookingService`, `Hold`, `Order`, `PurchaseResult`, `SeatsNotFoundException`, `SeatsUnavailableException`, `HoldNotFoundException`, `HoldNotActiveException`, `HoldController`, `OrderController`, `HoldRequest`, `BuyRequest`, `AtomicBookingService`)
- Modify: `src/main/java/com/ticketdrop/web/ApiExceptionHandler.java`
- Create: `src/test/java/com/ticketdrop/support/FakeBookingService.java`, `FakeBookingConfig.java`
- Modify: `src/test/java/com/ticketdrop/support/ApiClient.java`, `TestData.java`
- Test: `src/test/java/com/ticketdrop/booking/BookingEndpointsTest.java`, `MaxSeatsSettingTest.java`
- Create: `src/test/java/com/ticketdrop/booking/BookingApiContract.java`

**Interfaces:**
- Consumes: `EventService.requireEvent`, `ApiExceptionHandler`, `BookingProperties`, the test support from Tasks 1 and 2.
- Produces:

```java
public interface BookingService {
    Hold hold(long eventId, UUID buyerId, List<Long> seatIds);
    void release(UUID holdId);
    PurchaseResult buy(UUID holdId);
}
public record Hold(UUID holdId, long eventId, UUID buyerId, List<Long> seatIds, Instant expiresAt) {}
public record Order(UUID orderId, long eventId, UUID buyerId, UUID holdId, List<Long> seatIds, Instant createdAt) {}
public record PurchaseResult(Order order, boolean created) {}   // created = false means a repeat
```

  - Exceptions, all unchecked: `SeatsNotFoundException(List<Long> unknownSeatIds)`, `SeatsUnavailableException(List<Long> unavailableSeatIds)`, `HoldNotFoundException(UUID holdId)`, `HoldNotActiveException(UUID holdId)`.
  - **What the endpoints guarantee to every `BookingService`:** the event exists; `buyerId` is not null; `seatIds` holds between 1 and `maxSeatsPerHold` distinct, non-null values in the order the client sent them. Implementations do not re-check these.
  - **What every `BookingService` must do:** follow §6, return `seatIds` ascending, and signal failures only with the four exceptions above.
  - Handler mapping added: `SeatsNotFoundException` -> 404 `SEAT_NOT_FOUND` + `unknownSeatIds`; `SeatsUnavailableException` -> 409 `SEATS_UNAVAILABLE` + `unavailableSeatIds`; `HoldNotFoundException` -> 404 `HOLD_NOT_FOUND`; `HoldNotActiveException` -> 410 `HOLD_NOT_ACTIVE`; Spring's `ConcurrencyFailureException` -> 503 `TRY_AGAIN`.
  - `AtomicBookingService`: a `@Service` with `@ConditionalOnProperty(name = "ticketdrop.booking-strategy", havingValue = "atomic", matchIfMissing = true)` whose three methods throw `UnsupportedOperationException("Implemented in Task 7")`. The bodies are replaced in Task 7.
  - `ApiClient`: `ApiResponse hold(long eventId, UUID buyerId, List<Long> seatIds)`, `ApiResponse release(UUID holdId)`, `ApiResponse buy(UUID holdId)`.
  - `TestData`: `void expireHold(UUID holdId)` (sets the hold's `expires_at` and its seats' `hold_expires_at` one second into the past), `void stealSeat(long seatId)` (creates a fresh 1-seat hold for a random buyer, expiring in 5 minutes, and points the seat at it).
  - `FakeBookingService implements BookingService` with `holdReturns(Hold)`, `holdThrows(RuntimeException)`, `buyReturns(PurchaseResult)`, `buyThrows(RuntimeException)`, `List<String> calls()` (`"hold"`, `"release"`, `"buy"` in order), `List<Long> lastHoldSeatIds()`, `UUID lastReleasedHoldId()`, `void reset()`. `FakeBookingConfig` registers it as the `@Primary` `BookingService`.
  - `BookingApiContract`: an abstract `@IntegrationTest` class. A strategy is tested by subclassing it.

- [ ] **Step 1: Write the failing endpoint tests**

```java
@IntegrationTest
@Import(FakeBookingConfig.class)
class BookingEndpointsTest {
    static final UUID BUYER = UUID.fromString("6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11");
    static final UUID HOLD_ID = UUID.fromString("b3e1f0a2-58c4-4d0e-8f6a-2c9d7e4b1a30");
    static final UUID ORDER_ID = UUID.fromString("0a7d5c1e-9f42-4b8a-b1d3-6e2c4f8a9b07");
    @Autowired ApiClient api;
    @Autowired FakeBookingService fake;
    long event;   // @BeforeEach: fake.reset(); event = api.createEvent("E", 1, 4).longValue("id");

    @Test void holdIs201WithTheHoldAsJson() {
        fake.holdReturns(new Hold(HOLD_ID, event, BUYER, List.of(11L, 12L), Instant.parse("2026-11-11T18:35:00Z")));
        ApiResponse r = api.hold(event, BUYER, List.of(12L, 11L));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.uuid("holdId")).isEqualTo(HOLD_ID);
        assertThat(r.longValue("eventId")).isEqualTo(event);
        assertThat(r.uuid("buyerId")).isEqualTo(BUYER);
        assertThat(r.longs("seatIds")).containsExactly(11L, 12L);
        assertThat(r.rawBody()).contains("\"expiresAt\":\"2026-11-11T18:35:00Z\"");
        assertThat(fake.lastHoldSeatIds()).containsExactly(12L, 11L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"buyerId\":\"not-a-uuid\",\"seatIds\":[1]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\"}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[1,2,3,4,5]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[1,1]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[1,null]}",
        "{\"buyerId\":\"6f1c2a9e-3b7d-4c55-9a40-0d2f8e1b7c11\",\"seatIds\":[\"a\"]}",
        "not json" })
    void invalidHoldBodiesAre400(String body) {
        ApiResponse r = api.send("POST", "/api/events/" + event + "/holds", body);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(fake.calls()).isEmpty();
    }

    @Test void holdOnAnUnknownEventIs404AndNeverReachesTheService() {
        ApiResponse r = api.hold(999_999_999L, BUYER, List.of(1L));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("error")).isEqualTo("EVENT_NOT_FOUND");
        assertThat(fake.calls()).isEmpty();
    }

    @Test void invalidInputIsRejectedBeforeAnUnknownEvent() {
        ApiResponse r = api.hold(999_999_999L, BUYER, List.of(1L, 2L, 3L, 4L, 5L));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
    }

    @Test void unknownSeatsAre404WithTheirIds() {
        fake.holdThrows(new SeatsNotFoundException(List.of(7L)));
        ApiResponse r = api.hold(event, BUYER, List.of(7L));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("error")).isEqualTo("SEAT_NOT_FOUND");
        assertThat(r.longs("unknownSeatIds")).containsExactly(7L);
    }

    @Test void unavailableSeatsAre409WithTheirIds() {
        fake.holdThrows(new SeatsUnavailableException(List.of(3L, 4L)));
        ApiResponse r = api.hold(event, BUYER, List.of(3L, 4L));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("error")).isEqualTo("SEATS_UNAVAILABLE");
        assertThat(r.longs("unavailableSeatIds")).containsExactly(3L, 4L);
    }

    @Test void releaseIs204AndPassesTheHoldId() {
        assertThat(api.release(HOLD_ID).status()).isEqualTo(204);
        assertThat(fake.lastReleasedHoldId()).isEqualTo(HOLD_ID);
    }

    @Test void releaseWithANonUuidIs400() {
        ApiResponse r = api.send("DELETE", "/api/holds/not-a-uuid", null);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(fake.calls()).isEmpty();
    }

    @Test void firstBuyIs201AndARepeatIs200WithTheSameBody() {
        Order order = new Order(ORDER_ID, event, BUYER, HOLD_ID, List.of(11L, 12L), Instant.parse("2026-11-11T18:31:12Z"));
        fake.buyReturns(new PurchaseResult(order, true));
        ApiResponse first = api.buy(HOLD_ID);
        fake.buyReturns(new PurchaseResult(order, false));
        ApiResponse repeat = api.buy(HOLD_ID);
        assertThat(first.status()).isEqualTo(201);
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.rawBody()).isEqualTo(first.rawBody());
        assertThat(first.uuid("orderId")).isEqualTo(ORDER_ID);
        assertThat(first.uuid("holdId")).isEqualTo(HOLD_ID);
        assertThat(first.uuid("buyerId")).isEqualTo(BUYER);
        assertThat(first.longValue("eventId")).isEqualTo(event);
        assertThat(first.longs("seatIds")).containsExactly(11L, 12L);
        assertThat(first.rawBody()).contains("\"createdAt\":\"2026-11-11T18:31:12Z\"");
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"holdId\":\"nope\"}", "not json" })
    void invalidBuyBodiesAre400(String body) {
        ApiResponse r = api.send("POST", "/api/orders", body);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("error")).isEqualTo("VALIDATION_FAILED");
        assertThat(fake.calls()).isEmpty();
    }

    @Test void buyFailuresMapToTheirStatuses() {
        fake.buyThrows(new HoldNotFoundException(HOLD_ID));
        assertThat(api.buy(HOLD_ID).status()).isEqualTo(404);
        assertThat(api.buy(HOLD_ID).text("error")).isEqualTo("HOLD_NOT_FOUND");
        fake.buyThrows(new HoldNotActiveException(HOLD_ID));
        assertThat(api.buy(HOLD_ID).status()).isEqualTo(410);
        assertThat(api.buy(HOLD_ID).text("error")).isEqualTo("HOLD_NOT_ACTIVE");
        fake.buyThrows(new CannotAcquireLockException("deadlock detected"));
        assertThat(api.buy(HOLD_ID).status()).isEqualTo(503);
        assertThat(api.buy(HOLD_ID).text("error")).isEqualTo("TRY_AGAIN");
    }

    @Test void unexpectedFailureIs500AndLeaksNothing() {
        fake.buyThrows(new IllegalStateException("secret detail"));
        ApiResponse r = api.buy(HOLD_ID);
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.text("error")).isEqualTo("INTERNAL_ERROR");
        assertThat(r.rawBody()).doesNotContain("secret detail");
    }
}
```

```java
@IntegrationTest
@Import(FakeBookingConfig.class)
@TestPropertySource(properties = "ticketdrop.max-seats-per-hold=2")
class MaxSeatsSettingTest {
    // same fields and @BeforeEach as BookingEndpointsTest
    @Test void theLimitFollowsTheSetting() {
        fake.holdReturns(new Hold(UUID.randomUUID(), event, BUYER, List.of(1L, 2L), Instant.now()));
        assertThat(api.hold(event, BUYER, List.of(1L, 2L, 3L)).status()).isEqualTo(400);
        assertThat(api.hold(event, BUYER, List.of(1L, 2L)).status()).isEqualTo(201);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='BookingEndpointsTest,MaxSeatsSettingTest'`
Expected: FAIL to compile (`BookingService`, `FakeBookingService` do not exist).

- [ ] **Step 3: Write the `booking` types, the stub and the fake**

Everything listed under Produces except the two controllers and the handler changes.

- [ ] **Step 4: Write `HoldController`, `OrderController`, the request records and the handler mappings**

`HoldController` first checks the seat count against `maxSeatsPerHold` and rejects duplicates (400), then calls `eventService.requireEvent(eventId)` (404), then calls `hold`. That is the order §6 requires: invalid input is rejected before an unknown event. `OrderController` answers 201 when `created` is true and 200 otherwise.

- [ ] **Step 5: Run the endpoint tests to verify they pass**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Write `BookingApiContract`**

It compiles now and runs nowhere yet: Tasks 4 and 7 add the subclasses. Helpers whose bodies are the implementer's: `long newEvent(int rows, int seatsPerRow)`, `List<Long> seatIds(long eventId)` (in listing order), `String statusOf(long eventId, long seatId)`, `UUID held(long eventId, UUID buyer, Long... seats)` (holds, asserts 201, returns the hold ID), `UUID buyer()` (a random UUID).

```java
@IntegrationTest
abstract class BookingApiContract {
    @Autowired protected ApiClient api;
    @Autowired protected TestData data;

    // ---- hold ----
    @Test void holdReturns201WithTheHold() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event); UUID b = buyer();
        ApiResponse r = api.hold(event, b, List.of(s.get(0), s.get(1)));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.uuid("holdId")).isNotNull();
        assertThat(r.longValue("eventId")).isEqualTo(event);
        assertThat(r.uuid("buyerId")).isEqualTo(b);
        assertThat(r.longs("seatIds")).containsExactly(s.get(0), s.get(1));
        assertThat(r.instant("expiresAt"))
            .isCloseTo(data.dbNow().plus(Duration.ofMinutes(5)), within(5, ChronoUnit.SECONDS));
        assertThat(statusOf(event, s.get(0))).isEqualTo("HELD");
        assertThat(statusOf(event, s.get(1))).isEqualTo("HELD");
        assertThat(statusOf(event, s.get(2))).isEqualTo("AVAILABLE");
    }

    @Test void holdReturnsSeatIdsAscending() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(2), s.get(0)));
        assertThat(r.longs("seatIds")).containsExactly(s.get(0), s.get(2));
    }

    @Test void heldSeatIs409AndIsNamed() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        held(event, buyer(), s.get(0));
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("error")).isEqualTo("SEATS_UNAVAILABLE");
        assertThat(r.longs("unavailableSeatIds")).containsExactly(s.get(0));
    }

    @Test void multiSeatHoldIsAllOrNothing() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        held(event, buyer(), s.get(1));
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0), s.get(1), s.get(2)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.longs("unavailableSeatIds")).containsExactly(s.get(1));
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
        assertThat(statusOf(event, s.get(2))).isEqualTo("AVAILABLE");
    }

    @Test void aBuyerCannotReholdTheirOwnSeat() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event); UUID b = buyer();
        held(event, b, s.get(0));
        ApiResponse r = api.hold(event, b, List.of(s.get(0)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.longs("unavailableSeatIds")).containsExactly(s.get(0));
    }

    @Test void unknownSeatIs404AndNothingIsHeld() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0), 999_999_999L));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("error")).isEqualTo("SEAT_NOT_FOUND");
        assertThat(r.longs("unknownSeatIds")).containsExactly(999_999_999L);
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
    }

    @Test void seatFromAnotherEventIs404AndIsNotHeld() {
        long eventA = newEvent(1, 2); long eventB = newEvent(1, 2);
        long foreign = seatIds(eventB).get(0);
        ApiResponse r = api.hold(eventA, buyer(), List.of(seatIds(eventA).get(0), foreign));
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.longs("unknownSeatIds")).containsExactly(foreign);
        assertThat(statusOf(eventB, foreign)).isEqualTo("AVAILABLE");
        assertThat(statusOf(eventA, seatIds(eventA).get(0))).isEqualTo("AVAILABLE");
    }

    @Test void unknownSeatWinsOverUnavailableSeat() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        held(event, buyer(), s.get(0));
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0), 999_999_999L));
        assertThat(r.status()).isEqualTo(404);
    }

    @Test void anExpiredHoldCanBeTakenOver() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID first = held(event, buyer(), s.get(0));
        data.expireHold(first);
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
        assertThat(api.hold(event, buyer(), List.of(s.get(0))).status()).isEqualTo(201);
        assertThat(api.buy(first).status()).isEqualTo(410);
    }

    // ---- release ----
    @Test void releaseFreesTheSeats() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0), s.get(1));
        assertThat(api.release(hold).status()).isEqualTo(204);
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
        assertThat(statusOf(event, s.get(1))).isEqualTo("AVAILABLE");
        assertThat(api.hold(event, buyer(), List.of(s.get(0))).status()).isEqualTo(201);
    }

    @Test void releaseIsAlways204() {
        long event = newEvent(1, 4);
        UUID hold = held(event, buyer(), seatIds(event).get(0));
        assertThat(api.release(UUID.randomUUID()).status()).isEqualTo(204);
        assertThat(api.release(hold).status()).isEqualTo(204);
        assertThat(api.release(hold).status()).isEqualTo(204);
    }

    @Test void releaseAfterASaleChangesNothing() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0));
        api.buy(hold);
        assertThat(api.release(hold).status()).isEqualTo(204);
        assertThat(statusOf(event, s.get(0))).isEqualTo("SOLD");
    }

    @Test void releasingAnExpiredHoldLeavesSeatsTakenByAnotherHoldAlone() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID first = held(event, buyer(), s.get(0), s.get(1));
        data.expireHold(first);
        UUID second = held(event, buyer(), s.get(0));
        assertThat(api.release(first).status()).isEqualTo(204);
        assertThat(statusOf(event, s.get(0))).isEqualTo("HELD");
        assertThat(statusOf(event, s.get(1))).isEqualTo("AVAILABLE");
        assertThat(api.buy(second).status()).isEqualTo(201);
    }

    // ---- buy ----
    @Test void buyReturns201WithTheOrderAndSellsTheSeats() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event); UUID b = buyer();
        UUID hold = held(event, b, s.get(0), s.get(1));
        ApiResponse r = api.buy(hold);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.uuid("orderId")).isNotNull();
        assertThat(r.longValue("eventId")).isEqualTo(event);
        assertThat(r.uuid("buyerId")).isEqualTo(b);
        assertThat(r.uuid("holdId")).isEqualTo(hold);
        assertThat(r.longs("seatIds")).containsExactly(s.get(0), s.get(1));
        assertThat(r.instant("createdAt")).isCloseTo(data.dbNow(), within(5, ChronoUnit.SECONDS));
        assertThat(statusOf(event, s.get(0))).isEqualTo("SOLD");
        assertThat(statusOf(event, s.get(1))).isEqualTo("SOLD");
        assertThat(api.summary(event).intValue("sold")).isEqualTo(2);
        assertThat(api.summary(event).intValue("orders")).isEqualTo(1);
    }

    @Test void buyingTheSameHoldAgainIs200WithTheSameOrder() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0), s.get(1));
        ApiResponse first = api.buy(hold);
        ApiResponse repeat = api.buy(hold);
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.uuid("orderId")).isEqualTo(first.uuid("orderId"));
        assertThat(repeat.longs("seatIds")).containsExactly(s.get(0), s.get(1));
        assertThat(api.summary(event).intValue("orders")).isEqualTo(1);
    }

    @Test void buyAfterExpiryIs410AndSellsNothing() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0));
        data.expireHold(hold);
        ApiResponse r = api.buy(hold);
        assertThat(r.status()).isEqualTo(410);
        assertThat(r.text("error")).isEqualTo("HOLD_NOT_ACTIVE");
        assertThat(statusOf(event, s.get(0))).isEqualTo("AVAILABLE");
        assertThat(api.summary(event).intValue("orders")).isEqualTo(0);
    }

    @Test void buyAfterReleaseIs410() {
        long event = newEvent(1, 4);
        UUID hold = held(event, buyer(), seatIds(event).get(0));
        api.release(hold);
        assertThat(api.buy(hold).status()).isEqualTo(410);
    }

    @Test void buyingAnUnknownHoldIs404() {
        ApiResponse r = api.buy(UUID.randomUUID());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.text("error")).isEqualTo("HOLD_NOT_FOUND");
    }

    @Test void aSoldSeatCannotBeHeld() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        api.buy(held(event, buyer(), s.get(0)));
        ApiResponse r = api.hold(event, buyer(), List.of(s.get(0)));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.longs("unavailableSeatIds")).containsExactly(s.get(0));
    }

    @Test void aHoldThatLostASeatIsNotBoughtInPart() {
        long event = newEvent(1, 4); List<Long> s = seatIds(event);
        UUID hold = held(event, buyer(), s.get(0), s.get(1), s.get(2));
        data.stealSeat(s.get(1));        // the hold is unexpired but no longer whole
        assertThat(api.buy(hold).status()).isEqualTo(410);
        assertThat(statusOf(event, s.get(0))).isEqualTo("HELD");
        assertThat(statusOf(event, s.get(2))).isEqualTo("HELD");
        assertThat(api.summary(event).intValue("sold")).isEqualTo(0);
        assertThat(api.summary(event).intValue("orders")).isEqualTo(0);
    }
}
```

- [ ] **Step 7: Commit**

Run: `./mvnw verify`
Expected: BUILD SUCCESS (the contract compiles; no subclass runs it yet).

```bash
git add -A
git commit -m "feat: booking API surface, endpoint tests and the strategy contract"
git push -u origin task-3-booking-api
```

Then review and merge.

---

### Task 4: The naive strategy

Branch `task-4-naive`.

**Files:**
- Create: `src/main/java/com/ticketdrop/booking/NaiveBookingService.java`
- Test: `src/test/java/com/ticketdrop/booking/NaiveBookingApiTest.java`

**Interfaces:**
- Consumes: `BookingService`, `Hold`, `Order`, `PurchaseResult`, the four exceptions, and the two guarantee lists in Task 3. `BookingProperties.holdDuration()`. `JdbcClient`.
- Produces: `NaiveBookingService`, active only when `ticketdrop.booking-strategy=naive`.

- [ ] **Step 1: Add the test class**

```java
@TestPropertySource(properties = "ticketdrop.booking-strategy=naive")
@ExtendWith(OutputCaptureExtension.class)
class NaiveBookingApiTest extends BookingApiContract {
    @Test void warnsAtStartupThatItIsUnsafe(CapturedOutput output) {
        assertThat(output).contains("NAIVE booking strategy is enabled");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=NaiveBookingApiTest`
Expected: FAIL. The application does not start: no bean of type `BookingService`.

- [ ] **Step 3: Write `NaiveBookingService`**

```java
@Service
@ConditionalOnProperty(name = "ticketdrop.booking-strategy", havingValue = "naive")
public class NaiveBookingService implements BookingService {
    public NaiveBookingService(JdbcClient jdbc, BookingProperties properties) { /* yours */ }
    @Override @Transactional public Hold hold(long eventId, UUID buyerId, List<Long> seatIds) { /* yours */ }
    @Override @Transactional public void release(UUID holdId) { /* yours */ }
    @Override @Transactional public PurchaseResult buy(UUID holdId) { /* yours */ }
}
```

The rules are §6. The style is §9: for each operation, read the rows you need with a `SELECT`, decide in Java, then write with plain `UPDATE ... WHERE id = ...` statements. No `FOR UPDATE`, and no extra conditions on the writes. The constructor logs, at WARN, a message starting with `NAIVE booking strategy is enabled`.

Things you will need to find out, not solutions:
- `JdbcClient` basics: `jdbc.sql("...").param("name", value).query(...)` and `.update()`.
- A `List` bound to `IN (:ids)` is expanded for you.
- Let PostgreSQL do the clock arithmetic: compute the expiry in SQL from `now()` and get it back with `RETURNING`. Read `timestamptz` columns as `OffsetDateTime` and convert to `Instant`.

- [ ] **Step 4: Run the tests until they pass**

Run: `./mvnw test -Dtest=NaiveBookingApiTest`
Expected: PASS, 21 tests (the 20 contract tests and the startup warning).

- [ ] **Step 5: Run the whole build, commit and ask for review**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "feat: naive booking strategy (deliberately unsafe baseline)"
git push -u origin task-4-naive
```

Review against §6 and §9, then merge.

---

### Task 5: The race-test toolkit

Branch `task-5-race-toolkit`. Can run alongside Task 4.

**Files:**
- Create: `src/test/java/com/ticketdrop/support/ConcurrentCalls.java`, `Invariants.java`
- Modify: `src/test/java/com/ticketdrop/support/TestData.java`, `TestSupportConfig.java`
- Test: `src/test/java/com/ticketdrop/support/ConcurrentCallsTest.java`, `InvariantsTest.java`

**Interfaces:**
- Consumes: the `invariant_violations` view (Task 1), `TestData` (Tasks 2 and 3).
- Produces:
  - `static <T> List<T> ConcurrentCalls.runAll(List<Callable<T>> tasks, Duration timeout)`: starts one thread per task, releases them all at the same instant, and returns the results in task order. Throws `AssertionError` if the tasks have not all finished within `timeout`, or if any task throws (naming the task's index).
  - `Invariants` (a lazy bean): `List<String> violations(long eventId)` (each entry is `invariant + ": " + detail`), `void assertNone(long eventId)` (fails listing the violations), `int soldSeats(long eventId)`, `int orders(long eventId)`.
  - `TestData.expireHoldIn(UUID holdId, Duration fromNow)`: sets the hold's `expires_at` and its seats' `hold_expires_at` to `clock_timestamp() + fromNow`.

- [ ] **Step 1: Write the failing tests**

```java
class ConcurrentCallsTest {
    @Test void returnsResultsInTaskOrder() {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) { int n = i; tasks.add(() -> n); }
        assertThat(ConcurrentCalls.runAll(tasks, Duration.ofSeconds(5)))
            .containsExactlyElementsOf(IntStream.range(0, 50).boxed().toList());
    }

    @Test void allTasksRunAtTheSameTime() {
        CyclicBarrier barrier = new CyclicBarrier(100);   // passes only if 100 tasks wait together
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) tasks.add(() -> { barrier.await(5, TimeUnit.SECONDS); return true; });
        assertThat(ConcurrentCalls.runAll(tasks, Duration.ofSeconds(10))).hasSize(100).containsOnly(true);
    }

    @Test void failsWhenTasksDoNotFinishInTime() {
        List<Callable<Boolean>> tasks = List.of(() -> { Thread.sleep(5_000); return true; });
        assertThatThrownBy(() -> ConcurrentCalls.runAll(tasks, Duration.ofMillis(200)))
            .isInstanceOf(AssertionError.class);
    }

    @Test void failsWhenATaskThrows() {
        List<Callable<Boolean>> tasks = List.of(() -> true, () -> { throw new IllegalStateException("boom"); });
        assertThatThrownBy(() -> ConcurrentCalls.runAll(tasks, Duration.ofSeconds(5)))
            .isInstanceOf(AssertionError.class).hasMessageContaining("1");
    }
}
```

```java
@IntegrationTest
class InvariantsTest {
    @Autowired ApiClient api; @Autowired TestData data; @Autowired Invariants invariants; @Autowired JdbcClient jdbc;

    @Test void aCleanEventHasNoViolationsAndCountsItsSales() {
        long event = api.createEvent("E", 1, 4).longValue("id");
        List<SeatRow> s = api.listSeats(event).seats();
        data.markSold(s.get(0).id());
        data.markSold(s.get(1).id());
        invariants.assertNone(event);
        assertThat(invariants.soldSeats(event)).isEqualTo(2);
        assertThat(invariants.orders(event)).isEqualTo(2);
    }

    @Test void aBrokenEventIsReported() {
        long event = api.createEvent("E", 1, 4).longValue("id");
        data.markSold(api.listSeats(event).seats().get(0).id());
        jdbc.sql("update holds set seat_count = 2 where event_id = :e").param("e", event).update();
        assertThat(invariants.violations(event)).hasSize(1).first().asString().startsWith("V2: ");
        assertThatThrownBy(() -> invariants.assertNone(event)).isInstanceOf(AssertionError.class);
    }

    @Test void expireHoldInMovesTheExpiry() {
        long event = api.createEvent("E", 1, 1).longValue("id");
        long seat = api.listSeats(event).seats().get(0).id();
        data.markHeld(seat, Duration.ofMinutes(5));
        UUID hold = jdbc.sql("select hold_id from seats where id = :s").param("s", seat).query(UUID.class).single();
        data.expireHoldIn(hold, Duration.ofMillis(-1));
        assertThat(api.listSeats(event).seats().get(0).status()).isEqualTo("AVAILABLE");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest='ConcurrentCallsTest,InvariantsTest'`
Expected: FAIL to compile.

- [ ] **Step 3: Write `ConcurrentCalls`, `Invariants` and `TestData.expireHoldIn`**

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Confirm the strategy switch reaches the tests** (needs Task 4 merged)

Run: `./mvnw test -Dtest=SmokeTest -Dticketdrop.booking-strategy=naive`
Expected: PASS, and the output contains `NAIVE booking strategy is enabled`. If the line is missing, Maven is not passing the property to the test JVM: switch every such command in this plan and the README to the environment-variable form (`TICKETDROP_BOOKING_STRATEGY=naive`).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: toolkit for race tests (concurrent calls, invariant checks)"
git push -u origin task-5-race-toolkit
```

Then review and merge.

---

### Task 6: The race tests

Branch `task-6-7-atomic` (shared with Task 7; it is merged only when Task 7 is done).

**Files:**
- Test: `src/test/java/com/ticketdrop/booking/BookingRaceTest.java`
- Create: `docs/evidence/naive-race-tests.txt`

**Interfaces:**
- Consumes: `ApiClient`, `TestData.expireHoldIn`, `ConcurrentCalls.runAll`, `Invariants` (Task 5).
- Produces: five tests tagged `race`, named `r1_twoHundredBuyersOneSeat`, `r2_overlappingGroups`, `r3_oneHoldBoughtFiftyTimes`, `r4_buyAtTheMomentOfExpiry`, `r5_storm`.

- [ ] **Step 1: Create the class with R1's scaffold**

The class is `@IntegrationTest @Tag("race") class BookingRaceTest` with `ApiClient`, `TestData` and `Invariants` autowired. It is not tied to a strategy, so it runs against whichever one the setting selects.

```java
@Test
void r1_twoHundredBuyersOneSeat() {
    for (int round = 0; round < 20; round++) {
        long event = api.createEvent("R1", 1, 1).longValue("id");
        long seat = api.listSeats(event).seats().get(0).id();
        List<Callable<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            calls.add(() -> api.hold(event, UUID.randomUUID(), List.of(seat)));
        }
        List<ApiResponse> responses = ConcurrentCalls.runAll(calls, Duration.ofSeconds(30));
        // Your assertions go here.
    }
}
```

- [ ] **Step 2: Write the assertions for R1 and the whole of R2-R5**

The scenarios and pass conditions are the table in §10, word for word. R1-R4 run 20 rounds on fresh seats, each round with a 30-second timeout; R5 runs once. Every test ends by checking the invariants for its event, and R5 also checks V5 (seats in 201 buy responses equal `invariants.soldSeats`, and 201 buy responses equal `invariants.orders`) and V6 (no buy was answered 410).

- [ ] **Step 3: Run them against the naive strategy and watch them fail**

Run: `./mvnw test -Dgroups=race -Dticketdrop.booking-strategy=naive`
Expected: FAIL. R1 reports more than one 201 for the same seat. R2 and R5 normally fail too. R3 and R4 may fail or pass from run to run.

If R1 passes against the naive strategy, the test is not creating real concurrency. Fix the test before going on.

- [ ] **Step 4: Save the evidence**

Copy the failure summary from the run into `docs/evidence/naive-race-tests.txt`, with the command and the date at the top.

- [ ] **Step 5: Explain it**

Answer question 1 of §14 out loud or in writing: walk through two overlapping hold requests in your naive code and show the exact line where each one goes wrong.

- [ ] **Step 6: Commit (do not merge yet)**

```bash
git add -A
git commit -m "test: race tests R1-R5, failing against the naive strategy"
git push -u origin task-6-7-atomic
```

Against the default strategy these tests fail with 500s until Task 7, which is why the branch stays open.

---

### Task 7: The atomic strategy

Same branch, `task-6-7-atomic`.

**Files:**
- Modify: `src/main/java/com/ticketdrop/booking/AtomicBookingService.java` (replace the three stub bodies)
- Test: `src/test/java/com/ticketdrop/booking/AtomicBookingApiTest.java`

**Interfaces:**
- Consumes: the same as Task 4.
- Produces: the default `BookingService`, meeting every rule and every concurrency requirement of §6.

- [ ] **Step 1: Add the test class**

```java
class AtomicBookingApiTest extends BookingApiContract {
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=AtomicBookingApiTest`
Expected: FAIL. Every booking test gets a 500 (`Implemented in Task 7`).

- [ ] **Step 3: Write `hold`**

Questions to answer before you type:
- Which single SQL statement can both check that a seat is free and take it? What does its updated-row count tell you?
- What must happen before any change that touches several seats, so two requests can never deadlock? (PostgreSQL manual: "Explicit Locking", row-level locks.)
- How do you find out which seats were unknown, and which were not free, so the right exception carries the right IDs?

Run: `./mvnw test -Dtest=AtomicBookingApiTest`
Expected: eight of the nine hold tests pass. `anExpiredHoldCanBeTakenOver` also calls buy and passes after Step 5.

- [ ] **Step 4: Write `release`**

Run: `./mvnw test -Dtest=AtomicBookingApiTest`
Expected: `releaseFreesTheSeats` and `releaseIsAlways204` now pass. The other two release tests also call buy and pass after Step 5.

- [ ] **Step 5: Write `buy`**

Questions to answer before you type: why is "at least one seat is still mine" not enough (§6, Buy, step 2)? In what order must the order row and the seat changes be written, given the foreign key from `seats.order_id`? What does a second, simultaneous buy of the same hold see once the first has committed?

Run: `./mvnw test -Dtest=AtomicBookingApiTest`
Expected: PASS, all 20 contract tests.

- [ ] **Step 6: Run the race tests against it, five times**

Run, five times in a row: `./mvnw test -Dgroups=race`
Expected: PASS every time. One failure in five is a real bug, not bad luck.

- [ ] **Step 7: Confirm the naive strategy still fails**

Run: `./mvnw test -Dgroups=race -Dticketdrop.booking-strategy=naive`
Expected: FAIL, as in Task 6.

- [ ] **Step 8: Explain it**

Answer questions 2-8 of §14 without looking at the code.

- [ ] **Step 9: Run the whole build, commit and ask for review**

Run: `./mvnw verify`
Expected: BUILD SUCCESS.

```bash
git add -A
git commit -m "feat: atomic booking strategy; race tests R1-R5 pass"
git push
```

Review against §6, with particular attention to the concurrency requirements, then merge.

---

### Task 8: Container image and load-test harness

Branch `task-8-load-harness`. Steps 1-6 can be written while Tasks 6 and 7 are in progress.

**Files:**
- Create: `Dockerfile`, `.dockerignore`, `compose.yaml`
- Create: `load/drop.js`, `scripts/loadtest.sh`, `scripts/verify.sh`
- Modify: `README.md`

**Interfaces:**
- Consumes: the HTTP API of §7; the `invariant_violations` view; setting `ticketdrop.booking-strategy`.
- Produces:
  - `docker compose up -d --build` starts `db` and `app`; the API is on `http://localhost:8080`.
  - `bash scripts/loadtest.sh` runs one whole load test. Environment variables and defaults: `BUYERS=2000`, `ROWS=20`, `SEATS_PER_ROW=25`, `BOOKING_STRATEGY=atomic`. Its last line is `VERIFICATION PASSED` or `VERIFICATION FAILED`, and its exit code is 0 only for the former.
  - `load/results/metrics.csv` (every k6 data point) and `load/results/report.txt` (every number the README needs).
  - `bash scripts/verify.sh` re-runs the checks against the running stack and the existing CSV.

k6 is at 2.3.0. Before writing `drop.js`, read the k6 v2 documentation for scenarios, custom metrics and thresholds. This plan pins behaviour, not syntax. The harness reads results only from the CSV output, because the format of k6's end-of-test summary object changed in v2.

- [ ] **Step 1: Write `Dockerfile` and `.dockerignore`**

Two stages: build with `eclipse-temurin:25-jdk` using `./mvnw -q -DskipTests package`; run with `eclipse-temurin:25-jre` as a non-root user, `ENTRYPOINT ["java","-jar","/app/ticketdrop.jar"]`, port 8080. Ignore `target/`, `.git/`, `load/results/`, `docs/`.

- [ ] **Step 2: Write `compose.yaml`**

- `db`: `postgres:18`; database, user and password all `ticketdrop`; health check with `pg_isready`; 2 CPUs, 1 GB; no volume (every run starts empty).
- `app`: built from the Dockerfile; `depends_on` `db` with `condition: service_healthy`; `DB_URL=jdbc:postgresql://db:5432/ticketdrop`; `TICKETDROP_BOOKING_STRATEGY=${BOOKING_STRATEGY:-atomic}`; port `8080:8080`; 2 CPUs, 1 GB.
- `k6`: `grafana/k6:2.3.0`; profile `loadtest`; runs as root so it can write to the bind mount; mounts `./load` at `/load`; the whole k6 command line lives in `command:` (`run --out csv=/load/results/metrics.csv /load/drop.js`); receives `BASE_URL=http://app:8080`, `BUYERS`, `ROWS`, `SEATS_PER_ROW`.

- [ ] **Step 3: Verify a cold start**

Run, twice in a row: `docker compose down -v && docker compose up -d --build`, then `curl -s --retry 30 --retry-delay 2 --retry-connrefused http://localhost:8080/actuator/health`
Expected: `{"status":"UP"...}` both times, and `docker compose ps` shows `app` running with zero restarts.

- [ ] **Step 4: Write `scripts/verify.sh` and prove it can fail**

It sets `MSYS_NO_PATHCONV=1`, queries the database with `docker compose exec -T db psql` (counting across the whole database, which holds exactly one run), and sums the custom counters from `metrics.csv` with `awk` (metric name in column 1, value in column 3; any other column is located by its header name). It prints one line per check and then the verdict:

```
PASS V1-V4  invariant_violations=0
PASS V5     seats_bought=500 sold=500  buys_created=213 orders=213
PASS V6     broken_promises=0
PASS HTTP   http_500=0
VERIFICATION PASSED
```

A failed check prints `FAIL` in place of `PASS`. It then writes `load/results/report.txt` with: total requests, peak requests per second (the largest number of `http_reqs` rows sharing one timestamp), 95th and 99th percentile of `http_req_duration` for the requests tagged `hold` and for those tagged `buy`, seconds from the event's first hold to its last order (from the database), the percentage of `hold` requests answered 409, and `broken_promises`.

Prove the checker fails when it should. With the stack up and one event created, write a three-line `metrics.csv` by hand that claims `seats_bought` = 2 while the database has no sold seats.

Run: `bash scripts/verify.sh; echo "exit=$?"`
Expected: a `FAIL V5` line, `VERIFICATION FAILED`, `exit=1`.

Then break the data: insert a hold with `seat_count` 2, its order, and one `SOLD` seat.

Run: `bash scripts/verify.sh; echo "exit=$?"`
Expected: `FAIL V1-V4  invariant_violations=1`, `exit=1`.

- [ ] **Step 5: Write `load/drop.js`**

The scenario of §10, exactly: `setup` creates one event of `ROWS` x `SEATS_PER_ROW`; `BUYERS` virtual buyers, one journey each, all starting within 10 seconds, the run capped at 2 minutes. A journey: list the seats; pick 1-4 that show as available; hold; on 409, list and pick again, up to 5 attempts; with a hold, wait 1-3 seconds; then buy (90%) or release (10%).

- Requests carry a `name` tag: `list`, `hold`, `buy`, `release`.
- Custom counters: `seats_bought` (add the number of seats on each 201 from buy), `buys_created` (add 1 on each 201 from buy), `broken_promises` (add 1 on each 410 from buy), `http_500` (add 1 on each response with status 500). Each buyer first adds 0 to all four, so they always appear in the CSV.
- 409 on hold is an expected status and must not count as a failed request.
- Thresholds: `broken_promises` count is 0 and `http_500` count is 0.

- [ ] **Step 6: Write `scripts/loadtest.sh`**

It sets `MSYS_NO_PATHCONV=1` and passes no container paths on any command line. Sequence: `docker compose down -v`; `docker compose up -d --build db app`; wait up to 120 seconds for the health endpoint to report `UP`; `docker compose --profile loadtest run --rm k6`; `bash scripts/verify.sh`; exit with the verifier's code. It leaves the stack running and prints how to stop it.

```bash
git update-index --chmod=+x scripts/loadtest.sh scripts/verify.sh
```

- [ ] **Step 7: Run a small drop end to end** (needs Task 7 merged)

Run, from Git Bash on the development laptop: `BUYERS=50 ROWS=5 SEATS_PER_ROW=10 bash scripts/loadtest.sh; echo "exit=$?"`
Expected: four `PASS` lines, `VERIFICATION PASSED`, `exit=0`, and `load/results/report.txt` exists with every number filled in.

- [ ] **Step 8: Extend the README**

Sections: what the project is; run it (`docker compose up`); run the tests; reproduce the bug (`./mvnw test -Dgroups=race -Dticketdrop.booking-strategy=naive`, with the quoted form PowerShell needs); run the load test; a results table with these columns and no rows yet: Strategy, Buyers, Seats, Total requests, Peak requests/s, Hold p95/p99 (ms), Buy p95/p99 (ms), First hold to last order (s), Holds answered 409 (%), Broken promises, Verification; a line for the machine's CPU and memory and the container limits; and a note that the API has no logins and must not be exposed publicly yet (§13).

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: container image, k6 drop scenario and verification scripts"
git push -u origin task-8-load-harness
```

Then review and merge.

---

### Task 9: Run the drop, tune and record

Branch `task-9-results`.

**Files:**
- Modify: `README.md`
- Create: `docs/evidence/load-atomic.txt`, `docs/evidence/load-naive.txt`

**Interfaces:**
- Consumes: `scripts/loadtest.sh` and `load/results/report.txt` (Task 8).
- Produces: the first measured results in the README.

- [ ] **Step 1: Run the full drop against the atomic strategy**

Run: `bash scripts/loadtest.sh`
Expected: `VERIFICATION PASSED`. If your laptop cannot sustain 2,000 buyers (k6 or Docker runs out of memory), lower `BUYERS` and record the number you used.

- [ ] **Step 2: Run the same drop against the naive strategy**

Run: `BOOKING_STRATEGY=naive bash scripts/loadtest.sh`
Expected: `VERIFICATION FAILED`, with `FAIL V6` and a non-zero `broken_promises`.

- [ ] **Step 3: Record both runs**

Copy each run's `report.txt` and verifier output into `docs/evidence/load-atomic.txt` and `docs/evidence/load-naive.txt`. Add one row per run to the README's results table, and fill in the machine line.

- [ ] **Step 4: Tune, if a measurement asks for it**

Follow the rule in §10: start from the defaults, change one thing at a time in response to something you measured, and record the before and after as extra rows in the table. Do not tune without a number that justifies it.

- [ ] **Step 5: Explain it**

Answer question 9 of §14: where does this design stop scaling, and what would you change first? Use your own numbers.

- [ ] **Step 6: Commit and ask for review**

```bash
git add -A
git commit -m "docs: first load-test results for the atomic and naive strategies"
git push -u origin task-9-results
```

---

## Done

The piece is finished when the five success criteria of §2 hold:

- [ ] `docker compose up` starts the service and database, and the API behaves as §7 (Tasks 2, 3, 7, 8).
- [ ] The API tests and race tests pass in CI on every push (Tasks 1-7).
- [ ] One documented command shows the race tests failing against the naive strategy (Tasks 6, 8).
- [ ] A full load-test run passes verification with no HTTP 500, and its numbers are in the README (Task 9).
- [ ] You can answer the nine questions of §14 without the code (Tasks 6, 7, 9).
