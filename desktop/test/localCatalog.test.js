import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import test from 'node:test';
import { gzipSync } from 'node:zlib';

import {
  browseCatalogCategory,
  decodeSnapshotBytes,
  parseCatalogTsv,
  resolveWithFallback,
  searchCatalog,
} from '../src/core/localCatalog.js';
import {
  MAX_AGE_MS,
  buildCatalog,
  isCatalogStale,
  syncBundledCatalog,
} from '../../scripts/build-podcastindex-catalog.mjs';

function createSyntheticPodcastIndexDb(dbPath) {
  const db = new DatabaseSync(dbPath);
  db.exec(`
    CREATE TABLE podcasts (
      id INTEGER PRIMARY KEY,
      url TEXT NOT NULL UNIQUE,
      title TEXT NOT NULL,
      lastUpdate INTEGER,
      lastHttpStatus INTEGER,
      dead INTEGER,
      itunesId INTEGER,
      itunesAuthor TEXT NOT NULL,
      itunesOwnerName TEXT NOT NULL,
      imageUrl TEXT NOT NULL,
      newestItemPubdate INTEGER,
      episodeCount INTEGER,
      popularityScore INTEGER,
      podcastGuid TEXT NOT NULL,
      category1 TEXT NOT NULL,
      category2 TEXT NOT NULL
    );
    INSERT INTO podcasts VALUES
      (1, 'https://feeds.megaphone.fm/hubermanlab', 'Huberman Lab', 1750000000, 200, 0, 1545953110, 'Dr. Andrew Huberman', '', 'https://img/h.jpg', 1750000000, 250, 99, 'guid-h', 'Science', 'Health & Fitness'),
      (2, 'https://dead.example/rss', 'Dead Show', 1700000000, 404, 1, 0, 'Nobody', '', '', 1700000000, 10, 100, 'guid-dead', 'News', ''),
      (3, 'https://api.riverside.com/hosting/drlxDc7x.rss', 'Eager to Be Healthy', 1750001000, 200, 0, 1781234567, 'Michael Scott Eger', '', 'https://img/e.jpg', 1750001000, 45, 50, 'guid-e', 'Health & Fitness', 'Self-Help'),
      (4, 'https://empty.example/rss', 'Zero Episodes', 1750000000, 200, 0, 0, 'Empty', '', '', 0, 0, 80, 'guid-zero', 'Comedy', '');
  `);
  db.close();
}

test('build-time catalog staleness enforces the 21-day threshold', () => {
  const now = 1_760_000_000_000;
  const day = 24 * 60 * 60 * 1000;

  assert.equal(isCatalogStale(0, now), true);
  assert.equal(isCatalogStale(null, now), true);
  assert.equal(isCatalogStale(now - 20 * day, now), false);
  assert.equal(isCatalogStale(now - MAX_AGE_MS, now), false);
  assert.equal(isCatalogStale(now - MAX_AGE_MS - 1, now), true);
});

test('syncBundledCatalog builds at build time, skips when <= 21 days old, and updates when > 21 days old', async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'etl-sync-catalog-'));
  try {
    const dbPath = path.join(tmpDir, 'podcastindex_feeds.db');
    createSyntheticPodcastIndexDb(dbPath);

    const androidAsset = path.join(tmpDir, 'android', 'podcastindex-catalog.tsv.gz');
    const desktopAsset = path.join(tmpDir, 'desktop', 'podcastindex-catalog.tsv.gz');
    const metaPath = path.join(tmpDir, 'catalog', 'podcastindex-catalog.json');
    const day = 24 * 60 * 60 * 1000;
    const t0 = 1_760_000_000_000;

    // 1. Missing bundled file -> builds both Android & Desktop assets
    const first = await syncBundledCatalog({
      androidAssetPath: androidAsset,
      desktopAssetPath: desktopAsset,
      metaPath,
      limit: 10,
      input: dbPath,
      nowMs: t0,
    });
    assert.equal(first.action, 'updated');
    assert.equal(fs.existsSync(androidAsset), true);
    assert.equal(fs.existsSync(desktopAsset), true);
    assert.equal(first.meta.count, 2);

    // 2. 10 days later (<= 21 days) -> skips rebuilding completely
    const second = await syncBundledCatalog({
      androidAssetPath: androidAsset,
      desktopAssetPath: desktopAsset,
      metaPath,
      limit: 10,
      input: path.join(tmpDir, 'does-not-exist.db'),
      nowMs: t0 + 10 * day,
    });
    assert.equal(second.action, 'skipped');

    // 3. 22 days later (> 21 days) -> rebuilds both assets
    const third = await syncBundledCatalog({
      androidAssetPath: androidAsset,
      desktopAssetPath: desktopAsset,
      metaPath,
      limit: 10,
      input: dbPath,
      nowMs: t0 + 22 * day,
    });
    assert.equal(third.action, 'updated');
    assert.equal(third.meta.generatedAt, t0 + 22 * day);

    // 4. 50 days later but offline (offlineOk=true) -> keeps existing bundled copy without failing build
    const offline = await syncBundledCatalog({
      androidAssetPath: androidAsset,
      desktopAssetPath: desktopAsset,
      metaPath,
      limit: 10,
      input: path.join(tmpDir, 'unreachable.db'),
      offlineOk: true,
      nowMs: t0 + 50 * day,
    });
    assert.equal(offline.action, 'kept-stale-offline');
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});

