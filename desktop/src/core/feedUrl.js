// Feed address and episode ID rules. Mirrors android/.../data/model/FeedUrl.kt and EpisodeId.kt
// exactly: both apps must produce the same IDs or synced progress won't line up.

// Characters java.net.URI rejects; Android falls back to "trim + drop trailing slashes" for these.
const URI_ILLEGAL = /[\s"<>\\^`{|}]/;
// scheme://authority path ?query #fragment
const URI_RE = /^(?:([A-Za-z][A-Za-z0-9+.-]*):)?\/\/([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/;
// What java.net.URI accepts as a server-based host (no underscores etc.)
const HOST_RE = /^(?:\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\.?)$/;

function trimEnd(s, ch) {
  let end = s.length;
  while (end > 0 && s[end - 1] === ch) end--;
  return s.slice(0, end);
}

// Kotlin String.trim() trims chars <= ' '
function ktTrim(s) {
  let a = 0;
  let b = s.length;
  while (a < b && s.charCodeAt(a) <= 32) a++;
  while (b > a && s.charCodeAt(b - 1) <= 32) b--;
  return s.slice(a, b);
}

export function normalize(rawUrl) {
  const trimmed = ktTrim(String(rawUrl ?? ''));
  if (!trimmed) return '';
  const fallback = trimEnd(trimmed, '/');
  if (URI_ILLEGAL.test(trimmed)) return fallback;

  const m = URI_RE.exec(trimmed);
  if (!m) return fallback; // no "//authority": URI has no host

  const scheme = (m[1] || 'https').toLowerCase();
  let authority = m[2];
  const at = authority.lastIndexOf('@');
  if (at >= 0) authority = authority.slice(at + 1); // userinfo is dropped, as on Android

  let host = authority;
  let port = -1;
  const portMatch = /^(.*):(\d*)$/.exec(authority);
  if (portMatch && !authority.endsWith(']')) {
    host = portMatch[1];
    port = portMatch[2] === '' ? -1 : Number(portMatch[2]);
  }
  if (!host || !HOST_RE.test(host)) return fallback;
  host = host.toLowerCase();

  const portStr = port !== -1 && port !== 80 && port !== 443 ? `:${port}` : '';
  let path = m[3] || '';
  if (path.length > 1) path = trimEnd(path, '/') || '/';
  const query = m[4] ? `?${m[4]}` : '';
  const fragment = m[5] ? `#${m[5]}` : '';
  return `${scheme}://${host}${portStr}${path}${query}${fragment}`;
}

/** Comparison key for sameFeed: normalized, scheme-less, lowercase. */
export function matchKey(url) {
  let s = normalize(url) || ktTrim(String(url ?? ''));
  if (s.startsWith('https://')) s = s.slice(8);
  if (s.startsWith('http://')) s = s.slice(7);
  return s.toLowerCase();
}

/** True when two feed URLs point at the same feed (ignores http vs https, case, trailing slash). */
export function sameFeed(a, b) {
  const ka = matchKey(a);
  return ka.length > 0 && ka === matchKey(b);
}

export function episodeId(podcastId, guid, enclosureUrl) {
  const cleanFeedId = normalize(podcastId) || ktTrim(String(podcastId ?? ''));
  const g = guid == null ? '' : ktTrim(String(guid));
  const cleanIdentifier = g || ktTrim(String(enclosureUrl ?? ''));
  return `${cleanFeedId}::${cleanIdentifier}`;
}

/** Local folder shows are device-only and never synced (LocalFolderFormat.isLocalFeed). */
export function isLocalFeed(id) {
  return typeof id === 'string' && id.startsWith('local:');
}
