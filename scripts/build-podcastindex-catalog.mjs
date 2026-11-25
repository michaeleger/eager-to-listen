#!/usr/bin/env node
/**
 * Build-time updater for the bundled partial copy of the PodcastIndex.org directory
 * (5,000 to 100,000 top active podcasts).
 *
 * Runs at build time (Gradle preBuild & Desktop build) — NOT on end-user devices:
 *   - Checks the existing bundled snapshot's age.
 *   - If present and <= 21 days old (and --force is not passed), skips downloading and exits fast.
 *   - If missing or > 21 days old, downloads the weekly SQLite dump from podcastindex.org,
 *     extracts the top active podcasts into a compact gzipped TSV, and writes it into the
 *     Android & Desktop app asset directories.
 *
 * Usage:
 *   node scripts/build-podcastindex-catalog.mjs --sync-apps [--limit 25000] [--force] [--offline-ok]
 *   node scripts/build-podcastindex-catalog.mjs --input path/to/podcastindex_feeds.db --out-dir dist/catalog [--limit 25000]
 */

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createGzip, gunzipSync, constants as zlibConstants } from 'node:zlib';

export const MIN_LIMIT = 5_000;
export const MAX_LIMIT = 100_000;
export const DEFAULT_LIMIT = 25_000;
export const CATALOG_FORMAT_VERSION = 1;
export const MAX_AGE_DAYS = 21;
export const MAX_AGE_MS = MAX_AGE_DAYS * 24 * 60 * 60 * 1000;
export const DEFAULT_DUMP_URL = 'https://public.podcastindex.org/podcastindex_feeds.db.tgz';
export const BUILD_USER_AGENT = 'EagerToListen-Build/1.0 (+https://eagertobehealthy.com/eager-to-listen)';

const REPO_ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
export const ANDROID_ASSET_PATH = path.join(REPO_ROOT, 'android', 'app', 'src', 'main', 'assets', 'podcastindex-catalog.tsv.gz');
export const DESKTOP_ASSET_PATH = path.join(REPO_ROOT, 'desktop', 'src', 'assets', 'podcastindex-catalog.tsv.gz');
export const REPO_META_PATH = path.join(REPO_ROOT, 'catalog', 'podcastindex-catalog.json');

export function isCatalogStale(generatedAtMs, nowMs = Date.now()) {
  if (!generatedAtMs || !Number.isFinite(generatedAtMs) || generatedAtMs <= 0) return true;
  return nowMs - generatedAtMs > MAX_AGE_MS;
}

export function readBundledHeader(gzPath) {
  if (!fs.existsSync(gzPath)) return null;
  try {
    const gz = fs.readFileSync(gzPath);
    if (!gz.length) return null;
    const text = gunzipSync(gz).toString('utf8');
    const nl = text.indexOf('\n');
    const firstLine = (nl >= 0 ? text.slice(0, nl) : text).trim();
    if (!firstLine.startsWith('#')) return null;
    const obj = JSON.parse(firstLine.slice(1).trim());
    return {
      version: Number(obj.version) || 1,
      generatedAt: Number(obj.generatedAt) || 0,
      count: Number(obj.count) || 0,
    };
  } catch {
    return null;
  }
}

export function readExistingCatalogAge(gzPath, metaPath = null, nowMs = Date.now()) {
  if (!fs.existsSync(gzPath)) return { exists: false, stale: true, ageDays: Infinity, generatedAt: 0, count: 0 };
  let generatedAt = 0;
  let count = 0;
  if (metaPath && fs.existsSync(metaPath)) {
    try {
      const m = JSON.parse(fs.readFileSync(metaPath, 'utf8'));
      generatedAt = Number(m.generatedAt) || 0;
      count = Number(m.count) || 0;
    } catch {
      // fall through to reading gzip header
    }
  }
  if (!generatedAt || !count) {
    const h = readBundledHeader(gzPath);
    if (h) {
      generatedAt = h.generatedAt;
      count = h.count;
    }
  }
  if (!generatedAt) {
    generatedAt = fs.statSync(gzPath).mtimeMs;
  }
  const stale = isCatalogStale(generatedAt, nowMs);
  const ageDays = Math.max(0, (nowMs - generatedAt) / (24 * 60 * 60 * 1000));
  return { exists: true, stale, ageDays, generatedAt, count };
}

