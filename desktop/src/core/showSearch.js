// Show search across directories. Mirrors android/.../data/podcastindex/ShowSearch.kt; change both.
//
// Podcast Index and Apple Podcasts are asked at the same time and the answers merged. Before,
// Apple was only asked when Podcast Index returned nothing, so a new show that Podcast Index
// hadn't picked up yet never appeared as long as Podcast Index found anything at all.

import { matchKey } from './feedUrl.js';

const norm = (s) => String(s ?? '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, ' ').trim();

/** How well a result's title (and author) matches what was typed. Higher is better. */
export function matchScore(query, result) {
  const q = norm(query);
  if (!q) return 0;
  const t = norm(result.title);
  const words = q.split(' ');
  const titleWords = new Set(t.split(' '));
  if (t === q) return 100;
  if (t.startsWith(`${q} `) || t.startsWith(q)) return 80;
  if (` ${t} `.includes(` ${q} `)) return 60;
  if (words.every((w) => titleWords.has(w))) return 40;
  const all = new Set(`${t} ${norm(result.author)}`.split(' '));
  if (words.every((w) => all.has(w))) return 20;
  return 0;
}

/** Same show? Same feed address (http/https and case ignored), podcast:guid, Apple ID, or title and author. */
function sameShow(a, b) {
  if (matchKey(a.feedUrl) === matchKey(b.feedUrl)) return true;
  if (a.podcastGuid && b.podcastGuid && a.podcastGuid === b.podcastGuid) return true;
  if (a.appleId && b.appleId && String(a.appleId) === String(b.appleId)) return true;
  const ta = norm(a.title);
  return !!ta && ta === norm(b.title) && norm(a.author) === norm(b.author);
}

/**
 * Merge result lists (best source first). Duplicates collapse into one entry that keeps the
 * address with the newest episode, since directories can hold a show's old address. Dead feeds
 * are dropped. Order: best title match, then how high any source ranked it, with shows both
 * directories agree on breaking ties.
 */
export function mergeResults(query, lists) {
  const merged = [];
  lists.forEach((list, sourceIndex) => {
    (list || []).forEach((r, pos) => {
      if (!r?.feedUrl || r.dead) return;
      const hit = merged.find((m) => sameShow(m.item, r));
      if (hit) {
        hit.bestPos = Math.min(hit.bestPos, pos);
        hit.sources.add(sourceIndex);
        if ((r.newestEpisodeAt || 0) > (hit.item.newestEpisodeAt || 0)) {
          hit.item = { ...hit.item, feedUrl: r.feedUrl, newestEpisodeAt: r.newestEpisodeAt };
        }
        hit.item = {
          ...hit.item,
          artworkUrl: hit.item.artworkUrl || r.artworkUrl,
          podcastGuid: hit.item.podcastGuid || r.podcastGuid,
          appleId: hit.item.appleId || r.appleId,
        };
      } else {
        merged.push({ item: { ...r }, bestPos: pos, firstSource: sourceIndex, sources: new Set([sourceIndex]), order: merged.length });
      }
    });
  });
  for (const m of merged) m.score = matchScore(query, m.item) + (m.sources.size > 1 ? 5 : 0);
  merged.sort((a, b) => b.score - a.score || a.bestPos - b.bestPos || a.firstSource - b.firstSource || a.order - b.order);
  return merged.map((m) => m.item);
}

/** Podcast Index search/byterm or bycategory feeds -> results. */
export function fromPodcastIndex(json) {
  return (json?.feeds || [])
    .filter((f) => f.url)
    .map((f) => ({
      title: f.title || 'Untitled',
      author: f.author || f.ownerName || '',
      feedUrl: f.url,
      artworkUrl: f.artwork || f.image || '',
      podcastGuid: f.podcastGuid || null,
      appleId: f.itunesId ? String(f.itunesId) : null,
      newestEpisodeAt: (f.newestItemPubdate || f.lastUpdateTime || 0) * 1000,
      dead: f.dead === 1 || f.dead === true,
      source: 'Podcast Index',
    }));
}

/** Apple Podcasts search or lookup results -> results. */
export function fromApple(json) {
  return (json?.results || [])
    .filter((r) => r.feedUrl)
    .map((r) => ({
      title: r.collectionName || r.trackName || 'Untitled',
      author: r.artistName || '',
      feedUrl: r.feedUrl,
      artworkUrl: r.artworkUrl600 || r.artworkUrl100 || '',
      podcastGuid: null,
      appleId: r.collectionId ? String(r.collectionId) : null,
      newestEpisodeAt: Date.parse(r.releaseDate || '') || 0,
      dead: false,
      source: 'Apple Podcasts',
    }));
}
