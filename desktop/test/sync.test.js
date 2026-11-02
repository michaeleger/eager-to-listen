// Ported from SyncManagerTest.kt, plus the R2.7 tombstone cases. Every round trip goes through
// JSON text, the way the file travels between devices.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { emptyLibrary, newPodcast, newEpisode, removeFromQueue, applyFeedRefresh } from '../src/core/library.js';
import { serialize, parse, merge, isSyncFileName } from '../src/core/sync.js';

const pod = (id, o = {}) => newPodcast({
  id, title: `Feed ${id}`, feedUrl: `https://example.com/${id}/rss`, category: 'Health', subscribedAt: 1000, settingsUpdatedAt: 1000, ...o,
});
const epi = (id, podcastId, o = {}) => newEpisode({
  id, podcastId, title: `Episode ${id}`, audioUrl: `https://example.com/${id}.mp3`, publishedAt: 1000, durationSeconds: 600, updatedAt: 1000, ...o,
});
const roundTrip = (from, to, device, at) => merge(to, parse(JSON.stringify(serialize(from, device, at))));

test('completed episode and unsubscribe survive a round trip', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  for (const L of [A, B]) { L.podcasts.feedF = pod('feedF'); L.episodes['feedF::epX'] = epi('feedF::epX', 'feedF'); }
  Object.assign(A.episodes['feedF::epX'], { playbackPositionSeconds: 600, isCompleted: true, completedAt: 2000, updatedAt: 2000 });
  A.podcasts.feedF.unsubscribedAt = 2000;

  roundTrip(A, B, 'A', 2000);
  assert.equal(B.episodes['feedF::epX'].isCompleted, true);
  assert.equal(B.episodes['feedF::epX'].completedAt, 2000);
  assert.equal(B.podcasts.feedF.unsubscribedAt, 2000);

  roundTrip(B, A, 'B', 3000);
  assert.notEqual(A.podcasts.feedF.unsubscribedAt, null, 'must not be resurrected');
});

test('re-subscribe beats an older unsubscribe tombstone', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  A.podcasts.feedR = pod('feedR', { unsubscribedAt: 2000 });
  B.podcasts.feedR = pod('feedR', { unsubscribedAt: null, subscribedAt: 5000 });
  roundTrip(A, B, 'A', 2000);
  assert.equal(B.podcasts.feedR.unsubscribedAt, null);
  roundTrip(B, A, 'B', 5000);
  assert.equal(A.podcasts.feedR.unsubscribedAt, null);
  assert.equal(A.podcasts.feedR.subscribedAt, 5000);
  roundTrip(A, B, 'A', 6000);
  assert.equal(B.podcasts.feedR.unsubscribedAt, null);
});

test('older dismissal does not override a newer restore; newer dismissal applies', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  A.podcasts.feedN = pod('feedN'); B.podcasts.feedN = pod('feedN');
  A.episodes['feedN::ep1'] = epi('feedN::ep1', 'feedN', { updatedAt: 2000, isDismissed: true, dismissReason: 'EXPIRED_NEWS' });
  B.episodes['feedN::ep1'] = epi('feedN::ep1', 'feedN', { updatedAt: 3000, playbackPositionSeconds: 10 });
  roundTrip(A, B, 'A', 2000);
  assert.equal(B.episodes['feedN::ep1'].isDismissed, false);
  roundTrip(B, A, 'B', 3000);
  assert.equal(A.episodes['feedN::ep1'].isDismissed, false);
  assert.equal(A.episodes['feedN::ep1'].dismissReason, null);

  const C = emptyLibrary(), D = emptyLibrary();
  C.podcasts.feedD = pod('feedD'); D.podcasts.feedD = pod('feedD');
  C.episodes['feedD::ep1'] = epi('feedD::ep1', 'feedD', { updatedAt: 4000, isDismissed: true, dismissReason: 'UNPLAYABLE' });
  D.episodes['feedD::ep1'] = epi('feedD::ep1', 'feedD', { updatedAt: 1000 });
  roundTrip(C, D, 'C', 4000);
  assert.equal(D.episodes['feedD::ep1'].isDismissed, true);
  assert.equal(D.episodes['feedD::ep1'].dismissReason, 'UNPLAYABLE');
});

