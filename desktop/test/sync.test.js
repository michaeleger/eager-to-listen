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
  assert.ok(!isSyncFileName('some_other_sync_state.json'));
});

test('local folder shows never leave the PC', () => {
  const L = emptyLibrary();
  L.podcasts['local:/home/me/Audio'] = pod('local:/home/me/Audio');
  L.episodes['local:/home/me/Audio::a.mp3'] = epi('local:/home/me/Audio::a.mp3', 'local:/home/me/Audio', { playbackPositionSeconds: 5 });
  const out = serialize(L, 'pc', 1);
  assert.equal(out.podcasts.length, 0);
  assert.equal(out.episodes.length, 0);
});

// ---- the full sync pass against a fake Drive folder ----
import { runSync } from '../src/core/syncRunner.js';

function fakeFolder(files = {}) {
  return {
    files,
    list: async () => Object.keys(files),
    read: async (_t, n) => files[n],
    write: async (_t, json) => { files['eagertolisten_sync.json'] = json; },
    remove: async (_t, n) => { delete files[n]; },
  };
}

test('sync pass merges the phone file and conflict copies, writes one file, removes copies', async () => {
  const phone = emptyLibrary();
  phone.podcasts.feedS = pod('feedS');
  phone.episodes['feedS::e1'] = epi('feedS::e1', 'feedS', { playbackPositionSeconds: 120, updatedAt: 5000 });
  const copy = emptyLibrary();
  copy.podcasts.feedT = pod('feedT');

  const folder = fakeFolder({
    'eagertolisten_sync.json': JSON.stringify(serialize(phone, 'pixel', 5000)),
    'eagertolisten_sync (1).json': JSON.stringify(serialize(copy, 'pixel', 4000)),
    'eagertolisten_sync (2).json': '{ not json',
    'notes.txt': 'ignore me',
  });
  const pc = emptyLibrary();
  pc.deviceId = 'pc-test';
  pc.settings.syncFolder = 'gdrive:EagerToListen';

  const res = await runSync(pc, folder, { now: 6000 });
  assert.deepEqual(res.newlySubscribed.sort(), ['feedS', 'feedT']);
  assert.equal(pc.pending['feedS::e1'].pos, 120);
  assert.ok(!('eagertolisten_sync (1).json' in folder.files), 'merged copy removed');
  assert.ok('eagertolisten_sync (2).json' in folder.files, 'unreadable copy kept');
  assert.ok('notes.txt' in folder.files);
  const written = parse(folder.files['eagertolisten_sync.json']);
  assert.equal(written.deviceId, 'pc-test');
  assert.equal(written.podcasts.length, 2);
  assert.equal(pc.lastSyncAt, 6000);
});

