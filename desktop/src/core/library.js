// The desktop library: shows, episodes, queue, bookmarks, and sync bookkeeping, kept as one plain
// object that the Rust side saves to disk. The operations below mirror the Android DAOs and
// PodcastRepository so the same actions leave the same state on both devices.

import { buildQueue, parseStrategy } from './queueEngine.js';
import { isLocalFeed } from './feedUrl.js';

export const LIBRARY_VERSION = 1;
const UNPIN_MAX_AGE_MS = 90 * 24 * 60 * 60 * 1000;

export function emptyLibrary() {
  return {
    version: LIBRARY_VERSION,
    podcasts: {}, // id -> podcast
    episodes: {}, // id -> episode
    queue: [], // [{episodeId, podcastId, orderIndex, addedAt, isManual, manualIndex}]
    inspirations: {}, // id -> clip
    pending: {}, // episodeId -> sync state waiting for its feed to load
    unpins: {}, // episodeId -> removedAt (pins removed by hand, R2.7)
    settings: {
      globalConsecutiveLimit: 1,
      defaultNewsDays: 3,
      seekBackSeconds: 15,
      seekForwardSeconds: 90,
      syncFolder: null,
      autoSync: true,
    },
    deviceId: null,
    nowPlayingId: null, // last episode in the player, restored paused on start
    lastSyncAt: 0,
  };
}

export function newPodcast(fields) {
  return {
    id: '',
    feedUrl: '',
    title: 'Podcast',
    artworkUrl: '',
    author: '',
    description: '',
    priority: 3,
    playOrderStrategy: 'EVERGREEN',
    skipIntroSeconds: 0,
    skipOutroSeconds: 0,
    playbackSpeed: 1.0,
    autoAddToQueue: true,
    maxEpisodesInQueue: 5,
    consecutivePlayLimit: -1,
    newsRetentionDays: 3,
    category: 'General',
    customDonationUrl: null,
    fundingUrl: null,
    podcastGuid: null,
    subscribedAt: Date.now(),
    unsubscribedAt: null,
    settingsUpdatedAt: 0,
    lastRefreshedAt: 0,
    ...fields,
  };
}

export function newEpisode(fields) {
  return {
    id: '',
    podcastId: '',
    podcastTitle: '',
    title: '',
    description: '',
    audioUrl: '',
    artworkUrl: '',
    publishedAt: 0,
    feedOrder: 0,
    durationSeconds: 0,
    playbackPositionSeconds: 0,
    isCompleted: false,
    completedAt: null,
    isDismissed: false,
    dismissReason: null,
    skippedAt: null,
    skipCount: 0,
    updatedAt: 0,
    transcriptUrl: null,
    transcriptType: null,
    chaptersUrl: null,
    downloadPath: null,
    ...fields,
  };
}

// ---------- queries ----------

export const subscribed = (lib) => Object.values(lib.podcasts).filter((p) => p.unsubscribedAt == null);
export const pinnedItems = (lib) =>
  lib.queue.filter((q) => q.isManual).sort((a, b) => (a.manualIndex ?? 0) - (b.manualIndex ?? 0));
export const queuedEpisodes = (lib) =>
  [...lib.queue].sort((a, b) => a.orderIndex - b.orderIndex).map((q) => lib.episodes[q.episodeId]).filter(Boolean);

// EpisodeDao.getEpisodesForSync
export function episodesForSync(lib) {
  return Object.values(lib.episodes).filter(
    (e) => e.playbackPositionSeconds > 0 || e.isCompleted || e.isDismissed || e.skipCount > 0 || e.dismissReason === 'UNPLAYED',
  );
}

// ---------- episode state (EpisodeDao) ----------

export function updatePlaybackProgress(lib, episodeId, positionSec, now = Date.now()) {
  const e = lib.episodes[episodeId];
  if (!e || e.isCompleted) return; // I2: never un-complete except "mark unplayed"
  e.playbackPositionSeconds = Math.max(0, Math.floor(positionSec));
  e.updatedAt = now;
}

export function markFinished(lib, episodeId, now = Date.now()) {
  const e = lib.episodes[episodeId];
  if (!e) return;
  e.isCompleted = true;
  e.completedAt = now;
  e.playbackPositionSeconds = e.durationSeconds;
  e.dismissReason = null;
  e.updatedAt = now;
  removeFromQueue(lib, episodeId, { byHand: false });
  rebuildQueue(lib, { now });
}

export function markSkipped(lib, episodeId, positionSec, now = Date.now()) {
  const e = lib.episodes[episodeId];
  if (!e) return;
  e.skippedAt = now;
  e.skipCount += 1;
  e.playbackPositionSeconds = Math.max(0, Math.floor(positionSec));
  e.updatedAt = now;
  removeFromQueue(lib, episodeId, { byHand: true });
  rebuildQueue(lib, { now });
}

