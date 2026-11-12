// The drop: a crowd of buyers going for the same seats at the same time.
//
// Run it through scripts/loadtest.sh, which starts a fresh stack first and verifies the result after.
// The scenario is the one in section 10 of the design spec.

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BUYERS = parseInt(__ENV.BUYERS || '2000', 10);
const ROWS = parseInt(__ENV.ROWS || '20', 10);
const SEATS_PER_ROW = parseInt(__ENV.SEATS_PER_ROW || '25', 10);

const MAX_HOLD_ATTEMPTS = 5;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// What the buyers were told. scripts/verify.sh compares these with what the database says.
const seatsBought = new Counter('seats_bought');         // seats in 201 responses to buy
const buysCreated = new Counter('buys_created');         // 201 responses to buy
const brokenPromises = new Counter('broken_promises');   // 410 responses to buy
const http500 = new Counter('http_500');                 // responses with status 500, to anything

// A 409 on hold is a normal answer ("someone else got there first"), not a failed request.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 409));

export const options = {
  scenarios: {
    drop: {
      executor: 'per-vu-iterations',   // every buyer makes exactly one journey
      vus: BUYERS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    // Every buyer buys within seconds of holding, well inside the hold's lifetime. So a 410 on buy
    // can only mean the service promised the same seat to two buyers.
    broken_promises: ['count==0'],
    http_500: ['count==0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const res = http.post(
    `${BASE_URL}/api/events`,
    JSON.stringify({ name: 'The Drop', rows: ROWS, seatsPerRow: SEATS_PER_ROW }),
    { headers: JSON_HEADERS, tags: { name: 'create_event' } },
  );
  if (res.status !== 201) {
    throw new Error(`Could not create the event: ${res.status} ${res.body}`);
  }
  return { eventId: res.json('id') };
}

export default function (data) {
  // Each counter gets a zero first, so it always appears in the results even if nothing goes wrong.
  seatsBought.add(0);
  buysCreated.add(0);
  brokenPromises.add(0);
  http500.add(0);

  sleep(Math.random() * 10);   // all buyers arrive within the first 10 seconds

  const buyerId = uuid();
  const hold = holdSomeSeats(data.eventId, buyerId);
  if (hold === null) {
    return;   // sold out, or beaten to the seats five times
  }

  sleep(1 + Math.random() * 2);   // "checkout": 1-3 seconds

  if (Math.random() < 0.9) {
    const res = post('/api/orders', { holdId: hold.holdId }, 'buy');
    if (res.status === 201) {
      seatsBought.add(res.json('seatIds').length);
      buysCreated.add(1);
    } else if (res.status === 410) {
      brokenPromises.add(1);
    }
  } else {
    track(http.del(`${BASE_URL}/api/holds/${hold.holdId}`, null, { tags: { name: 'release' } }));
  }
}

// Lists the seats, picks 1-4 that show as available, and tries to hold them. On a 409 it looks
// again and picks again, up to five times. Returns the hold, or null if it never got one.
function holdSomeSeats(eventId, buyerId) {
  for (let attempt = 0; attempt < MAX_HOLD_ATTEMPTS; attempt++) {
    const list = track(http.get(`${BASE_URL}/api/events/${eventId}/seats`, { tags: { name: 'list' } }));
    if (list.status !== 200) {
      return null;
    }
    const available = list.json().filter((seat) => seat.status === 'AVAILABLE');
    if (available.length === 0) {
      return null;
    }
    const seatIds = pick(available, 1 + Math.floor(Math.random() * 4));
    const res = post(`/api/events/${eventId}/holds`, { buyerId, seatIds }, 'hold');
    if (res.status === 201) {
      return res.json();
    }
    if (res.status !== 409) {
      return null;
    }
  }
  return null;
}

function post(path, body, name) {
  return track(http.post(BASE_URL + path, JSON.stringify(body), { headers: JSON_HEADERS, tags: { name } }));
}

function track(res) {
  if (res.status === 500) {
    http500.add(1);
  }
  return res;
}

// The IDs of `count` different seats chosen at random (fewer if there are not that many).
function pick(seats, count) {
  const pool = seats.slice();
  const chosen = [];
  while (chosen.length < count && pool.length > 0) {
    const index = Math.floor(Math.random() * pool.length);
    chosen.push(pool.splice(index, 1)[0].id);
  }
  return chosen;
}

// A random version-4 UUID. Not cryptographic, and it does not need to be: it only names a buyer.
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
