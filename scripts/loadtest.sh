#!/usr/bin/env bash
#
# Runs one whole load test: a fresh stack, the drop, then the verification.
#
#   bash scripts/loadtest.sh                                       the full drop
#   BUYERS=50 ROWS=5 SEATS_PER_ROW=10 bash scripts/loadtest.sh     a small one
#   BOOKING_STRATEGY=naive bash scripts/loadtest.sh                against the unsafe strategy
#
# Settings, as environment variables (defaults in brackets):
#   BUYERS (2000), ROWS (20), SEATS_PER_ROW (25), BOOKING_STRATEGY (atomic)
#
# The last line printed is VERIFICATION PASSED or VERIFICATION FAILED. Exit code 0 only if it passed.
# Works from Git Bash on Windows and from any Linux or macOS shell.

set -u
export MSYS_NO_PATHCONV=1   # stop Git Bash on Windows rewriting arguments that look like Unix paths
cd "$(dirname "$0")/.."

export BUYERS="${BUYERS:-2000}"
export ROWS="${ROWS:-20}"
export SEATS_PER_ROW="${SEATS_PER_ROW:-25}"
export BOOKING_STRATEGY="${BOOKING_STRATEGY:-atomic}"

give_up() {
  echo "$1" >&2
  echo "VERIFICATION FAILED"
  exit 1
}

echo "== Load test: $BUYERS buyers, $ROWS x $SEATS_PER_ROW seats, strategy: $BOOKING_STRATEGY =="

mkdir -p load/results
rm -f load/results/metrics.csv load/results/report.txt

echo "== Starting a fresh stack (empty database) =="
docker compose --profile loadtest down -v --remove-orphans > /dev/null 2>&1
docker compose up -d --build db app || give_up "Could not start the stack."

echo "== Waiting for the service to report healthy =="
healthy=no
for attempt in $(seq 1 60); do
  if curl -fs --max-time 2 http://localhost:8080/actuator/health 2> /dev/null | grep -q '"status":"UP"'; then
    healthy=yes
    break
  fi
  sleep 2
done
[ "$healthy" = yes ] || give_up "The service was not healthy after 120 seconds. Look at: docker compose logs app"

echo "== Running the drop =="
docker compose --profile loadtest run --rm k6
echo "== k6 finished with exit code $? (the verification below decides the result) =="

echo "== The stack is left running so you can look around. Stop it with: docker compose down -v =="
echo "== Verifying =="
bash scripts/verify.sh
