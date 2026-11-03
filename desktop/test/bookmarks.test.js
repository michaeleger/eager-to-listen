import { test } from 'node:test';
import assert from 'node:assert/strict';
import { emptyLibrary, newPodcast, newEpisode } from '../src/core/library.js';
import { serialize, parse, merge } from '../src/core/sync.js';
import { parseTranscript, excerptAt } from '../src/core/transcript.js';
import {
  captureBookmark, updateBookmark, removeBookmarks, isArchived, runArchive, parseArchive,
  shareMarkdown, momentUrl, formatTs, isArchiveFileName, ARCHIVE_FILE, LEAD_IN_SECONDS,
} from '../src/core/bookmarks.js';

function lib() {
  const L = emptyLibrary();
  L.deviceId = 'desktop-test';
  L.settings.syncFolder = 'gd:Sync';
  L.podcasts.F = newPodcast({ id: 'F', title: 'Eager to Be Healthy', feedUrl: 'https://x/feed' });
  L.episodes['F::1'] = newEpisode({
    id: 'F::1', podcastId: 'F', title: 'JR Rennie', audioUrl: 'https://x/1.mp3', link: 'https://x/ep1',
    transcriptUrl: 'https://x/1.vtt', transcriptType: 'text/vtt', durationSeconds: 600,
  });
  return L;
}
const roundTrip = (from, to, dev, at) => merge(to, parse(JSON.stringify(serialize(from, dev, at))));

test('capture backs up 15 seconds and leaves the note empty', () => {
  const L = lib();
  const c = captureBookmark(L, { episode: L.episodes['F::1'], podcast: L.podcasts.F, positionSeconds: 130, now: 1000, id: 'b1' });
  assert.equal(c.timestampSeconds, 130 - LEAD_IN_SECONDS);
  assert.equal(c.note, '');
  assert.equal(captureBookmark(L, { episode: L.episodes['F::1'], positionSeconds: 4, id: 'b2' }).timestampSeconds, 0);
});

test('note edits: the newer edit wins in both directions', () => {
  const A = lib(), B = lib();
  captureBookmark(A, { episode: A.episodes['F::1'], positionSeconds: 100, now: 1000, id: 'b1' });
  roundTrip(A, B, 'A', 1100);
  updateBookmark(B, 'b1', { note: 'great line for the blog' }, 2000);
  roundTrip(A, B, 'A', 2100); // A's older copy must not wipe B's note
  assert.equal(B.inspirations.b1.note, 'great line for the blog');
  roundTrip(B, A, 'B', 2200);
  assert.equal(A.inspirations.b1.note, 'great line for the blog');
});

test('delete / clear syncs and does not come back; a later edit elsewhere still wins', () => {
  const A = lib(), B = lib();
  captureBookmark(A, { episode: A.episodes['F::1'], positionSeconds: 100, now: 1000, id: 'b1' });
  captureBookmark(A, { episode: A.episodes['F::1'], positionSeconds: 200, now: 1000, id: 'b2' });
  roundTrip(A, B, 'A', 1100);
  removeBookmarks(B, ['b1'], 3000);
  roundTrip(A, B, 'A', 3100);
  assert.equal(B.inspirations.b1, undefined, 'not resurrected by the other device');
  roundTrip(B, A, 'B', 3200);
  assert.equal(A.inspirations.b1, undefined, 'removal reaches the other device');
  assert.ok(A.inspirations.b2);
  // editing after a removal on another device keeps it
  updateBookmark(A, 'b2', { note: 'keep' }, 5000);
  removeBookmarks(B, ['b2'], 4000);
  roundTrip(B, A, 'B', 5100);
  assert.equal(A.inspirations.b2.note, 'keep');
});

test('0.9.2 files without edit times still merge', () => {
  const L = lib();
  merge(L, parse('{"format":2,"inspirations":[{"id":"old","episodeId":"F::1","timestampSeconds":5,"createdAt":42}]}'));
  assert.equal(L.inspirations.old.updatedAt, 42);
});

test('transcripts: exact excerpt from VTT, estimated from Riverside-style plain text', () => {
  const vtt = 'WEBVTT\n\n00:01:50.000 --> 00:01:58.000\nBefore the moment.\n\n00:02:00.000 --> 00:02:10.000\nThe <b>moment</b> itself.\n\n00:05:00.000 --> 00:05:05.000\nMuch later.';
  const ex = excerptAt(parseTranscript(vtt, 'text/vtt'), 115);
  assert.equal(ex.timing, 'exact');
  assert.equal(ex.text, 'Before the moment. The moment itself.');
  const plain = Array.from({ length: 10 }, (_, i) => `Speaker ${i % 2 ? 'B' : 'A'}\nParagraph ${i} words words words words`).join('\n\n');
  const p = parseTranscript(plain, 'text/plain', 1000);
  assert.equal(p.estimated, true);
  const ex2 = excerptAt(p, 500);
  assert.equal(ex2.timing, 'estimated');
  assert.ok(ex2.text.includes('Paragraph 5'));
});