test('an unreadable main sync file stops the pass and is left untouched', async () => {
  const damaged = '{ "format": 2, "podcasts": [';
  for (const folder of [
    fakeFolder({ 'eagertolisten_sync.json': damaged }),
    // A read that fails outright (rclone error, connection dropped)
    { ...fakeFolder({ 'eagertolisten_sync.json': damaged }), read: async () => { throw new Error('rclone: timeout'); } },
  ]) {
    const pc = emptyLibrary();
    pc.deviceId = 'pc-test';
    pc.settings.syncFolder = '/tmp/sync';
    pc.podcasts.feedS = pod('feedS');
    await assert.rejects(runSync(pc, folder, { now: 6000 }), /couldn't be read, so nothing was changed/);
    assert.equal(folder.files['eagertolisten_sync.json'], damaged);
    assert.ok(!pc.lastSyncAt);
  }
});

test('sync pass needs a folder', async () => {
  await assert.rejects(runSync(emptyLibrary(), fakeFolder()), /No sync folder/);
});

test('a fresh PC gets the phone pins once the new show loads, and keeps them in the file it writes', async () => {
  const phone = emptyLibrary();
  phone.podcasts.feedQ = pod('feedQ');
  phone.episodes['feedQ::e9'] = epi('feedQ::e9', 'feedQ', { playbackPositionSeconds: 77, updatedAt: 5000 });
  phone.queue.push({ episodeId: 'feedQ::e9', podcastId: 'feedQ', orderIndex: 0, addedAt: 5000, isManual: true, manualIndex: -1 });
  const folder = fakeFolder({ 'eagertolisten_sync.json': JSON.stringify(serialize(phone, 'pixel', 5000)) });
  const pc = emptyLibrary();
  pc.deviceId = 'pc';
  pc.settings.syncFolder = 'gdrive:X';

  let loaded = [];
  await runSync(pc, folder, {
    now: 6000,
    onNewShows: async (ids) => {
      loaded = ids;
      applyFeedRefresh(pc, 'feedQ', { podcast: {}, episodes: [{ id: 'feedQ::e9', title: 'E9', audioUrl: 'e9.mp3', publishedAt: 900 }] });
    },
  });
  assert.deepEqual(loaded, ['feedQ']);
  const q = pc.queue.find((x) => x.episodeId === 'feedQ::e9');
  assert.ok(q && q.isManual, 'pin lands');
  assert.equal(pc.episodes['feedQ::e9'].playbackPositionSeconds, 77);
  const written = parse(folder.files['eagertolisten_sync.json']);
  assert.ok(written.manualQueue.some((m) => m.episodeId === 'feedQ::e9'), "the phone's pin survives in the file the PC writes");
});

test("Android's float speeds come in clean (1.100000023841858 -> 1.1)", () => {
  const s = parse('{"format":2,"podcasts":[{"id":"f","rules":{"playbackSpeed":1.100000023841858}}]}');
  assert.equal(s.podcasts[0].rules.playbackSpeed, 1.1);
});

// ---------- skip work when nothing changed (syncFingerprint.js, mirrors SyncFingerprint.kt) ----------

import * as FP from '../src/core/syncFingerprint.js';
import { noteArchiveDone } from '../src/core/syncRunner.js';

/** A folder that also reports size and modified time, and counts what was read and written. */
function statFolder(files = {}) {
  const mod = Object.fromEntries(Object.keys(files).map((n) => [n, 1]));
  const f = {
    files,
    reads: 0,
    writes: 0,
    list: async () => Object.keys(files),
    stat: async () => Object.keys(files).map((name) => ({ name, size: files[name].length, modTime: String(mod[name] || 0) })),
    read: async (_t, n) => { f.reads++; return files[n]; },
    write: async (_t, json) => { f.writes++; files['eagertolisten_sync.json'] = json; mod['eagertolisten_sync.json'] = (mod['eagertolisten_sync.json'] || 0) + 1; },
    remove: async (_t, n) => { delete files[n]; },
    /** Another device wrote the file */
    touch: (name, text) => { files[name] = text; mod[name] = (mod[name] || 0) + 1; },
  };
  return f;
}

function pcWith(showId = 'feedS') {
  const pc = emptyLibrary();
  pc.deviceId = 'pc-test';
  pc.settings.syncFolder = '/tmp/sync';
  pc.podcasts[showId] = pod(showId);
  pc.episodes[`${showId}::e1`] = epi(`${showId}::e1`, showId, { playbackPositionSeconds: 10, updatedAt: 1000 });
  return pc;
}

test('fingerprint plan: nothing changed, only this device changed, anything else', () => {
  const base = { force: false, folderNow: 'f1', folderAtLastSync: 'f1', localNow: 'l1', localAtLastSync: 'l1', hasMainFile: true, lastFullSyncAt: 1000, now: 2000 };
  assert.equal(FP.plan(base), 'NOTHING_TO_DO');
  assert.equal(FP.plan({ ...base, localNow: 'l2' }), 'WRITE_ONLY');
  assert.equal(FP.plan({ ...base, folderNow: 'f2' }), 'FULL');
  assert.equal(FP.plan({ ...base, force: true }), 'FULL');
  assert.equal(FP.plan({ ...base, folderAtLastSync: null }), 'FULL');
  assert.equal(FP.plan({ ...base, hasMainFile: false }), 'FULL');
  assert.equal(FP.plan({ ...base, now: 1000 + FP.FULL_SYNC_EVERY_MS + 1 }), 'FULL');
  assert.equal(FP.plan({ ...base, now: 500 }), 'FULL');
});

test('folder fingerprint follows shared files only', () => {
  const a = [{ name: 'eagertolisten_sync.json', size: 10, modTime: 't1' }, { name: 'eagertolisten_bookmarks.jsonl', size: 5, modTime: 't1' }];
  const fp = FP.ofFolder(a);
  assert.equal(FP.ofFolder([...a].reverse().concat([{ name: 'eagertolisten_sync.bak.json', size: 1, modTime: 'x' }, { name: 'notes.txt', size: 1, modTime: 'x' }])), fp);
  assert.notEqual(FP.ofFolder([{ ...a[0], modTime: 't2' }, a[1]]), fp);
  assert.notEqual(FP.ofFolder([...a, { name: 'eagertolisten_sync (1).json', size: 9, modTime: 't1' }]), fp);
  assert.notEqual(FP.ofFolder([a[0], { ...a[1], size: 6 }]), fp);
});

test('local fingerprint ignores only the time stamp', () => {
  const pc = pcWith();
  assert.equal(FP.ofLocalState(serialize(pc, 'pc', 1)), FP.ofLocalState(serialize(pc, 'pc', 2)));
  const fp = FP.ofLocalState(serialize(pc, 'pc', 1));
  pc.episodes['feedS::e1'].playbackPositionSeconds = 11;
  assert.notEqual(FP.ofLocalState(serialize(pc, 'pc', 1)), fp);
});

test('a second sync with nothing changed reads and writes nothing', async () => {
  const pc = pcWith();
  const folder = statFolder();
  const first = await runSync(pc, folder, { now: 10_000 });
  assert.equal(first.plan, 'FULL');
  assert.equal(folder.writes, 1);
  await noteArchiveDone(pc, folder, first.bookmarksFp);

  const second = await runSync(pc, folder, { now: 20_000 });
  assert.equal(second.plan, 'NOTHING_TO_DO');
  assert.equal(second.message, 'Already up to date');
  assert.equal(second.archiveNeeded, false);
  assert.equal(folder.reads, 0);
  assert.equal(folder.writes, 1);
});

test('only this computer changed: write without reading, and skip the archive pass', async () => {
  const pc = pcWith();
  const folder = statFolder();
  const first = await runSync(pc, folder, { now: 10_000 });
  await noteArchiveDone(pc, folder, first.bookmarksFp);

  pc.episodes['feedS::e1'].playbackPositionSeconds = 300;
  pc.episodes['feedS::e1'].updatedAt = 15_000;
  const res = await runSync(pc, folder, { now: 20_000 });
  assert.equal(res.plan, 'WRITE_ONLY');
  assert.equal(res.wrote, true);
  assert.equal(res.archiveNeeded, false);
  assert.equal(folder.reads, 0);
  assert.equal(parse(folder.files['eagertolisten_sync.json']).episodes[0].pos, 300);

  // A bookmark change does need the archive pass
  pc.inspirations.b1 = { id: 'b1', episodeId: 'feedS::e1', podcastId: 'feedS', podcastTitle: 'S', episodeTitle: 'E', timestampSeconds: 5, audioUrl: '', note: 'n', quoteText: '', tags: '', createdAt: 21_000, updatedAt: 21_000 };
  const withBookmark = await runSync(pc, folder, { now: 30_000 });
  assert.equal(withBookmark.plan, 'WRITE_ONLY');
  assert.equal(withBookmark.archiveNeeded, true);
});

test('the phone wrote: full pass; nothing new here afterwards, so no write back (no ping-pong)', async () => {
  const pc = pcWith();
  const folder = statFolder();
  const first = await runSync(pc, folder, { now: 10_000 });
  await noteArchiveDone(pc, folder, first.bookmarksFp);

  // The phone merges our file, adds nothing we lack, and writes its own copy
  const phoneCopy = { ...JSON.parse(folder.files['eagertolisten_sync.json']), deviceId: 'pixel', writtenAt: 12_000 };
  folder.touch('eagertolisten_sync.json', JSON.stringify(phoneCopy));
  const res = await runSync(pc, folder, { now: 20_000 });
  assert.equal(res.plan, 'FULL');
  assert.equal(folder.reads, 1);
  assert.equal(res.wrote, false);
  assert.equal(parse(folder.files['eagertolisten_sync.json']).deviceId, 'pixel');
  // And now it's quiet again
  await noteArchiveDone(pc, folder, res.bookmarksFp);
  assert.equal((await runSync(pc, folder, { now: 30_000 })).plan, 'NOTHING_TO_DO');

  // The phone writes real news: merged, and nothing to send back
  const news = JSON.parse(folder.files['eagertolisten_sync.json']);
  news.writtenAt = 40_000;
  news.episodes[0].pos = 900;
  news.episodes[0].updatedAt = 39_000;
  folder.touch('eagertolisten_sync.json', JSON.stringify(news));
  const merged = await runSync(pc, folder, { now: 50_000 });
  assert.equal(merged.plan, 'FULL');
  assert.equal(pc.episodes['feedS::e1'].playbackPositionSeconds, 900);
  assert.equal(merged.wrote, true, 'our state changed, so our fingerprint moved and the file goes out');
});

test('the Sync button and the 6-hour safety net always read and write', async () => {
  const pc = pcWith();
  const folder = statFolder();
  const first = await runSync(pc, folder, { now: 10_000 });
  await noteArchiveDone(pc, folder, first.bookmarksFp);
  const forced = await runSync(pc, folder, { now: 20_000, force: true });
  assert.equal(forced.plan, 'FULL');
  assert.equal(forced.wrote, true);
  await noteArchiveDone(pc, folder, forced.bookmarksFp);
  const later = await runSync(pc, folder, { now: 20_000 + FP.FULL_SYNC_EVERY_MS + 1 });
  assert.equal(later.plan, 'FULL');
  assert.equal(later.wrote, true);
});

test('changing the sync folder forgets what was remembered about the old one', async () => {
  const pc = pcWith();
  const folder = statFolder();
  await runSync(pc, folder, { now: 10_000 });
  pc.settings.syncFolder = '/tmp/other';
  const res = await runSync(pc, statFolder(), { now: 20_000 });
  assert.equal(res.plan, 'FULL');
  assert.equal(res.wrote, true);
});
