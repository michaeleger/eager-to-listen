// Bundled local partial copy of the PodcastIndex.org directory (5,000 to 100,000 top active shows).
// Updated at BUILD TIME (scripts/build-podcastindex-catalog.mjs) when > 21 days old; never
// downloaded from end-user devices. Mirrors android/.../data/podcastindex/LocalPodcastCatalog.kt.

import { mergeResults } from './showSearch.js';

export const BUNDLED_CATALOG_URL = './assets/podcastindex-catalog.tsv.gz';
export const MAX_IMPORT_ROWS = 100_000;

const norm = (s) =>
  (s || '')
    .toLowerCase()
    .normalize('NFKD')
    .replace(/\p{M}+/gu, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();

export { norm as normalizeForIndex };

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
 * 3-tier search waterfall to shield PodcastIndex.org servers from common queries:
 * 1. Bundled local partial Podcast Index catalog (instant, offline, zero API load).
 * 2. Live Podcast Index API (only when local catalog returns no matches and API key is configured).
 * 3. Apple/iTunes fallback (only when both Podcast Index tiers return no matches and fallback is on).
 */
export async function resolveTiered(
  query,
  localResults,
  hasPodcastIndexApi,
  queryPodcastIndexApiFn,
  appleFallbackEnabled,
  fallbackAppleFn,
) {
  const local = mergeResults(query, [localResults]);
  if (local.length > 0) {
    return { results: local, tier: 'local' };
  }
  if (hasPodcastIndexApi) {
    const api = mergeResults(query, [await queryPodcastIndexApiFn()]);
    if (api.length > 0) {
      return { results: api, tier: 'api' };
    }
  }
  if (appleFallbackEnabled) {
    const apple = mergeResults(query, [await fallbackAppleFn()]);
    return { results: apple, tier: 'apple' };
  }
  return { results: [], tier: 'none' };
}
