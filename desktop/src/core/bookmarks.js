// Creator Inspiration Studio: bookmarks ("inspirations"), the Drive archive, and the share list.
// The phone app follows the same rules (data/sync/BookmarkArchive.kt). Change both together.
//
// Workflow: capture while listening -> archive to Drive on every sync (never cleared) ->
// add notes in a batch -> share the whole list -> clear what was shared from the app.

import { excerptAt } from './transcript.js';

export const ARCHIVE_FILE = 'eagertolisten_bookmarks.jsonl';
export const ARCHIVE_VERSION = 1;
/** Save Idea marks the spot this many seconds before the tap, so it catches what you just heard. */
export const LEAD_IN_SECONDS = 15;
const REMOVED_MAX_AGE_MS = 365 * 24 * 60 * 60 * 1000;

/** The main archive file plus Drive conflict copies like "eagertolisten_bookmarks (1).jsonl". */
export function isArchiveFileName(name) {
  return typeof name === 'string' && name.startsWith('eagertolisten_bookmarks') && name.endsWith('.jsonl') && !name.endsWith('.tmp.jsonl');
}

export function formatTs(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const mm = String(m).padStart(2, '0'), ss = String(s).padStart(2, '0');
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Audio link that starts at the moment (media fragment; browsers and many players honor it). */
export function momentUrl(audioUrl, sec) {
  if (!audioUrl) return '';
  return `${audioUrl.split('#')[0]}#t=${Math.max(0, Math.floor(sec))}`;
}

const editedAt = (c) => c.updatedAt || c.createdAt || 0;

// ---------- library actions ----------

export function captureBookmark(lib, { episode, podcast, positionSeconds, source = 'Desktop', now = Date.now(), id = crypto.randomUUID() }) {
  const clip = {
    id,
    episodeId: episode.id,
    podcastId: episode.podcastId,
    podcastTitle: podcast?.title || episode.podcastTitle || '',
    episodeTitle: episode.title,
    timestampSeconds: Math.max(0, Math.floor(positionSeconds) - LEAD_IN_SECONDS),
    audioUrl: episode.audioUrl,
    note: '',
    quoteText: '',
    tags: `Inspiration, ${source}`,
    createdAt: now,
    updatedAt: now,
  };
  lib.inspirations[id] = clip;
  return clip;
}

export function updateBookmark(lib, id, fields, now = Date.now()) {
  const c = lib.inspirations[id];
  if (!c) return null;
  Object.assign(c, fields, { updatedAt: Math.max(now, editedAt(c) + 1) });
  return c;
}

/** Delete or clear: removed here, and the removal syncs so the other device drops it too. */
export function removeBookmarks(lib, ids, now = Date.now()) {
  lib.inspirationsRemoved = lib.inspirationsRemoved || {};
  lib.removedUnarchived = lib.removedUnarchived || {};
  for (const id of ids) {
    const c = lib.inspirations[id];
    const at = Math.max(now, c ? editedAt(c) : 0);
    // Not in the Drive archive yet (new, or edited since): hold a copy until the next archive pass
    if (c && !isArchived(lib, c)) lib.removedUnarchived[id] = { ...c };
    delete lib.inspirations[id];
    lib.inspirationsRemoved[id] = at;
  }
  for (const [id, at] of Object.entries(lib.inspirationsRemoved)) {
    if (now - at > REMOVED_MAX_AGE_MS) delete lib.inspirationsRemoved[id];
  }
}

/** Bookmarks whose current version is safely in the Drive archive (OK to clear from the app). */
export function isArchived(lib, clip) {
  return (lib.archived?.[clip.id] ?? -1) >= editedAt(clip);
}

export function sortedBookmarks(lib) {
  return Object.values(lib.inspirations).sort((a, b) => b.createdAt - a.createdAt);
}

// ---------- archive (JSON Lines in the Drive sync folder) ----------

/** One archive line: everything needed to find and use the moment later, standing on its own. */
export function archiveRecord(clip, { episode = null, podcast = null, meta = null, device = '', removedAt = null } = {}) {
  return {
    type: 'bookmark',
    v: ARCHIVE_VERSION,
    id: clip.id,
    createdAt: clip.createdAt,
    updatedAt: editedAt(clip),
    removedAt: removedAt ?? null,
    capturedOn: device,
    show: {
      title: podcast?.title || clip.podcastTitle || '',
      feedUrl: podcast?.feedUrl || clip.podcastId || '',
    },
    episode: {
      id: clip.episodeId,
      title: episode?.title || clip.episodeTitle || '',
      link: episode?.link || null,
      publishedAt: episode?.publishedAt || null,
      audioUrl: clip.audioUrl || episode?.audioUrl || '',
    },
    timestampSeconds: clip.timestampSeconds,
    timestamp: formatTs(clip.timestampSeconds),
    audioAtMoment: momentUrl(clip.audioUrl || episode?.audioUrl, clip.timestampSeconds),
    transcript: {
      url: episode?.transcriptUrl || null,
      type: episode?.transcriptType || null,
      excerpt: meta?.transcriptExcerpt ?? null,
      timing: meta?.timing ?? null,
    },
    note: clip.note || '',
    quote: clip.quoteText || '',
    tags: clip.tags || '',
  };
}

/** Read one or more archive files; the newest version of each bookmark wins. Bad lines are kept aside. */
export function parseArchive(...texts) {
  const byId = new Map();
  const bad = [];
  for (const text of texts) {
    for (const line of String(text || '').split('\n')) {
      if (!line.trim()) continue;
      let r;
      try {
        r = JSON.parse(line);
      } catch {
        bad.push(line);
        continue;
      }
      if (!r || !r.id) continue;
      byId.set(r.id, mergeRecord(byId.get(r.id), r));
    }
  }
  return { records: byId, bad };
}

/** Combine two versions of one bookmark's archive record without ever losing information. */
export function mergeRecord(a, b) {
  if (!a) return b;
  if (!b) return a;
  const [older, newer] = (b.updatedAt || 0) >= (a.updatedAt || 0) ? [a, b] : [b, a];
  const out = { ...older, ...newer };
  out.show = { ...older.show, ...stripEmpty(newer.show) };
  out.episode = { ...older.episode, ...stripEmpty(newer.episode) };
  out.transcript = { ...older.transcript, ...stripEmpty(newer.transcript) };
  out.removedAt = a.removedAt ?? b.removedAt ?? null;
  if (a.removedAt != null && b.removedAt != null) out.removedAt = Math.max(a.removedAt, b.removedAt);
  out.createdAt = Math.min(a.createdAt || Infinity, b.createdAt || Infinity);
  if (!Number.isFinite(out.createdAt)) out.createdAt = newer.createdAt;
  return out;
}

function stripEmpty(o) {
  return Object.fromEntries(Object.entries(o || {}).filter(([, v]) => v !== null && v !== undefined && v !== ''));
}

export function serializeArchive(records, bad = []) {
  const lines = [...records.values()].sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0) || String(a.id).localeCompare(String(b.id))).map((r) => JSON.stringify(r));
  // Lines we couldn't read are carried along untouched rather than dropped
  return [...lines, ...bad].join('\n') + '\n';
}

