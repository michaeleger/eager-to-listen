// RSS + Podcasting 2.0 parser. Follows android/.../parser/FeedParser.kt field for field where it
// matters for sync (episode IDs, feedOrder, dates, durations, which enclosure wins).
// DOMParser is passed in so tests can run under Node.

import { episodeId } from './feedUrl.js';

const MAX_EPISODE_DESCRIPTION_CHARS = 8000;
const MAX_CHANNEL_DESCRIPTION_CHARS = 4000;
const MAX_EPISODES_PER_FEED = 200;
const MAX_RAW_ITEMS_PER_FEED = 500;

const CHRONO_REGEX = /\b(serial|course|class|lecture|audiobook)\b|\bseason\s+\d+|\bhistory\s+of\b/i;
const NEWS_REGEX = /\b(news|briefing|daily\s+news)\b/i;

function transcriptTypeScore(type) {
  const t = (type || '').toLowerCase().trim();
  if (t === 'text/vtt') return 5;
  if (t === 'application/x-subrip' || t === 'text/srt') return 4;
  if (t === 'application/json') return 3;
  if (t === 'text/plain') return 2;
  if (t === 'text/html') return 1;
  return 0;
}

export function parseDuration(s) {
  const t = String(s ?? '').trim();
  if (/^-?\d+$/.test(t)) return Number(t);
  const parts = t.split(':');
  const n = (x) => {
    if (!/^-?\d+(\.\d+)?$/.test(x)) throw new Error('bad');
    return Number(x);
  };
  try {
    if (parts.length === 3) return Math.trunc(n(parts[0])) * 3600 + Math.trunc(n(parts[1])) * 60 + Math.trunc(n(parts[2]));
    if (parts.length === 2) return Math.trunc(n(parts[0])) * 60 + Math.trunc(n(parts[1]));
  } catch {
    return 0;
  }
  return 0;
}

export function parseDate(s) {
  const t = String(s ?? '').trim();
  if (!t) return 0;
  const ms = Date.parse(t);
  if (Number.isFinite(ms)) return ms;
  // "Mon, 3 Nov 2026 10:00:00" with no zone: Android treats it as UTC
  const ms2 = Date.parse(`${t} GMT`);
  return Number.isFinite(ms2) ? ms2 : 0;
}

export function inferPlayOrderStrategy(title, description = '', itunesType = '', category = '') {
  if (itunesType.toLowerCase() === 'serial') return 'CHRONOLOGICAL';
  if (category.toLowerCase().includes('news')) return 'NEWS';
  const combined = `${title} ${description} ${category}`;
  if (CHRONO_REGEX.test(combined)) return 'CHRONOLOGICAL';
  if (NEWS_REGEX.test(combined)) return 'NEWS';
  return 'EVERGREEN';
}

// Some DOMs (and feeds with undeclared prefixes) keep "itunes:type" as the local name
const lname = (el) => String(el.localName || el.nodeName || '').split(':').pop().toLowerCase();
const prefix = (el) => String(el.prefix || (String(el.nodeName).includes(':') ? String(el.nodeName).split(':')[0] : '')).toLowerCase();
const descendants = (el) => {
  const list = el.getElementsByTagName('*');
  return list.length ? Array.from(list) : Array.from(el.querySelectorAll ? el.querySelectorAll('*') : []);
};
const text = (el) => el.textContent ?? '';
const attr = (el, a) => el.getAttribute(a);
const children = (el) => Array.from(el.children ?? el.childNodes ?? []).filter((n) => n.nodeType === 1);

/**
 * @returns {{podcast: object, episodes: object[], newFeedUrl: string|null}}
 */
