import { test } from 'node:test';
import assert from 'node:assert/strict';
import { emptyLibrary, newPodcast, newEpisode, rebuildQueue, setSortRule, hotList, playedList } from '../src/core/library.js';

function lib3() {
  const L = emptyLibrary();
  L.settings.globalConsecutiveLimit = 1;
  L.podcasts.A = newPodcast({ id: 'A', title: 'A', priority: 1, maxEpisodesInQueue: 5 });
  L.podcasts.B = newPodcast({ id: 'B', title: 'B', priority: 3, maxEpisodesInQueue: 5 });
  for (const [id, p, pub] of [['a1', 'A', 100], ['a2', 'A', 300], ['b1', 'B', 200], ['b2', 'B', 400]]) {
    L.episodes[id] = newEpisode({ id, podcastId: p, publishedAt: pub });
  }
  return L;
}
const ids = (L) => [...L.queue].sort((a, b) => a.orderIndex - b.orderIndex).map((q) => q.episodeId);

test('Radio U fair flow is the default', () => {
  const L = lib3();
  rebuildQueue(L, { now: 1000 });
  assert.deepEqual(ids(L), ['a2', 'b2', 'a1', 'b1']);
});

test('other orders sort the programmed part; the top episode and pins stay put', () => {
  const L = lib3();
  setSortRule(L, 'DATE_ASC', { now: 1000 });
  // a2 stays on top (orderIndex 0, as on Android), the rest oldest first
  assert.deepEqual(ids(L), ['a2', 'a1', 'b1', 'b2']);
  setSortRule(L, 'PRIORITY_THEN_DATE_DESC', { now: 1000 });
  assert.deepEqual(ids(L), ['a2', 'a1', 'b2', 'b1']);
  setSortRule(L, 'DATE_DESC', { now: 1000 });
  assert.deepEqual(ids(L), ['a2', 'b2', 'b1', 'a1']);
  // the choice sticks through later rebuilds (new episodes, finishing one)
  rebuildQueue(L, { now: 2000 });
  assert.deepEqual(ids(L), ['a2', 'b2', 'b1', 'a1']);
  setSortRule(L, 'NOT_A_RULE');
  assert.equal(L.settings.queueSortRule, 'PROGRAMMED_FAIR');
});

test('Hot List is Priority 1 unplayed, newest first; Played is newest finished first', () => {
  const L = lib3();
  L.episodes.a1.isCompleted = true;
  L.episodes.a1.completedAt = 50;
  L.episodes.b1.isCompleted = true;
  L.episodes.b1.completedAt = 70;
  assert.deepEqual(hotList(L).map((e) => e.id), ['a2']);
  assert.deepEqual(playedList(L).map((e) => e.id), ['b1', 'a1']);
});

test('finishing keeps the real spot when the feed gave no length', async () => {
  const { markFinished } = await import('../src/core/library.js');
  const L = lib3();
  L.episodes.a1.durationSeconds = 0;
  markFinished(L, 'a1', 5000, { positionSec: 1834.6 });
  assert.equal(L.episodes.a1.isCompleted, true);
  assert.equal(L.episodes.a1.playbackPositionSeconds, 1834);
  L.episodes.a2.durationSeconds = 3600;
  markFinished(L, 'a2', 5000, { positionSec: 3590 });
  assert.equal(L.episodes.a2.playbackPositionSeconds, 3600);
});
