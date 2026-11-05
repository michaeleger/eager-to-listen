import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MEDIA_ERR,
  MAX_NETWORK_RETRIES,
  MAX_OFFLINE_RETRIES,
  classifyError,
  retryDelayMs,
  safeStartPosition,
  pickNextPlayable,
  redactUrls,
  logPlaybackProblem,
  formatPlaybackLog,
} from '../src/core/playbackRecovery.js';

test('a cancelled load is not an error', () => {
  assert.equal(classifyError({ code: MEDIA_ERR.ABORTED }), 'IGNORE');
});

test('a failed download streams instead, once', () => {
  assert.equal(classifyError({ code: MEDIA_ERR.SRC_NOT_SUPPORTED, local: true }), 'STREAM_INSTEAD');
  assert.equal(classifyError({ code: MEDIA_ERR.DECODE, local: true }), 'STREAM_INSTEAD');
  // Already tried: treat it like any other source from here
  assert.equal(classifyError({ code: MEDIA_ERR.NETWORK, local: true, localFallbackTried: true }), 'RETRY_NETWORK');
});

test('online network errors retry twice, then skip', () => {
  assert.equal(classifyError({ code: MEDIA_ERR.NETWORK, retryCount: 0 }), 'RETRY_NETWORK');
  assert.equal(classifyError({ code: MEDIA_ERR.NETWORK, retryCount: MAX_NETWORK_RETRIES - 1 }), 'RETRY_NETWORK');
  assert.equal(classifyError({ code: MEDIA_ERR.NETWORK, retryCount: MAX_NETWORK_RETRIES }), 'SKIP');
});

test('no connection waits for the network instead of skipping', () => {
  assert.equal(classifyError({ code: MEDIA_ERR.NETWORK, retryCount: MAX_NETWORK_RETRIES + 3, online: false }), 'RETRY_OFFLINE');
  // Offline, WebKit can report a fetch failure as "not supported": still just wait
  assert.equal(classifyError({ code: MEDIA_ERR.SRC_NOT_SUPPORTED, retryCount: 5, online: false }), 'RETRY_OFFLINE');
  assert.equal(classifyError({ code: MEDIA_ERR.NETWORK, retryCount: MAX_OFFLINE_RETRIES, online: false }), 'SKIP');
});

test('a source that looks gone: retry once, check the feed, then skip', () => {
  assert.equal(classifyError({ code: MEDIA_ERR.SRC_NOT_SUPPORTED, retryCount: 0 }), 'RETRY_NETWORK');
  assert.equal(classifyError({ code: MEDIA_ERR.SRC_NOT_SUPPORTED, retryCount: 1 }), 'CHECK_FEED');
  assert.equal(classifyError({ code: MEDIA_ERR.SRC_NOT_SUPPORTED, retryCount: 1, feedChecked: true }), 'SKIP');
  assert.equal(classifyError({ code: MEDIA_ERR.DECODE, retryCount: 1 }), 'CHECK_FEED');
});

test('retry delays back off and cap', () => {
  assert.equal(retryDelayMs('RETRY_NETWORK', 1), 1500);
  assert.equal(retryDelayMs('RETRY_NETWORK', 2), 3000);
  assert.equal(retryDelayMs('RETRY_NETWORK', 9), 3000);
  assert.equal(retryDelayMs('RETRY_OFFLINE', 1), 2000);
  assert.equal(retryDelayMs('RETRY_OFFLINE', 3), 8000);
  assert.equal(retryDelayMs('RETRY_OFFLINE', 12), 30_000);
  assert.equal(retryDelayMs('SKIP', 1), 0);
});

test('never start at the very end of an episode', () => {
  assert.equal(safeStartPosition({ resumeAt: 1200, durationSec: 3600 }), 1200);
  assert.equal(safeStartPosition({ resumeAt: 3598, durationSec: 3600 }), 0);
  assert.equal(safeStartPosition({ resumeAt: 3600, durationSec: 3600, skipIntroSec: 30 }), 30);
  assert.equal(safeStartPosition({ resumeAt: 0, durationSec: 3600, skipIntroSec: 30 }), 30);
  // Duration not known yet: trust the saved spot
  assert.equal(safeStartPosition({ resumeAt: 3598, durationSec: 0 }), 3598);
  // Intro skip longer than the episode is ignored
  assert.equal(safeStartPosition({ resumeAt: 0, durationSec: 20, skipIntroSec: 30 }), 0);
});

test('next playable skips failures and stops instead of looping', () => {
  const q = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
  assert.equal(pickNextPlayable(q, { exclude: ['a'] }).id, 'b');
  assert.equal(pickNextPlayable(q, { exclude: ['a'], skipped: new Set(['b']) }).id, 'c');
  assert.equal(pickNextPlayable(q, { exclude: ['a'], skipped: new Set(['b', 'c']) }), null);
});

test('links lose their keys, keep their address', () => {
  assert.equal(redactUrls('https://cdn.example.com/a/ep9.mp3?token=SECRET#t=10'), 'https://cdn.example.com/a/ep9.mp3?[removed]');
  assert.equal(redactUrls('https://user:pw@feeds.example.com/rss'), 'https://feeds.example.com/rss');
  assert.equal(redactUrls('(https://www.patreon.com/rss/show?auth=SECRET)'), '(https://www.patreon.com/rss/show?[removed])');
  assert.equal(redactUrls('https://example.com/feed.xml'), 'https://example.com/feed.xml');
});

test('playback log is newest first, capped and redacted', () => {
  let log = [];
  for (let i = 0; i < 30; i++) log = logPlaybackProblem(log, { event: `e${i}`, url: `https://x.com/a.mp3?key=S${i}` }, 1000 + i);
  assert.equal(log.length, 25);
  assert.equal(log[0].event, 'e29');
  assert.ok(log.every((e) => !e.url.includes('key=')));
  const text = formatPlaybackLog(log.slice(0, 1), 'Header');
  assert.match(text, /^Header\n\n=== /);
  assert.match(text, /Source: https:\/\/x\.com\/a\.mp3\?\[removed\]/);
  assert.match(formatPlaybackLog([]), /No playback problems recorded/);
});
