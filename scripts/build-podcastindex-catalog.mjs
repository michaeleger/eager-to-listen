#!/usr/bin/env node
/**
 * Builds a slim, device-friendly partial snapshot of the PodcastIndex.org directory
 * (5,000 to 100,000 top active podcasts) from the weekly public SQLite dump
 * (https://public.podcastindex.org/podcastindex_feeds.db.tgz).
 *
 * Output (written to --out-dir, default dist/podcastindex-catalog):
 *   - podcastindex-catalog.tsv.gz  (header JSON line + 8-column TSV, sorted by popularity & recency)
 *   - podcastindex-catalog.json    (metadata: version, generatedAt, count, limit, sha256, bytes)
 *
 * Usage:
 *   node scripts/build-podcastindex-catalog.mjs [--limit 35000] [--input path/to/podcastindex_feeds.db] [--out-dir dist/podcastindex-catalog]
 */

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { createGzip, constants as zlibConstants } from 'node:zlib';

export const MIN_LIMIT = 5_000;
export const MAX_LIMIT = 100_000;
export const DEFAULT_LIMIT = 35_000;
export const CATALOG_FORMAT_VERSION = 1;
export const DEFAULT_DUMP_URL = 'https://public.podcastindex.org/podcastindex_feeds.db.tgz';

function parseArgs(argv) {
  const args = {
    limit: DEFAULT_LIMIT,
    input: null,
    outDir: path.resolve('dist/podcastindex-catalog'),
    dumpUrl: DEFAULT_DUMP_URL,
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
  execFileSync('curl', ['-fSL', '--retry', '3', '-o', archivePath, dumpUrl], { stdio: 'inherit' });
  console.log('Extracting podcastindex_feeds.db ...');
  execFileSync('tar', ['-xzf', archivePath, '-C', workDir], { stdio: 'inherit' });
  const direct = path.join(workDir, 'podcastindex_feeds.db');
  if (fs.existsSync(direct)) return direct;
  // Find any .db file extracted
  for (const name of fs.readdirSync(workDir)) {
    if (name.endsWith('.db')) return path.join(workDir, name);
  }
  throw new Error('Could not find extracted SQLite database file in archive');
}

export async function buildCatalog({ input, outDir, limit, dumpUrl = DEFAULT_DUMP_URL }) {
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

    const generatedAt = Date.now();
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

const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
if (isMain) {
  const args = parseArgs(process.argv.slice(2));
  buildCatalog(args).catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