test('feed rules: last writer wins', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  A.podcasts.feed1 = pod('feed1', { settingsUpdatedAt: 5000, priority: 1, playbackSpeed: 1.75, skipIntroSeconds: 30 });
  B.podcasts.feed1 = pod('feed1', { settingsUpdatedAt: 2000, priority: 4 });
  roundTrip(A, B, 'A', 5000);
  assert.equal(B.podcasts.feed1.priority, 1);
  assert.equal(B.podcasts.feed1.playbackSpeed, 1.75);
  assert.equal(B.podcasts.feed1.skipIntroSeconds, 30);
  assert.equal(B.podcasts.feed1.settingsUpdatedAt, 5000);
});

test('explicit unplayed reverts an older completion', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  A.podcasts.feed1 = pod('feed1'); B.podcasts.feed1 = pod('feed1');
  A.episodes['feed1::ep1'] = epi('feed1::ep1', 'feed1', { isCompleted: true, completedAt: 1000, updatedAt: 1000, playbackPositionSeconds: 600 });
  B.episodes['feed1::ep1'] = epi('feed1::ep1', 'feed1', { updatedAt: 3000, dismissReason: 'UNPLAYED' });
  roundTrip(B, A, 'B', 3000);
  const e = A.episodes['feed1::ep1'];
  assert.equal(e.isCompleted, false);
  assert.equal(e.completedAt, null);
  assert.equal(e.playbackPositionSeconds, 0);
});

test('pending states wait for the feed, then apply on refresh', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  A.podcasts.feed2 = pod('feed2');
  A.episodes['feed2::ep2'] = epi('feed2::ep2', 'feed2', { isCompleted: true, completedAt: 4000, updatedAt: 4000 });
  const sum = roundTrip(A, B, 'A', 4000);
  assert.deepEqual(sum.newlySubscribed, ['feed2']);
  assert.ok(B.podcasts.feed2);
  assert.equal(B.episodes['feed2::ep2'], undefined);
  assert.equal(B.pending['feed2::ep2'].completed, true);

  applyFeedRefresh(B, 'feed2', { podcast: {}, episodes: [{ id: 'feed2::ep2', title: 'Ep 2', audioUrl: 'x.mp3', publishedAt: 1000 }] });
  assert.equal(B.episodes['feed2::ep2'].isCompleted, true);
  assert.equal(B.episodes['feed2::ep2'].completedAt, 4000);
  assert.equal(B.pending['feed2::ep2'], undefined);
});

test('manual queue items merge', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  for (const L of [A, B]) { L.podcasts.feed3 = pod('feed3'); L.episodes['feed3::ep3'] = epi('feed3::ep3', 'feed3'); }
  A.queue.push({ episodeId: 'feed3::ep3', podcastId: 'feed3', orderIndex: 0, isManual: true, manualIndex: 0, addedAt: 1500 });
  roundTrip(A, B, 'A', 1500);
  const q = B.queue.find((x) => x.episodeId === 'feed3::ep3');
  assert.ok(q && q.isManual);
  assert.equal(q.addedAt, 1500);
});

// --- R2.7 ---
const seedPinned = (L, id, addedAt) => {
  L.podcasts.feedP = pod('feedP');
  L.episodes[id] = epi(id, 'feedP');
  L.queue.push({ episodeId: id, podcastId: 'feedP', orderIndex: 0, addedAt, isManual: true, manualIndex: 0 });
};