export function markUnplayed(lib, episodeId, now = Date.now()) {
  const e = lib.episodes[episodeId];
  if (!e) return;
  Object.assign(e, {
    isCompleted: false,
    completedAt: null,
    playbackPositionSeconds: 0,
    skippedAt: null,
    skipCount: 0,
    isDismissed: false,
    dismissReason: 'UNPLAYED',
    updatedAt: now,
  });
  rebuildQueue(lib, { now });
}

// ---------- queue (QueueDao + PodcastRepository) ----------

/** Remove one item; pins removed by hand are remembered so sync doesn't bring them back. */
export function removeFromQueue(lib, episodeId, { byHand = true, now = Date.now() } = {}) {
  const item = lib.queue.find((q) => q.episodeId === episodeId);
  if (!item) return false;
  if (byHand && item.isManual) recordUnpins(lib, [episodeId], now);
  lib.queue = lib.queue.filter((q) => q.episodeId !== episodeId);
  return true;
}

export function clearQueue(lib, now = Date.now()) {
  recordUnpins(lib, pinnedItems(lib).map((q) => q.episodeId), now);
  lib.queue = [];
}

export function recordUnpins(lib, ids, at = Date.now()) {
  for (const id of ids) if (!(lib.unpins[id] >= at)) lib.unpins[id] = at;
  pruneUnpins(lib, at);
}

export function pruneUnpins(lib, now = Date.now()) {
  for (const [id, at] of Object.entries(lib.unpins)) if (now - at > UNPIN_MAX_AGE_MS) delete lib.unpins[id];
}

/** "Play Next": pin to the very top (QueueDao.enqueueEpisodeTop). */
export function playNext(lib, episodeId, now = Date.now()) {
  const e = lib.episodes[episodeId];
  if (!e) return;
  lib.queue = lib.queue.filter((q) => q.episodeId !== episodeId);
  const minManual = Math.min(0, ...pinnedItems(lib).map((q) => q.manualIndex ?? 0));
  lib.queue.push({ episodeId, podcastId: e.podcastId, orderIndex: 0, addedAt: now, isManual: true, manualIndex: minManual - 1 });
  rebuildQueue(lib, { now });
}

/** "Add to queue" by hand: pin at the end of the pinned block (QueueDao.appendManualEpisode). */
export function addToQueue(lib, episodeId, now = Date.now()) {
  const e = lib.episodes[episodeId];
  if (!e) return;
  lib.queue = lib.queue.filter((q) => q.episodeId !== episodeId);
  const maxManual = Math.max(0, ...pinnedItems(lib).map((q) => q.manualIndex ?? 0));
  const maxOrder = Math.max(0, ...lib.queue.map((q) => q.orderIndex));
  lib.queue.push({ episodeId, podcastId: e.podcastId, orderIndex: maxOrder + 1, addedAt: now, isManual: true, manualIndex: maxManual + 1 });
  rebuildQueue(lib, { now });
}

/** lastTurnAt per feed: newest completedAt/skippedAt among its episodes (EpisodeDao.getAllLastTurns). */
function lastTurns(lib) {
  const out = {};
  for (const e of Object.values(lib.episodes)) {
    const t = Math.max(e.completedAt ?? 0, e.skippedAt ?? 0);
    if (t > (out[e.podcastId] ?? 0)) out[e.podcastId] = t;
  }
  return out;
}

/** Expire old NEWS episodes the way PodcastRepository.purgeExpiredNewsEpisodes does. */
export function purgeExpiredNews(lib, now = Date.now()) {
  for (const p of subscribed(lib)) {
    if (parseStrategy(p.playOrderStrategy) !== 'NEWS') continue;
    const days = p.newsRetentionDays > 0 ? p.newsRetentionDays : lib.settings.defaultNewsDays;
    const cutoff = now - days * 86_400_000;
    for (const e of Object.values(lib.episodes)) {
      if (e.podcastId !== p.id || e.isCompleted || e.isDismissed) continue;
      if (e.publishedAt > 0 && e.publishedAt < cutoff) {
        e.isDismissed = true;
        e.dismissReason = 'EXPIRED_NEWS';
        e.updatedAt = now;
        lib.queue = lib.queue.filter((q) => q.episodeId !== e.id);
      }
    }
  }
}

/** Feed moved off NEWS: bring back what it aged out (EpisodeDao.restoreExpiredNews). */
export function restoreExpiredNews(lib, podcastId, now = Date.now()) {
  for (const e of Object.values(lib.episodes)) {
    if (e.podcastId === podcastId && e.isDismissed && e.dismissReason === 'EXPIRED_NEWS') {
      e.isDismissed = false;
      e.dismissReason = null;
      e.updatedAt = now;
    }
  }
}

