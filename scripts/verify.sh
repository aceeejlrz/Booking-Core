#!/usr/bin/env bash
#
# Checks a finished load-test run against the booking invariants (V1-V6 in section 10 of the design
# spec) and writes every number the README needs to load/results/report.txt.
#
#   bash scripts/verify.sh
#
# Needs the stack to be up and load/results/metrics.csv to exist; scripts/loadtest.sh arranges both.
# The last line printed is VERIFICATION PASSED or VERIFICATION FAILED. Exit code 0 only if it passed.

set -u
export MSYS_NO_PATHCONV=1   # stop Git Bash on Windows rewriting arguments that look like Unix paths
cd "$(dirname "$0")/.."

CSV=load/results/metrics.csv
REPORT=load/results/report.txt

if [ ! -f "$CSV" ]; then
  echo "There is no $CSV. Run: bash scripts/loadtest.sh" >&2
  echo "VERIFICATION FAILED"
  exit 1
fi

# Runs one SQL query against the stack's database and prints its single value.
sql() {
  docker compose exec -T db psql -U ticketdrop -d ticketdrop -At -c "$1" 2>/dev/null | tr -d '\r'
}

# Sums one of the load test's custom counters. In k6's CSV the metric name is column 1 and the
# value is column 3.
counter() {
  awk -F, -v metric="$1" '$1 == metric { sum += $3 } END { printf "%d", sum }' "$CSV"
}

# Finds a column of the CSV by its header name, because its position differs between k6 versions.
column() {
  head -1 "$CSV" | tr -d '\r' | awk -F, -v want="$1" '{ for (i = 1; i <= NF; i++) if ($i == want) print i }'
}

is_number() {
  case "$1" in '' | *[!0-9]*) return 1 ;; *) return 0 ;; esac
}

# ---- what the database says -------------------------------------------------------------------
# The stack starts with an empty database, so it holds exactly one run.
violations=$(sql "select count(*) from invariant_violations")
sold=$(sql "select count(*) from seats where status = 'SOLD'")
orders=$(sql "select count(*) from orders")
seats=$(sql "select count(*) from seats")

for value in "$violations" "$sold" "$orders" "$seats"; do
  if ! is_number "$value"; then
    echo "Could not read the database. Is the stack up?  docker compose ps" >&2
    echo "VERIFICATION FAILED"
    exit 1
  fi
done

# ---- what the buyers were told ----------------------------------------------------------------
seats_bought=$(counter seats_bought)
buys_created=$(counter buys_created)
broken_promises=$(counter broken_promises)
http_500=$(counter http_500)

# ---- the checks ---------------------------------------------------------------------------------
failed=0
check() {   # check LABEL ok|bad DETAIL
  if [ "$2" = ok ]; then verdict=PASS; else verdict=FAIL; failed=1; fi
  printf '%s %-6s %s\n' "$verdict" "$1" "$3"
}

# V1-V4: nothing in the data contradicts itself. (V1 and V4 are also enforced by constraints.)
[ "$violations" -eq 0 ] && r=ok || r=bad
check "V1-V4" "$r" "invariant_violations=$violations"

# V5: what buyers were told they bought is exactly what the database says was sold.
[ "$seats_bought" -eq "$sold" ] && [ "$buys_created" -eq "$orders" ] && r=ok || r=bad
check "V5" "$r" "seats_bought=$seats_bought sold=$sold  buys_created=$buys_created orders=$orders"

# V6: no buyer who was promised seats and bought in time was turned away.
[ "$broken_promises" -eq 0 ] && r=ok || r=bad
check "V6" "$r" "broken_promises=$broken_promises"

# The run's gate also requires that the service never answered 500.
[ "$http_500" -eq 0 ] && r=ok || r=bad
check "HTTP" "$r" "http_500=$http_500"

# ---- the report -----------------------------------------------------------------------------------
name_col=$(column name)
status_col=$(column status)

# The value below which P percent of the response times of one kind of request fall, in ms.
percentile() {   # percentile REQUEST-NAME P
  awk -F, -v want="$1" -v c="$name_col" '$1 == "http_req_duration" && $c == want { print $3 }' "$CSV" \
    | sort -n \
    | awk -v p="$2" '{ v[NR] = $1 }
        END {
          if (NR == 0) { print "n/a"; exit }
          i = int(p / 100 * NR + 0.999999); if (i < 1) i = 1; if (i > NR) i = NR
          printf "%.1f", v[i]
        }'
}

total_requests=$(awk -F, '$1 == "http_reqs" { n++ } END { printf "%d", n }' "$CSV")
peak_rps=$(awk -F, '$1 == "http_reqs" { perSecond[$2]++ }
    END { for (t in perSecond) if (perSecond[t] > max) max = perSecond[t]; printf "%d", max }' "$CSV")
buyers=$(awk -F, '$1 == "vus_max" { v = $3 } END { printf "%d", v }' "$CSV")
holds_409_percent=$(awk -F, -v n="$name_col" -v s="$status_col" '
    $1 == "http_reqs" && $n == "hold" { all++; if ($s == 409) rejected++ }
    END { if (all == 0) print "n/a"; else printf "%.1f", 100 * rejected / all }' "$CSV")
first_hold_to_last_order=$(sql "select coalesce(round(extract(epoch from
    (select max(created_at) from orders) - (select min(created_at) from holds))::numeric, 1)::text, 'n/a')")
strategy=$(docker compose exec -T app printenv TICKETDROP_BOOKING_STRATEGY 2>/dev/null | tr -d '\r')

[ "$failed" -eq 0 ] && outcome="PASSED" || outcome="FAILED"

{
  echo "TicketDrop load test report"
  echo "strategy:                        ${strategy:-unknown}"
  echo "buyers:                          $buyers"
  echo "seats:                           $seats"
  echo "total requests:                  $total_requests"
  echo "peak requests per second:        $peak_rps"
  echo "hold p95 / p99 (ms):             $(percentile hold 95) / $(percentile hold 99)"
  echo "buy p95 / p99 (ms):              $(percentile buy 95) / $(percentile buy 99)"
  echo "first hold to last order (s):    $first_hold_to_last_order"
  echo "holds answered 409 (%):          $holds_409_percent"
  echo "seats sold / orders:             $sold / $orders"
  echo "broken promises:                 $broken_promises"
  echo "responses with status 500:       $http_500"
  echo "invariant violations:            $violations"
  echo "verification:                    $outcome"
} > "$REPORT"
echo "Report written to $REPORT"

echo "VERIFICATION $outcome"
[ "$failed" -eq 0 ]