/**
 * One archive pass, run after each sync: merge the archive file(s) in the sync folder with this
 * device's bookmarks and removals, write one file back, and remember what's confirmed safe.
 * io: { list(target), read(target, name), write(target, name, text), remove(target, name), transcript(url) }
 */
export async function runArchive(lib, io, { now = Date.now() } = {}) {
  const target = lib.settings.syncFolder;
  if (!target) throw new Error('No sync folder set.');
  const names = (await io.list(target)).filter(isArchiveFileName);
  const texts = [];
  const unreadable = new Set();
  for (const name of names) {
    try {
      texts.push(await io.read(target, name));
    } catch {
      unreadable.add(name);
    }
  }
  const { records, bad } = parseArchive(...texts);
  const before = serializeArchive(records, bad);

  lib.bookmarkMeta = lib.bookmarkMeta || {};
  const transcripts = new Map();
  const held = Object.values(lib.removedUnarchived || {});
  for (const clip of [...Object.values(lib.inspirations), ...held]) {
    const episode = lib.episodes[clip.episodeId] || null;
    const podcast = lib.podcasts[clip.podcastId] || null;
    let meta = lib.bookmarkMeta[clip.id];
    const existing = records.get(clip.id);
    if (!meta && existing?.transcript?.excerpt) meta = { transcriptExcerpt: existing.transcript.excerpt, timing: existing.transcript.timing };
    if (!meta && episode?.transcriptUrl && io.transcript) {
      try {
        if (!transcripts.has(episode.transcriptUrl)) transcripts.set(episode.transcriptUrl, io.transcript(episode.transcriptUrl, episode.transcriptType, episode.durationSeconds));
        const parsed = await transcripts.get(episode.transcriptUrl);
        const ex = excerptAt(parsed, clip.timestampSeconds);
        meta = ex ? { transcriptExcerpt: ex.text, timing: ex.timing } : null;
      } catch {
        meta = null; // try again next sync
      }
    }
    if (meta) lib.bookmarkMeta[clip.id] = meta;
    records.set(clip.id, mergeRecord(existing, archiveRecord(clip, { episode, podcast, meta, device: lib.deviceId || '' })));
  }
  for (const [id, at] of Object.entries(lib.inspirationsRemoved || {})) {
    const r = records.get(id);
    if (r && !(r.removedAt >= at)) records.set(id, { ...r, removedAt: at });
  }

  const after = serializeArchive(records, bad);
  const copies = names.filter((n) => n !== ARCHIVE_FILE);
  if (after !== before || copies.length || !names.includes(ARCHIVE_FILE)) {
    await io.write(target, ARCHIVE_FILE, after);
    for (const n of copies) {
      if (!unreadable.has(n)) {
        try {
          await io.remove(target, n);
        } catch {
          /* merged next time */
        }
      }
    }
  }
  lib.removedUnarchived = {};
  lib.archived = lib.archived || {};
  for (const clip of Object.values(lib.inspirations)) lib.archived[clip.id] = editedAt(clip);
  for (const id of Object.keys(lib.archived)) if (!lib.inspirations[id]) delete lib.archived[id];
  return { total: records.size, written: after !== before };
}

