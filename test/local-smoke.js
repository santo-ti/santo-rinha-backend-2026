// Local smoke for the UDS + HAProxy topology (NOT the official test). Validates the stack
// boots, /fraud-score answers 200 through the LB over UDS, keep-alive holds, and nothing
// 5xxs under light concurrency. Absolute latency is NOT meaningful here (Docker Desktop does
// not enforce the contest CPU limits) — this only proves the wiring and zero-error.
import http from 'k6/http';
import { check } from 'k6';

const TARGET = __ENV.TARGET || 'http://host.docker.internal:9999';

export const options = {
  scenarios: {
    load: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [{ duration: '20s', target: 600 }],
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
};

const payload = JSON.stringify({
  id: 'tx-1',
  transaction: { amount: 41.12, installments: 2, requested_at: '2026-03-11T18:45:53Z' },
  customer: { avg_amount: 82.24, tx_count_24h: 3, known_merchants: ['MERC-003', 'MERC-016'] },
  merchant: { id: 'MERC-016', mcc: '5411', avg_amount: 60.25 },
  terminal: { is_online: false, card_present: true, km_from_home: 29.23 },
  last_transaction: null,
});

const params = { headers: { 'Content-Type': 'application/json' }, timeout: '2001ms' };

export default function () {
  const r = http.post(`${TARGET}/fraud-score`, payload, params);
  check(r, {
    'status 200': (x) => x.status === 200,
    'has fraud_score': (x) => x.body && x.body.indexOf('fraud_score') >= 0,
  });
}