test('a pin removed on one device does not come back', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  seedPinned(A, 'feedP::ep1', 1000); seedPinned(B, 'feedP::ep1', 1000);
  removeFromQueue(B, 'feedP::ep1', { now: 2000 });
  assert.equal(B.unpins['feedP::ep1'], 2000);

  roundTrip(A, B, 'A', 2500);
  assert.ok(!B.queue.some((q) => q.episodeId === 'feedP::ep1'), 'stays removed on B');
  roundTrip(B, A, 'B', 3000);
  assert.ok(!A.queue.some((q) => q.episodeId === 'feedP::ep1'), 'A drops it too');
  assert.equal(A.unpins['feedP::ep1'], 2000);
});

test('re-pinning after a removal wins', () => {
  const A = emptyLibrary(), B = emptyLibrary();
  seedPinned(A, 'feedP::ep2', 5000);
  B.podcasts.feedP = pod('feedP'); B.episodes['feedP::ep2'] = epi('feedP::ep2', 'feedP');
  B.unpins['feedP::ep2'] = 2000;
  roundTrip(A, B, 'A', 5000);
  assert.ok(B.queue.some((q) => q.episodeId === 'feedP::ep2' && q.isManual));
  roundTrip(B, A, 'B', 6000);
  assert.ok(A.queue.some((q) => q.episodeId === 'feedP::ep2'));
});

test('reads a file written by Android 0.9.2 (no tombstones, nulls, doubles)', () => {
  const android = `{
    "format": 2, "deviceId": "pixel", "writtenAt": 1762000000000,
    "podcasts": [{"id": "https://api.riverside.com/hosting/drlxDc7x.rss", "feedUrl": "https://api.riverside.com/hosting/drlxDc7x.rss",
      "title": "Eager to Be Healthy", "artworkUrl": "", "author": "Michael", "description": "",
      "settingsUpdatedAt": 0, "subscribedAt": 1761000000000, "unsubscribedAt": null,
      "rules": {"priority": 1, "playOrderStrategy": "EVERGREEN", "skipIntroSeconds": 0, "skipOutroSeconds": 0,
        "playbackSpeed": 1.25, "autoAddToQueue": true, "maxEpisodesInQueue": 5, "consecutivePlayLimit": -1,
        "newsRetentionDays": 3, "category": "Health", "customDonationUrl": null}}],
    "episodes": [{"id": "https://api.riverside.com/hosting/drlxDc7x.rss::abc", "pos": 312, "completed": false,
      "completedAt": null, "dismissed": false, "dismissReason": null, "skippedAt": null, "skipCount": 0, "updatedAt": 1762000000000}],
    "manualQueue": [], "inspirations": []
  }`;
  const s = parse(android);
  assert.equal(s.format, 2);
  assert.equal(s.podcasts[0].rules.playbackSpeed, 1.25);
  assert.equal(s.podcasts[0].unsubscribedAt, null);
  assert.deepEqual(s.manualQueueRemoved, {});
  const L = emptyLibrary();
  const sum = merge(L, s);
  assert.equal(sum.newlySubscribed.length, 1);
  assert.equal(L.pending['https://api.riverside.com/hosting/drlxDc7x.rss::abc'].pos, 312);
});

test('sync file names follow the Android rules', () => {
  assert.ok(isSyncFileName('eagertolisten_sync.json'));
  assert.ok(isSyncFileName('eagertolisten_sync (1).json'));
  assert.ok(!isSyncFileName('eagertolisten_sync.tmp.json'));
  assert.ok(!isSyncFileName('eagertolisten_sync.bak.json'));
  assert.ok(!isSyncFileName('doggcatcher_sync_state.json'));
});

test('local folder shows never leave the PC', () => {
  const L = emptyLibrary();
  L.podcasts['local:/home/me/Audio'] = pod('local:/home/me/Audio');
  L.episodes['local:/home/me/Audio::a.mp3'] = epi('local:/home/me/Audio::a.mp3', 'local:/home/me/Audio', { playbackPositionSeconds: 5 });
  const out = serialize(L, 'pc', 1);
  assert.equal(out.podcasts.length, 0);
  assert.equal(out.episodes.length, 0);
});