/** PodcastRepository.generateProgrammedQueue: rebuild the whole queue from the rules. */
export function rebuildQueue(lib, { now = Date.now(), nowPlayingId = null } = {}) {
  purgeExpiredNews(lib, now);
  const podcasts = subscribed(lib);
  const turns = lastTurns(lib);
  const feeds = podcasts.map((p) => ({
    id: p.id,
    title: p.title,
    priority: p.priority,
    strategy: parseStrategy(p.playOrderStrategy),
    newsRetentionDays: p.newsRetentionDays,
    consecutiveLimit: p.consecutivePlayLimit,
    maxInQueue: p.maxEpisodesInQueue,
    autoAdd: p.autoAddToQueue,
    lastTurnAt: turns[p.id] ?? 0,
    subscribedAt: p.subscribedAt,
  }));
  const ids = new Set(podcasts.map((p) => p.id));
  const episodes = Object.values(lib.episodes)
    .filter((e) => ids.has(e.podcastId) && !e.isCompleted && !e.isDismissed)
    .map((e) => ({
      id: e.id,
      feedId: e.podcastId,
      publishedAt: e.publishedAt,
      feedOrder: e.feedOrder,
      positionSec: e.playbackPositionSeconds,
      isCompleted: e.isCompleted,
      isDismissed: e.isDismissed,
      skippedAt: e.skippedAt,
      skipCount: e.skipCount,
    }));

  const pins = pinnedItems(lib);
  const pinMap = new Map(pins.map((q) => [q.episodeId, q]));

  // Same rule as Android: keep the current episode on top if it's started
  if (nowPlayingId == null) {
    const first = queuedEpisodes(lib)[0];
    if (first && !first.isCompleted && !first.isDismissed && first.playbackPositionSeconds > 0) nowPlayingId = first.id;
  }

  const order = buildQueue({
    feeds,
    episodes,
    pinned: pins.map((q) => ({ episodeId: q.episodeId, manualIndex: q.manualIndex ?? 0 })),
    nowPlayingId,
    now,
    globalLimit: lib.settings.globalConsecutiveLimit,
    defaultNewsDays: lib.settings.defaultNewsDays,
  });

  lib.queue = order.map((id, index) => {
    const pin = pinMap.get(id);
    return {
      episodeId: id,
      podcastId: lib.episodes[id]?.podcastId ?? '',
      orderIndex: index,
      // Keep a pin's original time: sync compares it with removals from other devices
      addedAt: pin ? pin.addedAt : now,
      isManual: !!pin,
      manualIndex: pin ? pin.manualIndex : null,
    };
  });
  return lib.queue;
}

// ---------- shows ----------

export function unsubscribe(lib, podcastId, now = Date.now()) {
  const p = lib.podcasts[podcastId];
  if (!p) return;
  p.unsubscribedAt = now;
  lib.queue = lib.queue.filter((q) => q.podcastId !== podcastId);
}

export function updateRules(lib, podcastId, rules, now = Date.now()) {
  const p = lib.podcasts[podcastId];
  if (!p) return;
  Object.assign(p, rules, { settingsUpdatedAt: now });
  if (parseStrategy(p.playOrderStrategy) !== 'NEWS') restoreExpiredNews(lib, podcastId, now);
  rebuildQueue(lib, { now });
}

/**
 * Merge freshly parsed feed items into the library. New episodes are added; existing ones get
 * metadata updates only (never playback state). Pending sync states for this show are applied,
 * as PodcastRepository.refreshFeedInternal does.
 */
export function applyFeedRefresh(lib, podcastId, parsed, now = Date.now()) {
  const p = lib.podcasts[podcastId];
  if (!p) return { added: 0 };
  const meta = parsed.podcast || {};
  for (const k of ['title', 'artworkUrl', 'author', 'description', 'fundingUrl', 'podcastGuid']) {
    if (meta[k]) p[k] = meta[k];
  }
  p.lastRefreshedAt = now;
  let added = 0;
  parsed.episodes.forEach((item, idx) => {
    const existing = lib.episodes[item.id];
    if (existing) {
      Object.assign(existing, {
        title: item.title || existing.title,
        description: item.description ?? existing.description,
        audioUrl: item.audioUrl || existing.audioUrl,
        artworkUrl: item.artworkUrl || existing.artworkUrl,
        publishedAt: item.publishedAt || existing.publishedAt,
        durationSeconds: item.durationSeconds || existing.durationSeconds,
        transcriptUrl: item.transcriptUrl ?? existing.transcriptUrl,
        transcriptType: item.transcriptType ?? existing.transcriptType,
        chaptersUrl: item.chaptersUrl ?? existing.chaptersUrl,
        feedOrder: idx,
        podcastTitle: p.title,
      });
    } else {
      lib.episodes[item.id] = newEpisode({ ...item, podcastId, podcastTitle: p.title, feedOrder: idx });
      added++;
    }
  });

  for (const [epId, s] of Object.entries(lib.pending)) {
    if (s.podcastId !== podcastId) continue;
    const e = lib.episodes[epId];
    if (e) {
      Object.assign(e, {
        playbackPositionSeconds: s.pos,
        isCompleted: s.completed,
        completedAt: s.completedAt,
        isDismissed: s.dismissed,
        dismissReason: s.dismissReason,
        skippedAt: s.skippedAt,
        skipCount: s.skipCount,
        updatedAt: s.updatedAt,
      });
    }
    delete lib.pending[epId];
  }
  return { added };
}

export function syncableShows(lib) {
  return Object.values(lib.podcasts).filter((p) => !isLocalFeed(p.id));
}
