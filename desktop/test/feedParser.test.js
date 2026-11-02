import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DOMParser } from 'linkedom';
import { parseFeed, parseDuration, parseDate, inferPlayOrderStrategy } from '../src/core/feedParser.js';
import { normalize } from '../src/core/feedUrl.js';

const FEED = 'https://api.riverside.com/hosting/drlxDc7x.rss';

test('parses the real Eager to Be Healthy feed', () => {
  const xml = readFileSync(new URL('../../riverside_sample.xml', import.meta.url), 'utf8');
  const { podcast, episodes } = parseFeed(xml, normalize(FEED), { DOMParser });
  assert.ok(podcast.title.length > 0);
  assert.ok(episodes.length > 0);
  for (const e of episodes) {
    assert.ok(e.id.startsWith(`${FEED}::`), e.id);
    assert.ok(e.audioUrl.startsWith('http'));
    assert.ok(e.publishedAt > 0, `date for ${e.title}`);
  }
  // feedOrder follows document order
  assert.deepEqual(episodes.map((e) => e.feedOrder), [...episodes.map((e) => e.feedOrder)].sort((a, b) => a - b));
  console.log(`  ${podcast.title}: ${episodes.length} episodes, first "${episodes[0].title}", transcript=${!!episodes[0].transcriptUrl}`);
});

test('ID uses guid, falls back to enclosure; non-audio enclosures ignored', () => {
  const xml = `<?xml version="1.0"?><rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel>
    <title>Show</title><itunes:type>serial</itunes:type><podcast:guid> abc-123 </podcast:guid>
    <item><title>One</title><guid> g1 </guid><enclosure url="https://x/1.mp3" type="audio/mpeg"/><itunes:duration>1:02:03</itunes:duration>
      <pubDate>Mon, 03 Nov 2026 10:00:00 +0000</pubDate>
      <podcast:transcript url="https://x/1.txt" type="text/plain"/><podcast:transcript url="https://x/1.vtt" type="text/vtt"/></item>
    <item><title>Two</title><enclosure url="https://x/2.mp3"/><itunes:duration>125</itunes:duration></item>
    <item><title>Video only</title><enclosure url="https://x/3.mp4" type="video/mp4"/></item>
  </channel></rss>`;
  const { podcast, episodes } = parseFeed(xml, 'https://x/feed', { DOMParser });
  assert.equal(podcast.podcastGuid, 'abc-123');
  assert.equal(podcast.playOrderStrategy, 'CHRONOLOGICAL');
  assert.deepEqual(episodes.map((e) => e.id), ['https://x/feed::g1', 'https://x/feed::https://x/2.mp3']);
  assert.equal(episodes[0].durationSeconds, 3723);
  assert.equal(episodes[0].transcriptUrl, 'https://x/1.vtt');
  assert.equal(episodes[0].publishedAt, Date.UTC(2026, 10, 3, 10));
  assert.equal(episodes[1].durationSeconds, 125);
  assert.equal(episodes[1].feedOrder, 1);
});

test('helpers', () => {
  assert.equal(parseDuration('45:30'), 2730);
  assert.equal(parseDuration('junk'), 0);
  assert.equal(parseDate(''), 0);
  assert.equal(inferPlayOrderStrategy('Morning News Briefing'), 'NEWS');
  assert.equal(inferPlayOrderStrategy('Eager to Be Healthy'), 'EVERGREEN');
});