// ---------- share list (Markdown) ----------

/** The whole list as Markdown, grouped by show then episode, in timestamp order. */
export function shareMarkdown(lib, clips, { now = Date.now() } = {}) {
  const shows = new Map();
  for (const c of clips) {
    const ep = lib.episodes[c.episodeId];
    const showTitle = lib.podcasts[c.podcastId]?.title || c.podcastTitle || 'Unknown show';
    if (!shows.has(showTitle)) shows.set(showTitle, new Map());
    const eps = shows.get(showTitle);
    const key = c.episodeId;
    if (!eps.has(key)) eps.set(key, { ep, title: ep?.title || c.episodeTitle, clips: [] });
    eps.get(key).clips.push(c);
  }
  const d = new Date(now);
  const out = [`# Podcast bookmarks (${clips.length}), shared ${d.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })}`, ''];
  for (const [showTitle, eps] of shows) {
    out.push(`## ${showTitle}`, '');
    for (const { ep, title, clips: list } of eps.values()) {
      out.push(`### ${title}`);
      const links = [];
      if (ep?.link) links.push(`[Episode page](${ep.link})`);
      const audio = list[0].audioUrl || ep?.audioUrl;
      if (audio) links.push(`[Audio](${audio})`);
      if (ep?.transcriptUrl) links.push(`[Transcript](${ep.transcriptUrl})`);
      if (links.length) out.push(links.join(' · '));
      out.push('');
      for (const c of [...list].sort((a, b) => a.timestampSeconds - b.timestampSeconds)) {
        out.push(`- **[${formatTs(c.timestampSeconds)}](${momentUrl(c.audioUrl || audio, c.timestampSeconds)})**${c.note ? ` ${c.note}` : ''}`);
        if (c.quoteText) out.push(`  > ${c.quoteText.replace(/\n+/g, ' ')}`);
        const meta = lib.bookmarkMeta?.[c.id];
        if (meta?.transcriptExcerpt) {
          out.push(`  > ${meta.transcriptExcerpt}${meta.timing === 'estimated' ? ' *(transcript position estimated)*' : ''}`);
        }
      }
      out.push('');
    }
  }
  return out.join('\n').trimEnd() + '\n';
}
