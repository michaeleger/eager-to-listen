// Ported from FeedUrlAndEpisodeIdTest.kt and FeedUrlSameFeedTest.kt
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalize, sameFeed, episodeId } from '../src/core/feedUrl.js';

test('normalization', () => {
  for (const u of ['https://example.com/podcast/rss/', 'HTTPS://Example.COM/podcast/rss', '  https://example.com/podcast/rss  ']) {
    assert.equal(normalize(u), 'https://example.com/podcast/rss');
  }
});

test('normalization is idempotent', () => {
  for (const u of ['https://Example.com/feed/podcast//', 'HTTPS://example.com:443/rss/', 'https://example.com/', 'feed1']) {
    const once = normalize(u);
    assert.equal(normalize(once), once);
  }
  assert.equal(normalize('https://Example.com/feed/podcast//'), 'https://example.com/feed/podcast');
  assert.equal(normalize('HTTPS://example.com:443/rss/'), 'https://example.com/rss');
  assert.equal(normalize('https://example.com/'), 'https://example.com/');
  assert.equal(normalize('feed1'), 'feed1');
});

test('java.net.URI edge cases match Android', () => {
  assert.equal(normalize('https://example.com:8080/a/?x=1#f'), 'https://example.com:8080/a?x=1#f');
  assert.equal(normalize('https://user@Example.com/rss'), 'https://example.com/rss');
  assert.equal(normalize('https://example.com/a b/'), 'https://example.com/a b'); // URI rejects spaces: fallback
  assert.equal(normalize('https://bad_host.example.com/rss/'), 'https://bad_host.example.com/rss'); // no server host: fallback
  assert.equal(normalize('local:content://tree/x/'), 'local:content://tree/x');
  assert.equal(normalize(''), '');
});

test('episode ID trims guid whitespace', () => {
  assert.equal(episodeId('https://example.com/rss/', '  abc\n', 'https://example.com/1.mp3'), 'https://example.com/rss::abc');
});

test('episode ID consistency', () => {
  const feed = 'https://example.com/feed.xml/';
  const enc = 'https://example.com/media/ep1.mp3';
  assert.equal(episodeId(feed, 'episode-12345', enc), 'https://example.com/feed.xml::episode-12345');
  assert.equal(episodeId(feed, null, enc), 'https://example.com/feed.xml::https://example.com/media/ep1.mp3');
  assert.equal(episodeId(feed, '   ', enc), 'https://example.com/feed.xml::https://example.com/media/ep1.mp3');
  assert.notEqual(episodeId('https://feed-a.com/rss', '1', 'a'), episodeId('https://feed-b.com/rss', '1', 'b'));
});

test('sameFeed', () => {
  assert.equal(sameFeed('https://feeds.example.com/show.xml', 'http://feeds.example.com/show.xml'), true);
  assert.equal(sameFeed('https://Feeds.Example.com/show/', 'https://feeds.example.com/show'), true);
  assert.equal(sameFeed('  https://feeds.example.com/show  ', 'https://feeds.example.com/show'), true);
  assert.equal(sameFeed('https://feeds.example.com/show-a', 'https://feeds.example.com/show-b'), false);
  assert.equal(sameFeed('', ''), false);
});
