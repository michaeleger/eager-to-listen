// Turning whatever someone opens, shares or pastes into a podcast feed. Mirrors
// android/.../data/model/FeedLink.kt rule for rule; change both together.
//
// Handles: plain feed URLs, feed:/podcast:/pcast:/itpc: links, feed:https://..., the
// subscribeonandroid.com redirector, Apple Podcasts and Overcast show links (by Apple ID), and
// links buried in shared text ("Check this out: https://..."). A web page is fine too: the feed
// is found from the page's <link rel="alternate"> tags.

/** Default priority for a show added from a link. */
export const LINK_DEFAULT_PRIORITY = 2;

const SCHEME_ALIASES = ['feed', 'podcast', 'podcasts', 'pcast', 'itpc', 'rss', 'antennapod-subscribe'];
const URL_IN_TEXT = new RegExp(`\\b(?:https?|${SCHEME_ALIASES.join('|')}):[^\\s<>"']+`, 'i');

/**
 * @returns {{kind:'feed', url:string, schemeGuessed:boolean}
 *         | {kind:'apple', id:string}
 *         | {kind:'search', term:string}
 *         | {kind:'none'}}
 */
export function resolveLink(input) {
  const text = String(input ?? '').trim();
  if (!text) return { kind: 'none' };
  const m = URL_IN_TEXT.exec(text);
  if (!m) return { kind: 'search', term: text };
  // Trailing punctuation from prose ("...feed.xml).") isn't part of the link
  let link = m[0].replace(/[),.;!?\]]+$/, '');

  // feed:https://example.com/rss  ->  https://example.com/rss
  const nested = /^([a-z-]+):(https?:\/\/.+)$/i.exec(link);
  if (nested && SCHEME_ALIASES.includes(nested[1].toLowerCase())) return resolveHttp(nested[2], false);

  const scheme = /^([a-z][a-z0-9+.-]*):(?:\/\/)?(.*)$/i.exec(link);
  if (!scheme) return { kind: 'search', term: text };
  const s = scheme[1].toLowerCase();
  if (s === 'http' || s === 'https') return resolveHttp(link, false);
  if (SCHEME_ALIASES.includes(s)) {
    const rest = scheme[2];
    if (!rest) return { kind: 'none' };
    // These links don't say http or https: try https first (the app falls back to http)
    return resolveHttp(`https://${rest}`, true);
  }
  return { kind: 'search', term: text };
}

function resolveHttp(url, schemeGuessed) {
  let u;
  try {
    u = new URL(url);
  } catch {
    return { kind: 'none' };
  }
  if (!u.hostname) return { kind: 'feed', url: url.trim(), schemeGuessed };
  const host = u.hostname.toLowerCase().replace(/^www\./, '');
  // https://subscribeonandroid.com/feeds.example.com/show.xml
  if (host === 'subscribeonandroid.com' && u.pathname.length > 1) {
    const rest = decodeURIComponent(u.pathname.slice(1)) + u.search;
    if (/^https?:\/\//i.test(rest)) return resolveHttp(rest, false);
    return resolveHttp(`https://${rest}`, true);
  }
  // https://podcasts.apple.com/us/podcast/some-show/id1234567890
  if (host === 'podcasts.apple.com' || host === 'itunes.apple.com') {
    const id = /\/id(\d+)/.exec(u.pathname) || /[?&]id=(\d+)/.exec(u.search);
    if (id) return { kind: 'apple', id: id[1] };
  }
  // https://overcast.fm/itunes1234567890/some-show
  if (host === 'overcast.fm') {
    const id = /^\/itunes(\d+)/.exec(u.pathname);
    if (id) return { kind: 'apple', id: id[1] };
  }
  return { kind: 'feed', url: url.trim(), schemeGuessed };
}

/** The http fallback for a link that didn't say which scheme it uses. */
export function httpFallback(url) {
  return /^https:\/\//i.test(url) ? `http://${url.slice(8)}` : null;
}

/** Does this fetched body look like a web page rather than a feed? */
export function looksLikeHtml(body, contentType = '') {
  if (/html/i.test(contentType) && !/xml/i.test(contentType)) return true;
  const head = String(body ?? '').slice(0, 1024).trimStart().toLowerCase();
  return head.startsWith('<!doctype html') || head.startsWith('<html');
}

/**
 * Feed addresses a web page advertises with <link rel="alternate" type="application/rss+xml">
 * (or Atom), resolved against the page address, in page order, without duplicates.
 */
export function discoverFeedLinks(html, pageUrl) {
  const out = [];
  const tags = String(html ?? '').match(/<link\b[^>]*>/gi) || [];
  for (const tag of tags) {
    const attr = (name) => {
      const m = new RegExp(`\\b${name}\\s*=\\s*("([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i').exec(tag);
      return m ? (m[2] ?? m[3] ?? m[4] ?? '').trim() : '';
    };
    const rel = attr('rel').toLowerCase().split(/\s+/);
    const type = attr('type').toLowerCase();
    const href = attr('href').replace(/&amp;/g, '&');
    if (!rel.includes('alternate') || !href) continue;
    if (!/(rss|atom)\+xml|application\/xml|text\/xml/.test(type)) continue;
    let abs;
    try {
      abs = new URL(href, pageUrl).toString();
    } catch {
      continue;
    }
    // Skip per-post comment feeds that blogs advertise next to the main feed
    if (/\/comments\/feed\/?$/i.test(abs)) continue;
    if (!out.includes(abs)) out.push(abs);
  }
  return out;
}
