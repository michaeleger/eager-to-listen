// Ported from QueueEngineTest.kt
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildQueue } from '../src/core/queueEngine.js';

const feed = (id, o = {}) => ({
  id, title: o.title ?? id, priority: 3, strategy: 'EVERGREEN', newsRetentionDays: 3, consecutiveLimit: 1,
  maxInQueue: 50, autoAdd: true, lastTurnAt: 0, subscribedAt: 1000, ...o,
});
const ep = (id, feedId, publishedAt, o = {}) => ({
  id, feedId, publishedAt, feedOrder: 0, positionSec: 0, isCompleted: false, isDismissed: false, skippedAt: null, skipCount: 0, ...o,
});
const q = (feeds, episodes, pinned = [], nowPlayingId = null, now = 1000, globalLimit = 10, defaultNewsDays = 3) =>
  buildQueue({ feeds, episodes, pinned, nowPlayingId, now, globalLimit, defaultNewsDays });

test('1 equal turns: round robin by priority', () => {
  const eps = [];
  for (const f of ['A', 'B', 'C']) for (const i of [1, 2, 3]) eps.push(ep(`${f}${i}`, f, i * 100));
  const out = q([feed('C', { priority: 3 }), feed('A', { priority: 1 }), feed('B', { priority: 2 })], eps, [], null, 10_000, 1);
  assert.equal(out.length, 9);
  assert.deepEqual(out.map((x) => x[0]).join(''), 'ABCABCABC');
});

test('2 fresh news precedes P1; expired contributes nothing', () => {
  const now = 1_000_000_000, day = 86_400_000;
  const out = q(
    [feed('NEWS', { strategy: 'NEWS', newsRetentionDays: 2 }), feed('P1', { priority: 1 })],
    [ep('N_fresh', 'NEWS', now - day), ep('N_expired', 'NEWS', now - 3 * day), ep('P1_ep', 'P1', now)],
    [], null, now, 2, 2,
  );
  assert.deepEqual(out, ['N_fresh', 'P1_ep']);
});

test('3 strategy contrast', () => {
  const now = 1_000_000;
  const catalog = () => Array.from({ length: 10 }, (_, k) => ep(`e${k + 1}`, 'F', (k + 1) * 100, { feedOrder: 10 - (k + 1) }));
  const ever = feed('F', { maxInQueue: 50, consecutiveLimit: 50 });
  assert.deepEqual(q([ever], catalog(), [], null, now, 50), ['e10', 'e9', 'e8', 'e7', 'e6', 'e5', 'e4', 'e3', 'e2', 'e1']);

  const heard = catalog().map((e) => (['e10', 'e8', 'e5'].includes(e.id) ? { ...e, isCompleted: true } : e));
  const q3b = q([ever], heard, [], null, now, 50);
  assert.deepEqual(q3b, ['e9', 'e7', 'e6', 'e4', 'e3', 'e2', 'e1']);

  const e11 = ep('e11', 'F', 1100, { feedOrder: 0 });
  assert.deepEqual(q([ever], [...heard, e11], [], null, now, 50), ['e11', 'e9', 'e7', 'e6', 'e4', 'e3', 'e2', 'e1']);

  const rev = feed('F', { strategy: 'REVERSE_CHRONOLOGICAL', maxInQueue: 50, consecutiveLimit: 50 });
  assert.deepEqual(q([rev], heard, [], null, now, 50), q3b);

  const chrono = feed('F', { strategy: 'CHRONOLOGICAL', maxInQueue: 50, consecutiveLimit: 50 });
  assert.deepEqual(q([chrono], [...heard, e11], [], null, now, 50), ['e1', 'e2', 'e3', 'e4', 'e6', 'e7', 'e9', 'e11']);

  const skips = catalog().map((e) =>
    e.id === 'e10' ? { ...e, skippedAt: 500, skipCount: 1 } : e.id === 'e1' ? { ...e, skippedAt: 600, skipCount: 2 } : e);
  assert.deepEqual(q([ever], skips, [], null, now, 50), ['e9', 'e8', 'e7', 'e6', 'e5', 'e4', 'e3', 'e2', 'e10', 'e1']);

  const out = q([feed('A', { lastTurnAt: 1000 }), feed('B', { lastTurnAt: 0 })], [ep('a1', 'A', 100), ep('b1', 'B', 100)], [], null, now, 1);
  assert.deepEqual(out, ['b1', 'a1']);
});

test('4 completed or dismissed never appear', () => {
  const out = q([feed('F')], [ep('ep_active', 'F', 100), ep('ep_completed', 'F', 200, { isCompleted: true }), ep('ep_dismissed', 'F', 300, { isDismissed: true })]);
  assert.deepEqual(out, ['ep_active']);
});

test('5 pins survive and keep order, even with auto-add off', () => {
  const out = q(
    [feed('F_off', { autoAdd: false }), feed('F_on')],
    [ep('pinned_1', 'F_off', 100), ep('pinned_2', 'F_on', 200), ep('prog_1', 'F_on', 300)],
    [{ episodeId: 'pinned_2', manualIndex: 2 }, { episodeId: 'pinned_1', manualIndex: 1 }],
  );
  assert.deepEqual(out, ['pinned_1', 'pinned_2', 'prog_1']);
});

test('6 maxInQueue respected; pins do not count', () => {
  const out = q([feed('F', { maxInQueue: 2, consecutiveLimit: 10 })],
    [ep('pin', 'F', 100), ep('e1', 'F', 200), ep('e2', 'F', 300), ep('e3', 'F', 400)], [{ episodeId: 'pin', manualIndex: 0 }]);
  assert.deepEqual(out, ['pin', 'e3', 'e2']);
});

test('7 now playing stays first and trims its feed first round', () => {
  const out = q([feed('A', { consecutiveLimit: 2 }), feed('B', { consecutiveLimit: 2 })],
    [ep('a_now', 'A', 100), ep('a1', 'A', 200), ep('a2', 'A', 300), ep('b1', 'B', 100), ep('b2', 'B', 200)], [], 'a_now', 1000, 2);
  assert.deepEqual(out, ['a_now', 'a2', 'b2', 'b1', 'a1']);
});

test('8 determinism', () => {
  const eps = Array.from({ length: 15 }, (_, k) => ep(`ep${k + 1}`, ['A', 'B', 'C'][(k + 1) % 3], (k + 1) * 100));
  const feeds = [feed('A', { priority: 1 }), feed('B', { priority: 2 }), feed('C', { priority: 3 })];
  assert.deepEqual(q(feeds, eps, [], null, 5000, 1), q(feeds, eps, [], null, 5000, 1));
});