function parseArgs(argv) {
  const args = {
    limit: DEFAULT_LIMIT,
    input: null,
    outDir: path.resolve('dist/podcastindex-catalog'),
    dumpUrl: DEFAULT_DUMP_URL,
    syncApps: false,
    force: false,
    offlineOk: false,
    allowSmallTest: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--limit' && argv[i + 1]) {
      args.limit = Number(argv[++i]);
    } else if (a === '--input' && argv[i + 1]) {
      args.input = path.resolve(argv[++i]);
    } else if (a === '--out-dir' && argv[i + 1]) {
      args.outDir = path.resolve(argv[++i]);
    } else if (a === '--dump-url' && argv[i + 1]) {
      args.dumpUrl = argv[++i];
    } else if (a === '--sync-apps') {
      args.syncApps = true;
    } else if (a === '--force') {
      args.force = true;
    } else if (a === '--offline-ok') {
      args.offlineOk = true;
    } else if (a === '--allow-small-test') {
      args.allowSmallTest = true;
    }
  }
  if (!Number.isFinite(args.limit) || args.limit < 1) {
    throw new Error(`Invalid --limit: ${args.limit}`);
  }
  if (!args.allowSmallTest) {
    args.limit = Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, Math.floor(args.limit)));
  } else {
    args.limit = Math.min(MAX_LIMIT, Math.floor(args.limit));
  }
  return args;
}

function cleanField(v) {
  if (v == null) return '';
  return String(v).replace(/[\t\r\n]+/g, ' ').trim();
}

function joinCategories(row) {
  const seen = new Set();
  const out = [];
  for (let i = 1; i <= 10; i++) {
    const c = cleanField(row[`category${i}`]);
    if (c && !seen.has(c.toLowerCase())) {
      seen.add(c.toLowerCase());
      out.push(c);
    }
  }
  return out.join('|');
}

function downloadAndExtractDump(dumpUrl, workDir) {
  const archivePath = path.join(workDir, 'podcastindex_feeds.db.tgz');
  console.log(`Downloading ${dumpUrl} ...`);
  execFileSync(
    'curl',
    ['-fSL', '--retry', '3', '-A', BUILD_USER_AGENT, '-o', archivePath, dumpUrl],
    { stdio: 'inherit' },
  );
  console.log('Extracting podcastindex_feeds.db ...');
  execFileSync('tar', ['-xzf', archivePath, '-C', workDir], { stdio: 'inherit' });
  const direct = path.join(workDir, 'podcastindex_feeds.db');
  if (fs.existsSync(direct)) return direct;
  for (const name of fs.readdirSync(workDir)) {
    if (name.endsWith('.db')) return path.join(workDir, name);
  }
  throw new Error('Could not find extracted SQLite database file in archive');
}

