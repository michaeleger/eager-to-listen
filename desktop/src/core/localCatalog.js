// Local partial copy of the PodcastIndex.org directory (5,000 to 100,000 top active shows).
// Mirrors android/.../data/podcastindex/LocalPodcastCatalog.kt rule for rule; change both together.

import { mergeResults } from './showSearch.js';

export const DEFAULT_SNAPSHOT_URL =
  'https://github.com/michaeleger/eager-to-listen/releases/download/podcastindex-catalog/podcastindex-catalog.tsv.gz';

export const MAX_CATALOG_AGE_DAYS = 21;
export const MAX_CATALOG_AGE_MS = MAX_CATALOG_AGE_DAYS * 24 * 60 * 60 * 1000;
export const MAX_IMPORT_ROWS = 100_000;

const norm = (s) =>
  (s || '')
    .toLowerCase()
    .normalize('NFKD')
    .replace(/\p{M}+/gu, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();

export { norm as normalizeForIndex };

/** True when no local DB is cached or the last download/verification is older than 21 days. */
export function isCatalogStale(meta, now = Date.now()) {
  if (!meta || !meta.count || !meta.downloadedAt || meta.downloadedAt <= 0) return true;
  return now - meta.downloadedAt > MAX_CATALOG_AGE_MS;
}

/** Decode either a gzipped (.tsv.gz) or plain UTF-8 snapshot byte buffer into text. */
export async function decodeSnapshotBytes(input) {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  const isGzip = bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
  if (!isGzip) {
    return new TextDecoder('utf-8').decode(bytes);
  }
  if (typeof DecompressionStream !== 'function') {
    throw new Error('This platform does not support gzip decompression in the web view.');
  }
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  return await new Response(stream).text();
}

export function parseHeader(line) {
  if (!line || !line.startsWith('#')) return null;
  const raw = line.slice(1).trim();
  if (!raw.startsWith('{')) return null;
  try {
    const obj = JSON.parse(raw);
    return {
      version: Number(obj.version) || 1,
      generatedAt: Number(obj.generatedAt) || 0,
      count: Number(obj.count) || 0,
    };
  } catch {
    return null;
  }
}

export function parseCatalogTsv(tsvText) {
  const lines = String(tsvText || '').split(/\r?\n/);
  let header = null;
  const entries = [];
  for (const line of lines) {
    if (!line) continue;
    if (header === null && line.startsWith('#')) {
      header = parseHeader(line);
      continue;
    }
    if (line.startsWith('#')) continue;
    const parts = line.split('\t');
    if (parts.length < 3) continue;
    const title = (parts[0] || '').trim();
    const author = (parts[1] || '').trim();
    const feedUrl = (parts[2] || '').trim();
    if (!title || !feedUrl) continue;
    const artworkUrl = (parts[3] || '').trim();
    const rawCats = (parts[4] || '').trim();
    const appleId = (parts[5] || '').trim() || null;
    const podcastGuid = (parts[6] || '').trim() || null;
    const newestSec = Number(parts[7]) || 0;
    const genres = rawCats ? rawCats.split('|').map((c) => c.trim()).filter(Boolean).slice(0, 3) : ['Podcast Index'];
    entries.push({
      title,
      author,
      feedUrl,
      artworkUrl,
      genres,
      appleId,
      podcastGuid,
      newestEpisodeAt: newestSec * 1000,
      dead: false,
      source: 'Podcast Index (Local)',
      // Pre-normalized padded search fields for fast prefix matching without rebuilding per query
      _haystack: ` ${norm(title)} ${norm(author)} ${norm(rawCats.replace(/\|/g, ' '))} `,
      _cats: ` ${norm(rawCats.replace(/\|/g, ' '))} `,
    });
    if (entries.length >= MAX_IMPORT_ROWS) break;
  }
  return { header, entries };
}

/** Full-text prefix search matching LocalPodcastCatalog.kt's FTS4 word-prefix semantics. */
export function searchCatalog(entries, query, limit = 40) {
  const tokens = norm(query).split(' ').filter(Boolean).slice(0, 8);
  if (!tokens.length || !entries?.length) return [];
  const cap = limit * 2;
  const out = [];
  for (const e of entries) {
    let ok = true;
    for (const tok of tokens) {
      if (!e._haystack.includes(` ${tok}`)) {
        ok = false;
        break;
      }
    }
    if (ok) {
      out.push(e);
      if (out.length >= cap) break;
    }
  }
  return out;
}

function categoryTokenSets(category) {
  const c = norm(category);
  if (c.includes('health') || c.includes('fitness') || c.includes('wellness')) return [['health'], ['fitness']];
  if (c.includes('self help') || c.includes('mindset')) return [['self', 'help'], ['mental', 'health'], ['health']];
  if (c.includes('true crime')) return [['true', 'crime']];
  if (c.includes('society') || c.includes('culture')) return [['society'], ['culture']];
  if (c.includes('news')) return [['news']];
  if (c.includes('technology')) return [['technology']];
  if (c.includes('science')) return [['science']];
  if (c.includes('business')) return [['business']];
  if (c.includes('history')) return [['history']];
  if (c.includes('comedy')) return [['comedy']];
  if (c.includes('education')) return [['education']];
  const words = c.split(' ').filter(Boolean);
  return words.length ? [words] : [];
}

export function browseCatalogCategory(entries, category, limit = 40) {
  const sets = categoryTokenSets(category);
  if (!sets.length || !entries?.length) return [];
  const out = [];
  const seen = new Set();
  for (const tokens of sets) {
    if (out.length >= limit) break;
    for (const e of entries) {
      if (out.length >= limit) break;
      if (seen.has(e.feedUrl.toLowerCase())) continue;
      if (tokens.every((t) => e._cats.includes(` ${t}`))) {
        seen.add(e.feedUrl.toLowerCase());
        out.push({ ...e, genres: [category] });
      }
    }
  }
  return out;
}

/**
 * Strict fallback ordering: merge Podcast Index results (API + local partial catalog) first,
 * and invoke fallbackAppleFn() ONLY when Podcast Index returned nothing (and Apple fallback is on).
 */
export async function resolveWithFallback(query, podcastIndexLists, appleFallbackEnabled, fallbackAppleFn) {
  const piMerged = mergeResults(query, podcastIndexLists);
  if (piMerged.length > 0) {
    return { results: piMerged, usedAppleFallback: false };
  }
  if (!appleFallbackEnabled) {
    return { results: [], usedAppleFallback: false };
  }
  const apple = await fallbackAppleFn();
  return { results: mergeResults(query, [apple]), usedAppleFallback: true };
}