export function parseFeed(xmlText, podcastId, { DOMParser: DP = globalThis.DOMParser } = {}) {
  const doc = new DP().parseFromString(xmlText, 'application/xml');
  const all = descendants(doc);
  if (!all.length || all.some((e) => lname(e) === 'parsererror')) {
    throw new Error("That address didn't return a podcast feed.");
  }
  const channel = all.find((e) => lname(e) === 'channel');
  if (!channel) throw new Error("That address didn't return a podcast feed.");

  const ch = {
    title: '', description: '', author: '', artworkUrl: '', fundingUrl: null, fundingMessage: null,
    category: '', itunesType: '', newFeedUrl: null, podcastGuid: null,
  };
  const items = [];

  for (const el of children(channel)) {
    const name = lname(el);
    const pre = prefix(el);
    if (name === 'item') {
      items.push(el);
      continue;
    }
    if (name === 'title') {
      const t = text(el).trim();
      if (!ch.title && t) ch.title = t;
    } else if (name === 'description') {
      ch.description = text(el).slice(0, MAX_CHANNEL_DESCRIPTION_CHARS);
    } else if ((name === 'author' && pre === 'itunes') || name === 'managingeditor') {
      ch.author = text(el);
    } else if (name === 'image') {
      const href = attr(el, 'href');
      if (href) ch.artworkUrl = href;
      else if (!ch.artworkUrl) {
        const url = children(el).find((c) => lname(c) === 'url');
        if (url) ch.artworkUrl = text(url).trim();
      }
    } else if (name === 'category') {
      const cat = attr(el, 'text') || '';
      ch.category = ch.category ? `${ch.category}, ${cat}` : cat;
    } else if (name === 'type' && pre === 'itunes') {
      ch.itunesType = text(el).trim().toLowerCase();
    } else if (name === 'guid' && pre === 'podcast') {
      ch.podcastGuid = text(el).trim() || null;
    } else if (name === 'new-feed-url') {
      ch.newFeedUrl = text(el).trim() || null;
    } else if (name === 'funding') {
      ch.fundingUrl = attr(el, 'url');
      ch.fundingMessage = text(el).trim() || null;
    }
  }

  const episodes = [];
  let feedOrder = 0;
  for (const item of items) {
    const order = feedOrder++;
    if (episodes.length >= MAX_RAW_ITEMS_PER_FEED) continue;
    let title = '', guid = '', audioUrl = '', description = '', publishedAt = 0, durationSeconds = 0;
    let link = '';
    let artworkUrl = null, transcriptUrl = null, transcriptType = null, chaptersUrl = null, fundingUrl = null;

    for (const el of descendants(item)) {
      const name = lname(el);
      const pre = prefix(el);
      if (name === 'title' && el.parentNode === item) title = text(el);
      else if (name === 'link' && el.parentNode === item && !prefix(el)) link = text(el).trim();
      else if (name === 'guid' && el.parentNode === item) guid = text(el);
      else if (name === 'enclosure') {
        const url = attr(el, 'url');
        const type = attr(el, 'type');
        if (url && (type == null || /audio|mpeg|m3u8|aac|ogg/.test(type))) audioUrl = url;
      } else if (name === 'contentlink') {
        const href = attr(el, 'href');
        if (href && !audioUrl) audioUrl = href;
      } else if (name === 'description' || name === 'encoded') {
        const raw = text(el);
        if (raw.trim() && (!description.trim() || name === 'encoded')) description = raw.slice(0, MAX_EPISODE_DESCRIPTION_CHARS);
      } else if (name === 'pubdate') publishedAt = parseDate(text(el));
      else if (name === 'duration') durationSeconds = parseDuration(text(el));
      else if (name === 'image' && (pre === 'itunes' || pre === '')) {
        const href = attr(el, 'href');
        if (href) artworkUrl = href;
      } else if (name === 'transcript') {
        const url = attr(el, 'url');
        const type = attr(el, 'type');
        if (url && (transcriptUrl == null || transcriptTypeScore(type) > transcriptTypeScore(transcriptType))) {
          transcriptUrl = url;
          transcriptType = type;
        }
      } else if (name === 'chapters') chaptersUrl = attr(el, 'url');
      else if (name === 'funding') fundingUrl = attr(el, 'url');
    }

    const id = episodeId(podcastId, guid, audioUrl);
    if (!id || !audioUrl.trim()) continue;
    episodes.push({
      id,
      podcastId,
      title: title.trim() ? title : 'Untitled Episode',
      audioUrl,
      link: link || null,
      publishedAt,
      durationSeconds,
      description,
      artworkUrl: artworkUrl ?? ch.artworkUrl,
      transcriptUrl,
      transcriptType,
      chaptersUrl,
      fundingUrl: fundingUrl ?? ch.fundingUrl,
      feedOrder: order,
    });
  }

  const category = ch.category.trim() ? ch.category : 'General';
  const strategy = inferPlayOrderStrategy(ch.title, ch.description, ch.itunesType, category);
  let bounded = episodes;
  if (episodes.length > MAX_EPISODES_PER_FEED && strategy !== 'CHRONOLOGICAL') {
    bounded = [...episodes].sort((a, b) => b.publishedAt - a.publishedAt).slice(0, MAX_EPISODES_PER_FEED);
  }

  return {
    podcast: {
      title: ch.title,
      description: ch.description,
      author: ch.author,
      artworkUrl: ch.artworkUrl,
      category,
      fundingUrl: ch.fundingUrl,
      fundingMessage: ch.fundingMessage,
      podcastGuid: ch.podcastGuid,
      playOrderStrategy: strategy,
    },
    episodes: bounded,
    newFeedUrl: ch.newFeedUrl,
  };
}