export async function buildCatalog({ input, outDir, limit, dumpUrl = DEFAULT_DUMP_URL, nowMs = Date.now() }) {
  let tempDir = null;
  let dbPath = input;
  try {
    if (!dbPath) {
      tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pi-catalog-'));
      dbPath = downloadAndExtractDump(dumpUrl, tempDir);
    }
    if (!fs.existsSync(dbPath)) {
      throw new Error(`Input database not found: ${dbPath}`);
    }

    fs.mkdirSync(outDir, { recursive: true });
    const db = new DatabaseSync(dbPath, { readOnly: true });
    const cols = new Set(db.prepare('PRAGMA table_info(podcasts)').all().map((c) => c.name));
    const colOr = (name, fallback = "''") => (cols.has(name) ? name : fallback);

    const catSelect = Array.from({ length: 10 }, (_, idx) => {
      const name = `category${idx + 1}`;
      return `${colOr(name, "''")} AS ${name}`;
    }).join(', ');

    const sql = `
      SELECT
        ${colOr('title')} AS title,
        ${colOr('itunesAuthor')} AS itunesAuthor,
        ${colOr('itunesOwnerName')} AS itunesOwnerName,
        ${colOr('url')} AS url,
        ${colOr('imageUrl')} AS imageUrl,
        ${colOr('itunesId', '0')} AS itunesId,
        ${colOr('podcastGuid')} AS podcastGuid,
        ${colOr('newestItemPubdate', '0')} AS newestItemPubdate,
        ${colOr('lastUpdate', '0')} AS lastUpdate,
        ${catSelect}
      FROM podcasts
      WHERE COALESCE(${colOr('dead', '0')}, 0) = 0
        AND TRIM(COALESCE(${colOr('url')}, '')) != ''
        AND TRIM(COALESCE(${colOr('title')}, '')) != ''
        AND (COALESCE(${colOr('lastHttpStatus', '200')}, 200) = 0
             OR (COALESCE(${colOr('lastHttpStatus', '200')}, 200) >= 200
                 AND COALESCE(${colOr('lastHttpStatus', '200')}, 200) < 400))
        AND COALESCE(${colOr('episodeCount', '1')}, 1) > 0
      ORDER BY
        COALESCE(${colOr('popularityScore', '0')}, 0) DESC,
        COALESCE(${colOr('newestItemPubdate', '0')}, ${colOr('lastUpdate', '0')}, 0) DESC,
        COALESCE(${colOr('episodeCount', '0')}, 0) DESC
      LIMIT ?
    `;

    const rows = db.prepare(sql).all(limit);
    db.close();

    const generatedAt = nowMs;
    const lines = [
      `# ${JSON.stringify({
        version: CATALOG_FORMAT_VERSION,
        generatedAt,
        count: rows.length,
        limit,
        source: 'podcastindex.org',
      })}`,
    ];

    for (const r of rows) {
      const title = cleanField(r.title);
      const url = cleanField(r.url);
      if (!title || !url) continue;
      const author = cleanField(r.itunesAuthor) || cleanField(r.itunesOwnerName);
      const artwork = cleanField(r.imageUrl);
      const categories = joinCategories(r);
      const itunesId = Number(r.itunesId) > 0 ? String(Math.floor(Number(r.itunesId))) : '';
      const podcastGuid = cleanField(r.podcastGuid);
      const newestSec = Number(r.newestItemPubdate) > 0
        ? Math.floor(Number(r.newestItemPubdate))
        : (Number(r.lastUpdate) > 0 ? Math.floor(Number(r.lastUpdate)) : 0);

      lines.push([title, author, url, artwork, categories, itunesId, podcastGuid, String(newestSec)].join('\t'));
    }

    const tsvContent = `${lines.join('\n')}\n`;
    const uncompressedBytes = Buffer.byteLength(tsvContent, 'utf8');
    const gzPath = path.join(outDir, 'podcastindex-catalog.tsv.gz');
    const metaPath = path.join(outDir, 'podcastindex-catalog.json');

    await new Promise((resolve, reject) => {
      const gz = createGzip({ level: zlibConstants.Z_BEST_COMPRESSION });
      const out = fs.createWriteStream(gzPath);
      gz.on('error', reject);
      out.on('error', reject);
      out.on('finish', resolve);
      gz.pipe(out);
      gz.end(tsvContent, 'utf8');
    });

    const gzBytes = fs.readFileSync(gzPath);
    const sha256 = createHash('sha256').update(gzBytes).digest('hex');
    const meta = {
      version: CATALOG_FORMAT_VERSION,
      generatedAt,
      count: lines.length - 1,
      limit,
      source: 'podcastindex.org',
      file: 'podcastindex-catalog.tsv.gz',
      gzipBytes: gzBytes.length,
      uncompressedBytes,
      sha256,
    };
    fs.writeFileSync(metaPath, `${JSON.stringify(meta, null, 2)}\n`, 'utf8');
    console.log(
      `Built catalog: ${meta.count.toLocaleString()} shows, ${(meta.gzipBytes / (1024 * 1024)).toFixed(2)} MB gz ` +
      `(${(meta.uncompressedBytes / (1024 * 1024)).toFixed(2)} MB raw) -> ${gzPath}`
    );
    return meta;
  } finally {
    if (tempDir) {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  }
}

/**
 * Build-time freshness check & sync for both Android and Desktop bundled assets.
 * Only downloads/rebuilds when the local partial copy is missing or > 21 days old (or force=true).
 */
export async function syncBundledCatalog({
  androidAssetPath = ANDROID_ASSET_PATH,
  desktopAssetPath = DESKTOP_ASSET_PATH,
  metaPath = REPO_META_PATH,
  limit = DEFAULT_LIMIT,
  input = null,
  dumpUrl = DEFAULT_DUMP_URL,
  force = false,
  offlineOk = false,
  nowMs = Date.now(),
} = {}) {
  const existing = readExistingCatalogAge(androidAssetPath, metaPath, nowMs);
  if (!force && existing.exists && !existing.stale) {
    // Ensure desktop copy stays in sync with Android copy without downloading
    if (!fs.existsSync(desktopAssetPath)) {
      fs.mkdirSync(path.dirname(desktopAssetPath), { recursive: true });
      fs.copyFileSync(androidAssetPath, desktopAssetPath);
    }
    console.log(
      `Bundled Podcast Index catalog is ${existing.ageDays.toFixed(1)} day(s) old (<= ${MAX_AGE_DAYS} days, ` +
      `${existing.count.toLocaleString()} shows); skipping build-time download.`
    );
    return { action: 'skipped', ...existing };
  }

  const stageDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pi-sync-'));
  try {
    const meta = await buildCatalog({ input, outDir: stageDir, limit, dumpUrl, nowMs });
    const builtGz = path.join(stageDir, 'podcastindex-catalog.tsv.gz');
    const builtMeta = path.join(stageDir, 'podcastindex-catalog.json');

    for (const target of [androidAssetPath, desktopAssetPath]) {
      fs.mkdirSync(path.dirname(target), { recursive: true });
      fs.copyFileSync(builtGz, target);
    }
    fs.mkdirSync(path.dirname(metaPath), { recursive: true });
    fs.copyFileSync(builtMeta, metaPath);

    console.log(`Synced bundled catalog (${meta.count.toLocaleString()} shows) to Android & Desktop assets.`);
    return { action: 'updated', meta };
  } catch (err) {
    if (offlineOk && existing.exists) {
      console.warn(
        `Warning: could not refresh stale Podcast Index catalog (${err.message}); ` +
        `continuing build with existing bundled copy (${existing.ageDays.toFixed(1)} days old).`
      );
      if (!fs.existsSync(desktopAssetPath)) {
        fs.mkdirSync(path.dirname(desktopAssetPath), { recursive: true });
        fs.copyFileSync(androidAssetPath, desktopAssetPath);
      }
      return { action: 'kept-stale-offline', ...existing };
    }
    throw err;
  } finally {
    fs.rmSync(stageDir, { recursive: true, force: true });
  }
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
if (isMain) {
  const args = parseArgs(process.argv.slice(2));
  const task = args.syncApps
    ? syncBundledCatalog({
        limit: args.limit,
        input: args.input,
        dumpUrl: args.dumpUrl,
        force: args.force,
        offlineOk: args.offlineOk,
      })
    : buildCatalog(args);
  task.catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
