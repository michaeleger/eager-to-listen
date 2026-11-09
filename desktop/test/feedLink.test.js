import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveLink, httpFallback, looksLikeHtml, discoverFeedLinks, LINK_DEFAULT_PRIORITY } from '../src/core/feedLink.js';
import { mergeResults, matchScore, fromPodcastIndex, fromApple } from '../src/core/showSearch.js';

const feed = (url, schemeGuessed = false) => ({ kind: 'feed', url, schemeGuessed });

test('links added from outside default to priority 2', () => {
  assert.equal(LINK_DEFAULT_PRIORITY, 2);
});

test('plain feed addresses pass through untouched', () => {
  assert.deepEqual(resolveLink('https://api.riverside.com/hosting/drlxDc7x.rss'), feed('https://api.riverside.com/hosting/drlxDc7x.rss'));
  assert.deepEqual(resolveLink('  http://example.com/feed.xml  '), feed('http://example.com/feed.xml'));
});

test('podcast app link schemes become https feeds', () => {
  assert.deepEqual(resolveLink('feed://example.com/rss'), feed('https://example.com/rss', true));
  assert.deepEqual(resolveLink('podcast://feeds.example.com/show.xml'), feed('https://feeds.example.com/show.xml', true));
  assert.deepEqual(resolveLink('itpc://example.com/podcast.xml'), feed('https://example.com/podcast.xml', true));
  assert.deepEqual(resolveLink('pcast://example.com/podcast.xml'), feed('https://example.com/podcast.xml', true));
  assert.deepEqual(resolveLink('feed:https://example.com/rss'), feed('https://example.com/rss'));
  assert.deepEqual(resolveLink('feed:http://example.com/rss'), feed('http://example.com/rss'));
  assert.equal(httpFallback('https://example.com/rss'), 'http://example.com/rss');
  assert.equal(httpFallback('http://example.com/rss'), null);
});

test('subscribeonandroid.com links unwrap to the feed', () => {
  assert.deepEqual(resolveLink('https://subscribeonandroid.com/feeds.example.com/show.xml'), feed('https://feeds.example.com/show.xml', true));
  assert.deepEqual(resolveLink('https://www.subscribeonandroid.com/https://feeds.example.com/show.xml'), feed('https://feeds.example.com/show.xml'));
});

test('Apple Podcasts and Overcast links resolve by Apple ID', () => {
  assert.deepEqual(resolveLink('https://podcasts.apple.com/us/podcast/eager-to-be-healthy/id1781234567'), { kind: 'apple', id: '1781234567' });
  assert.deepEqual(resolveLink('https://podcasts.apple.com/us/podcast/x/id42?i=1000600000000'), { kind: 'apple', id: '42' });
  assert.deepEqual(resolveLink('https://itunes.apple.com/podcast/id99'), { kind: 'apple', id: '99' });
  assert.deepEqual(resolveLink('https://overcast.fm/itunes1781234567/eager-to-be-healthy'), { kind: 'apple', id: '1781234567' });
});

test('a link inside shared text is found; plain words are a search', () => {
  assert.deepEqual(resolveLink('Great show! https://example.com/feed.xml).'), feed('https://example.com/feed.xml'));
  assert.deepEqual(resolveLink('Eager to Be Healthy\nhttps://podcasts.apple.com/us/podcast/x/id7'), { kind: 'apple', id: '7' });
  assert.deepEqual(resolveLink('huberman lab'), { kind: 'search', term: 'huberman lab' });
  assert.deepEqual(resolveLink('   '), { kind: 'none' });
  assert.deepEqual(resolveLink('feed://'), { kind: 'none' });
});

test('web pages are told apart from feeds', () => {
  assert.equal(looksLikeHtml('<!DOCTYPE html><html>', ''), true);
  assert.equal(looksLikeHtml('<?xml version="1.0"?><rss>', 'text/html'), true);
  assert.equal(looksLikeHtml('<?xml version="1.0"?><rss>', 'application/rss+xml'), false);
  assert.equal(looksLikeHtml('<?xml version="1.0"?><rss>', 'text/xml'), false);
  assert.equal(looksLikeHtml('  <html lang="en">', ''), true);
});