test('gzipped TSV snapshot decodes, parses, searches, and browses categories', async () => {
  const tsv = [
    '# {"version":1,"generatedAt":1750000000000,"count":3,"source":"podcastindex.org"}',
    'Huberman Lab\tDr. Andrew Huberman\thttps://feeds.megaphone.fm/hubermanlab\thttps://img/h.jpg\tScience|Health & Fitness\t1545953110\tguid-h\t1750000000',
    'No Agenda Show\tAdam Curry & John C. Dvorak\thttps://feeds.noagendaassets.com/noagenda.xml\thttps://img/na.jpg\tNews|Society & Culture\t269169796\tguid-na\t1749999000',
    'Eager to Be Healthy\tMichael Scott Eger\thttps://api.riverside.com/hosting/drlxDc7x.rss\thttps://img/e.jpg\tHealth & Fitness|Self-Help\t1781234567\tguid-e\t1750001000',
  ].join('\n');

  const gz = gzipSync(Buffer.from(tsv, 'utf8'));
  const decoded = await decodeSnapshotBytes(gz);
  const { header, entries } = parseCatalogTsv(decoded);

  assert.equal(header.version, 1);
  assert.equal(header.count, 3);
  assert.equal(entries.length, 3);

  const huberman = searchCatalog(entries, 'huberman');
  assert.equal(huberman.length, 1);
  assert.equal(huberman[0].title, 'Huberman Lab');
  assert.equal(huberman[0].appleId, '1545953110');
  assert.equal(huberman[0].source, 'Podcast Index (Local)');

  const authorSearch = searchCatalog(entries, 'Adam Curry');
  assert.equal(authorSearch.length, 1);
  assert.equal(authorSearch[0].title, 'No Agenda Show');

  const health = browseCatalogCategory(entries, 'Health & Fitness');
  assert.deepEqual(health.map((h) => h.title), ['Huberman Lab', 'Eager to Be Healthy']);

  const news = browseCatalogCategory(entries, 'News');
  assert.deepEqual(news.map((n) => n.title), ['No Agenda Show']);
});

test('strict Apple/iTunes fallback only runs when Podcast Index returns no results', async () => {
  const localShow = {
    title: 'Huberman Lab',
    author: 'Dr. Andrew Huberman',
    feedUrl: 'https://feeds.megaphone.fm/hubermanlab',
    artworkUrl: '',
    genres: ['Science'],
    source: 'Podcast Index (Local)',
  };
  const appleShow = {
    title: 'Brand New Indie Show',
    author: 'Jane Doe',
    feedUrl: 'https://example.com/new.rss',
    artworkUrl: '',
    genres: ['Health'],
    source: 'Apple Podcasts',
  };

  let appleCalls = 0;
  const hit = await resolveWithFallback('huberman', [[], [localShow]], true, async () => {
    appleCalls++;
    return [appleShow];
  });
  assert.equal(appleCalls, 0);
  assert.equal(hit.usedAppleFallback, false);
  assert.equal(hit.results[0].title, 'Huberman Lab');

  const fallback = await resolveWithFallback('brand new indie', [[], []], true, async () => {
    appleCalls++;
    return [appleShow];
  });
  assert.equal(appleCalls, 1);
  assert.equal(fallback.usedAppleFallback, true);
  assert.equal(fallback.results[0].title, 'Brand New Indie Show');

  const disabled = await resolveWithFallback('brand new indie', [[], []], false, async () => {
    appleCalls++;
    return [appleShow];
  });
  assert.equal(appleCalls, 1);
  assert.equal(disabled.usedAppleFallback, false);
  assert.equal(disabled.results.length, 0);
});

test('build-podcastindex-catalog script exports active top shows readable by clients', async () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'etl-catalog-test-'));
  try {
    const dbPath = path.join(tmpDir, 'podcastindex_feeds.db');
    const outDir = path.join(tmpDir, 'out');
    createSyntheticPodcastIndexDb(dbPath);

    const meta = await buildCatalog({ input: dbPath, outDir, limit: 10 });
    assert.equal(meta.count, 2);
    assert.ok(meta.sha256.length === 64);

    const gzBytes = fs.readFileSync(path.join(outDir, 'podcastindex-catalog.tsv.gz'));
    const text = await decodeSnapshotBytes(gzBytes);
    const parsed = parseCatalogTsv(text);
    assert.equal(parsed.entries.length, 2);
    assert.equal(parsed.entries[0].title, 'Huberman Lab');
    assert.equal(parsed.entries[1].title, 'Eager to Be Healthy');
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