function fakeFolder(files = {}) {
  return {
    files,
    list: async () => Object.keys(files),
    read: async (_t, n) => files[n],
    write: async (_t, n, text) => { files[n] = text; },
    remove: async (_t, n) => { delete files[n]; },
    transcript: async () => parseTranscript('WEBVTT\n\n00:01:20.000 --> 00:01:30.000\nGood stuff here.', 'text/vtt'),
  };
}

test('archive: every field, merged with the file and its Drive copies, removals marked not dropped', async () => {
  const L = lib();
  captureBookmark(L, { episode: L.episodes['F::1'], podcast: L.podcasts.F, positionSeconds: 100, now: 1000, id: 'b1' });
  updateBookmark(L, 'b1', { note: 'blog idea' }, 1500);
  const phoneLine = JSON.stringify({ type: 'bookmark', v: 1, id: 'p1', createdAt: 500, updatedAt: 500, removedAt: null, show: { title: 'Other' }, episode: { title: 'X' }, timestampSeconds: 9, note: 'from phone' });
  const folder = fakeFolder({ [ARCHIVE_FILE]: phoneLine + '\nnot json\n', 'eagertolisten_bookmarks (1).jsonl': JSON.stringify({ id: 'p2', createdAt: 600, updatedAt: 600 }) + '\n' });

  assert.equal(isArchived(L, L.inspirations.b1), false);
  await runArchive(L, folder, { now: 2000 });
  assert.equal(isArchived(L, L.inspirations.b1), true, 'safe to clear once in Drive');
  assert.ok(!('eagertolisten_bookmarks (1).jsonl' in folder.files), 'conflict copy merged and removed');

  const { records, bad } = parseArchive(folder.files[ARCHIVE_FILE]);
  assert.deepEqual([...records.keys()].sort(), ['b1', 'p1', 'p2']);
  assert.deepEqual(bad, ['not json'], 'unreadable lines kept, not dropped');
  const r = records.get('b1');
  assert.equal(r.show.title, 'Eager to Be Healthy');
  assert.equal(r.episode.link, 'https://x/ep1');
  assert.equal(r.episode.audioUrl, 'https://x/1.mp3');
  assert.equal(r.audioAtMoment, 'https://x/1.mp3#t=85');
  assert.equal(r.timestamp, '01:25');
  assert.equal(r.transcript.url, 'https://x/1.vtt');
  assert.equal(r.transcript.excerpt, 'Good stuff here.');
  assert.equal(r.note, 'blog idea');

  // an edit makes it "not archived" until the next pass
  updateBookmark(L, 'b1', { note: 'blog idea, longer' }, 3000);
  assert.equal(isArchived(L, L.inspirations.b1), false);

  // clearing keeps the archive line, marked removed
  removeBookmarks(L, ['b1'], 4000);
  await runArchive(L, folder, { now: 4000 });
  const after = parseArchive(folder.files[ARCHIVE_FILE]).records.get('b1');
  assert.equal(after.removedAt, 4000);
  assert.equal(after.note, 'blog idea, longer', 'the last edit made it into the archive before the clear');
});

test('share list: grouped, timestamp order, moment links, transcript flagged when estimated', () => {
  const L = lib();
  captureBookmark(L, { episode: L.episodes['F::1'], positionSeconds: 300, now: 1000, id: 'b2' });
  captureBookmark(L, { episode: L.episodes['F::1'], positionSeconds: 100, now: 2000, id: 'b1' });
  updateBookmark(L, 'b1', { note: 'open with this' }, 2500);
  L.bookmarkMeta = { b1: { transcriptExcerpt: 'So I quit my job.', timing: 'estimated' } };
  const md = shareMarkdown(L, Object.values(L.inspirations), { now: Date.UTC(2026, 10, 3) });
  assert.match(md, /^# Podcast bookmarks \(2\)/);
  assert.match(md, /## Eager to Be Healthy\n\n### JR Rennie\n\[Episode page\]\(https:\/\/x\/ep1\) · \[Audio\]\(https:\/\/x\/1\.mp3\) · \[Transcript\]\(https:\/\/x\/1\.vtt\)/);
  assert.ok(md.indexOf('[01:25]') < md.indexOf('[04:45]'), 'timestamp order');
  assert.match(md, /- \*\*\[01:25\]\(https:\/\/x\/1\.mp3#t=85\)\*\* open with this\n  > So I quit my job\. \*\(transcript position estimated\)\*/);
});

test('helpers', () => {
  assert.equal(formatTs(3725), '1:02:05');
  assert.equal(momentUrl('https://x/a.mp3#t=4', 10), 'https://x/a.mp3#t=10');
  assert.ok(isArchiveFileName('eagertolisten_bookmarks (2).jsonl'));
  assert.ok(!isArchiveFileName('eagertolisten_sync.json'));
});