test('feed links are found on a web page', () => {
  const html = `<html><head>
    <link rel="stylesheet" href="/a.css">
    <link rel="alternate" type="application/rss+xml" title="Podcast" href="/podcast/feed.xml">
    <link type='application/atom+xml' rel='alternate' href='https://cdn.example.com/atom?x=1&amp;y=2'>
    <link rel="alternate" type="application/rss+xml" href="https://example.com/comments/feed/">
    <link rel="alternate" type="application/rss+xml" href="/podcast/feed.xml">
    <link rel="alternate" hreflang="es" href="/es/">
  </head></html>`;
  assert.deepEqual(discoverFeedLinks(html, 'https://example.com/show/'), [
    'https://example.com/podcast/feed.xml',
    'https://cdn.example.com/atom?x=1&y=2',
  ]);
  assert.deepEqual(discoverFeedLinks('<html></html>', 'https://example.com/'), []);
});

test('title matches rank first', () => {
  const r = (title, author = '') => ({ title, author });
  assert.equal(matchScore('Huberman Lab', r('Huberman Lab')), 100);
  assert.equal(matchScore('huberman', r('Huberman Lab Clips')), 80);
  assert.equal(matchScore('lab', r('The Huberman Lab Show')), 60);
  assert.equal(matchScore('lab huberman', r('The Huberman Lab Show')), 40);
  assert.equal(matchScore('eger healthy', r('Eager to Be Healthy', 'Michael Eger')), 20);
  assert.equal(matchScore('cooking', r('Eager to Be Healthy')), 0);
});

test('both directories merge: new shows from Apple show up even when Podcast Index answers', () => {
  const pi = fromPodcastIndex({
    feeds: [
      { title: 'Healthy Living Daily', author: 'A', url: 'https://a.example/rss', newestItemPubdate: 1700000000 },
      { title: 'Old Dead Show', author: 'B', url: 'https://dead.example/rss', dead: 1 },
      { title: 'Eager to Be Healthy', author: 'Michael Scott Eger', url: 'http://old-host.example/eager.rss', itunesId: 555, newestItemPubdate: 1600000000 },
    ],
  });
  const apple = fromApple({
    results: [
      { collectionName: 'Brand New Health Show', artistName: 'C', feedUrl: 'https://new.example/rss', collectionId: 777, releaseDate: '2026-11-08T10:00:00Z' },
      { collectionName: 'Eager to Be Healthy', artistName: 'Michael Scott Eger', feedUrl: 'https://api.riverside.com/hosting/drlxDc7x.rss', collectionId: 555, releaseDate: '2026-11-01T10:00:00Z' },
      { collectionName: 'No feed here', artistName: 'D' },
    ],
  });
  const out = mergeResults('eager to be healthy', [pi, apple]);
  assert.equal(out[0].title, 'Eager to Be Healthy');
  // The duplicate kept the address with the newest episode (Apple's current one, not Podcast Index's stale one)
  assert.equal(out[0].feedUrl, 'https://api.riverside.com/hosting/drlxDc7x.rss');
  assert.equal(out.filter((r) => r.title === 'Eager to Be Healthy').length, 1);
  assert.ok(out.some((r) => r.title === 'Brand New Health Show'), 'Apple-only show is in the results');
  assert.ok(!out.some((r) => r.title === 'Old Dead Show'), 'dead feeds are dropped');
  assert.ok(!out.some((r) => r.title === 'No feed here'));
});

test('one directory down: the other still answers', () => {
  const apple = fromApple({ results: [{ collectionName: 'Only Apple', artistName: 'X', feedUrl: 'https://x.example/rss' }] });
  assert.deepEqual(mergeResults('only', [[], apple]).map((r) => r.title), ['Only Apple']);
  assert.deepEqual(mergeResults('only', [undefined, apple]).map((r) => r.title), ['Only Apple']);
});

test('same address over http and https is one show', () => {
  const out = mergeResults('x', [
    [{ title: 'X One', author: '', feedUrl: 'http://Example.com/feed/' }],
    [{ title: 'X Show', author: 'someone', feedUrl: 'https://example.com/feed' }],
  ]);
  assert.equal(out.length, 1);
});

test('scheme names inside ordinary words are not links', () => {
  assert.deepEqual(resolveLink('Progress: the podcast'), { kind: 'search', term: 'Progress: the podcast' });
  assert.deepEqual(resolveLink('Address: https://example.com/rss'), feed('https://example.com/rss'));
});
