// Phone <-> PC sync. Reads and writes the same eagertolisten_sync.json (format 2) the Android
// app keeps in your Drive folder. Port of android/.../data/sync/SyncSerializer.kt: parse, serialize
// and merge must stay rule-for-rule identical, or the two devices will argue on every sync.

import { normalize, isLocalFeed } from './feedUrl.js';
import { newPodcast, restoreExpiredNews, episodesForSync, pinnedItems } from './library.js';

export const CURRENT_FORMAT = 2;
export const SYNC_FILE = 'eagertolisten_sync.json';
const UNPIN_MAX_AGE_MS = 90 * 24 * 60 * 60 * 1000;

/** Files Android treats as sync copies: the main file plus Drive conflict copies like "(1)". */
export function isSyncFileName(name) {
  return (
    typeof name === 'string' &&
    name.startsWith('eagertolisten_sync') &&
    name.endsWith('.json') &&
    !name.endsWith('.tmp.json') &&
    !name.endsWith('.bak.json')
  );
}

// ---------- helpers that act like org.json's opt* ----------
const has = (o, k) => o != null && Object.prototype.hasOwnProperty.call(o, k);
const optNullable = (o, k) => (has(o, k) && o[k] !== null ? o[k] : null);
function optInt(o, k, d) {
  const v = o?.[k];
  if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Math.trunc(Number(v));
  return d;
}
const optLong = optInt;
function optNum(o, k, d) {
  const v = o?.[k];
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v);
  return d;
}
function optBool(o, k, d) {
  const v = o?.[k];
  if (typeof v === 'boolean') return v;
  if (typeof v === 'string') {
    if (v.toLowerCase() === 'true') return true;
    if (v.toLowerCase() === 'false') return false;
  }
  return d;
}
function optStr(o, k, d) {
  if (!has(o, k) || o[k] === null) return d;
  return String(o[k]);
}

// ---------- tombstones for removed pins (UnpinRules) ----------
export const pinWins = (pinUpdatedAt, removedAt) => removedAt == null || pinUpdatedAt > removedAt;
export const removalWins = (pinAddedAt, removedAt) => removedAt != null && removedAt >= pinAddedAt;

export function mergeUnpins(a, b) {
  const out = { ...a };
  for (const [id, at] of Object.entries(b)) if (out[id] == null || at > out[id]) out[id] = at;
  return out;
}

export function pruneUnpins(unpins, now, maxAgeMs = UNPIN_MAX_AGE_MS) {
  return Object.fromEntries(Object.entries(unpins).filter(([, at]) => now - at <= maxAgeMs));
}

function unpinsToJson(unpins) {
  return Object.keys(unpins)
    .sort()
    .map((id) => ({ episodeId: id, removedAt: unpins[id] }));
}

function unpinsFromJson(arr) {
  const out = {};
  if (!Array.isArray(arr)) return out;
  for (const o of arr) {
    if (!o || typeof o !== 'object') continue;
    const id = optStr(o, 'episodeId', '');
    const at = optLong(o, 'removedAt', 0);
    if (!id.trim() || at <= 0) continue;
    if (out[id] == null || at > out[id]) out[id] = at;
  }
  return out;
}

// ---------- serialize ----------

export function serialize(lib, deviceId, writtenAt = Date.now()) {
  const podcasts = Object.values(lib.podcasts).filter((p) => !isLocalFeed(p.id));
  const episodes = episodesForSync(lib).filter((e) => !isLocalFeed(e.podcastId));
  const manual = pinnedItems(lib).filter((q) => !isLocalFeed(q.podcastId));
  const inspirations = Object.values(lib.inspirations).filter((i) => !isLocalFeed(i.podcastId));
  const unpins = Object.fromEntries(
    Object.entries(pruneUnpins(lib.unpins || {}, writtenAt)).filter(([id]) => !isLocalFeed(id.split('::')[0])),
  );

  return {
    format: CURRENT_FORMAT,
    deviceId,
    writtenAt,
    podcasts: podcasts.map((p) => ({
      id: p.id,
      feedUrl: p.feedUrl,
      title: p.title,
      artworkUrl: p.artworkUrl,
      author: p.author,
      description: p.description,
      settingsUpdatedAt: p.settingsUpdatedAt,
      subscribedAt: p.subscribedAt,
      unsubscribedAt: p.unsubscribedAt ?? null,
      rules: {
        priority: p.priority,
        playOrderStrategy: p.playOrderStrategy,
        skipIntroSeconds: p.skipIntroSeconds,
        skipOutroSeconds: p.skipOutroSeconds,
        playbackSpeed: p.playbackSpeed,
        autoAddToQueue: p.autoAddToQueue,
        maxEpisodesInQueue: p.maxEpisodesInQueue,
        consecutivePlayLimit: p.consecutivePlayLimit,
        newsRetentionDays: p.newsRetentionDays,
        category: p.category,
        customDonationUrl: p.customDonationUrl ?? null,
      },
    })),
    episodes: episodes.map((e) => {
      const o = {
        id: e.id,
        pos: e.playbackPositionSeconds,
        completed: e.isCompleted,
        completedAt: e.completedAt ?? null,
        dismissed: e.isDismissed,
        dismissReason: e.dismissReason ?? null,
        skippedAt: e.skippedAt ?? null,
        skipCount: e.skipCount,
        updatedAt: e.updatedAt,
      };
      if (!e.isCompleted && e.playbackPositionSeconds === 0 && e.updatedAt > 0) o.unplayedExplicit = true;
      return o;
    }),
    manualQueue: manual.map((q) => ({ episodeId: q.episodeId, manualIndex: q.manualIndex ?? 0, updatedAt: q.addedAt })),
    manualQueueRemoved: unpinsToJson(unpins),
    inspirations: inspirations.map((i) => ({
      id: i.id,
      episodeId: i.episodeId,
      podcastId: i.podcastId,
      podcastTitle: i.podcastTitle,
      episodeTitle: i.episodeTitle,
      timestampSeconds: i.timestampSeconds,
      audioUrl: i.audioUrl,
      note: i.note,
      quoteText: i.quoteText,
      tags: i.tags,
      createdAt: i.createdAt,
    })),
  };
}

// ---------- parse ----------

function parseRules(o) {
  return {
    priority: optInt(o, 'priority', 3),
    playOrderStrategy: optStr(o, 'playOrderStrategy', 'EVERGREEN'),
    skipIntroSeconds: optInt(o, 'skipIntroSeconds', 0),
    skipOutroSeconds: optInt(o, 'skipOutroSeconds', 0),
    // Android writes Float.toDouble(), so 1.1 arrives as 1.100000023841858; keep it at 1.1
    playbackSpeed: Math.round(optNum(o, 'playbackSpeed', 1.0) * 100) / 100,
    autoAddToQueue: optBool(o, 'autoAddToQueue', true),
    maxEpisodesInQueue: optInt(o, 'maxEpisodesInQueue', 5),
    consecutivePlayLimit: optInt(o, 'consecutivePlayLimit', -1),
    newsRetentionDays: optInt(o, 'newsRetentionDays', 3),
    category: optStr(o, 'category', 'General'),
    customDonationUrl: optNullable(o, 'customDonationUrl') != null ? optStr(o, 'customDonationUrl', null) : null,
  };
}

export function parse(json, now = Date.now()) {
  const j = typeof json === 'string' ? JSON.parse(json) : json;
  const format = optInt(j, 'format', optInt(j, 'version', 1));
  const deviceId = optStr(j, 'deviceId', 'remote');
  const writtenAt = optLong(j, 'writtenAt', optLong(j, 'timestamp', now));

  const podcasts = (Array.isArray(j.podcasts) ? j.podcasts : []).map((o) => {
    if (!has(o, 'id')) throw new Error('podcast without id');
    const id = String(o.id);
    const rulesObj = o.rules && typeof o.rules === 'object' ? o.rules : null;
    return {
      id,
      feedUrl: optStr(o, 'feedUrl', id),
      title: optStr(o, 'title', 'Podcast'),
      artworkUrl: optStr(o, 'artworkUrl', ''),
      author: optStr(o, 'author', ''),
      description: optStr(o, 'description', ''),
      settingsUpdatedAt: optLong(o, 'settingsUpdatedAt', 0),
      subscribedAt: optLong(o, 'subscribedAt', 0),
      unsubscribedAt: optNullable(o, 'unsubscribedAt') != null ? optLong(o, 'unsubscribedAt', 0) : null,
      rules: parseRules(rulesObj ?? o),
    };
  });

  const episodes = (Array.isArray(j.episodes) ? j.episodes : []).map((o) => {
    if (!has(o, 'id')) throw new Error('episode without id');
    return {
      id: String(o.id),
      pos: optLong(o, 'pos', optLong(o, 'playbackPositionSeconds', 0)),
      completed: optBool(o, 'completed', optBool(o, 'isCompleted', false)),
      completedAt: optNullable(o, 'completedAt') != null ? optLong(o, 'completedAt', 0) : null,
      dismissed: optBool(o, 'dismissed', optBool(o, 'isDismissed', false)),
      dismissReason: optNullable(o, 'dismissReason') != null ? optStr(o, 'dismissReason', null) : null,
      skippedAt: optNullable(o, 'skippedAt') != null ? optLong(o, 'skippedAt', 0) : null,
      skipCount: optInt(o, 'skipCount', 0),
      updatedAt: optLong(o, 'updatedAt', 0),
      unplayedExplicit: optBool(o, 'unplayedExplicit', false),
    };
  });

  const manualQueue = [];
  (Array.isArray(j.manualQueue) ? j.manualQueue : []).forEach((item, i) => {
    if (item && typeof item === 'object') {
      if (!has(item, 'episodeId')) throw new Error('queue item without episodeId');
      manualQueue.push({ episodeId: String(item.episodeId), manualIndex: optInt(item, 'manualIndex', i), updatedAt: optLong(item, 'updatedAt', 0) });
    } else if (typeof item === 'string') {
      manualQueue.push({ episodeId: item, manualIndex: i, updatedAt: 0 });
    }
  });

  const inspirations = (Array.isArray(j.inspirations) ? j.inspirations : []).map((o) => {
    if (!has(o, 'id')) throw new Error('inspiration without id');
    return {
      id: String(o.id),
      episodeId: optStr(o, 'episodeId', ''),
      podcastId: optStr(o, 'podcastId', ''),
      podcastTitle: optStr(o, 'podcastTitle', ''),
      episodeTitle: optStr(o, 'episodeTitle', ''),
      timestampSeconds: optLong(o, 'timestampSeconds', 0),
      audioUrl: optStr(o, 'audioUrl', ''),
      note: optStr(o, 'note', ''),
      quoteText: optStr(o, 'quoteText', ''),
      tags: optStr(o, 'tags', ''),
      createdAt: optLong(o, 'createdAt', now),
    };
  });

  return {
    format,
    deviceId,
    writtenAt,
    podcasts,
    episodes,
    manualQueue,
    inspirations,
    manualQueueRemoved: unpinsFromJson(j.manualQueueRemoved),
  };
}

// ---------- merge ----------

function findPodcast(lib, id) {
  return lib.podcasts[id] ?? null;
}
function findPodcastByFeedUrl(lib, url) {
  return Object.values(lib.podcasts).find((p) => p.feedUrl === url) ?? null;
}

/**
 * Merge another device's state into the library. Returns a summary and the IDs of shows that are
 * new here, so the caller can fetch their feeds.
 */
export function merge(lib, remote) {
  let mergedPodcasts = 0;
  let mergedEpisodes = 0;
  let mergedQueue = 0;
  const newlySubscribed = [];

  // 1. Podcasts
  for (const rp of remote.podcasts) {
    const normalizedUrl = normalize(rp.feedUrl);
    const normalizedId = normalize(rp.id);
    // Older files predate subscribedAt; fall back to the previous proxy
    const remoteSubscribedAt = rp.subscribedAt > 0 ? rp.subscribedAt : rp.settingsUpdatedAt;
    const existing =
      findPodcast(lib, rp.id) ??
      (normalizedId && normalizedId !== rp.id ? findPodcast(lib, normalizedId) : null) ??
      (normalizedUrl ? findPodcastByFeedUrl(lib, normalizedUrl) : null);

    if (existing) {
      // Rules: last writer wins by settingsUpdatedAt
      if (rp.settingsUpdatedAt > existing.settingsUpdatedAt) {
        Object.assign(existing, {
          priority: rp.rules.priority,
          skipIntroSeconds: rp.rules.skipIntroSeconds,
          skipOutroSeconds: rp.rules.skipOutroSeconds,
          playbackSpeed: rp.rules.playbackSpeed,
          autoAddToQueue: rp.rules.autoAddToQueue,
          maxEpisodesInQueue: rp.rules.maxEpisodesInQueue,
          category: rp.rules.category,
          customDonationUrl: rp.rules.customDonationUrl,
          playOrderStrategy: rp.rules.playOrderStrategy,
          newsRetentionDays: rp.rules.newsRetentionDays,
          consecutivePlayLimit: rp.rules.consecutivePlayLimit,
          settingsUpdatedAt: rp.settingsUpdatedAt,
        });
        if (rp.rules.playOrderStrategy !== 'NEWS') restoreExpiredNews(lib, existing.id);
      }

      // Unsubscribe tombstone: the newer of (subscribe, unsubscribe) wins
      if (rp.unsubscribedAt != null) {
        if (existing.unsubscribedAt == null && !(existing.subscribedAt > rp.unsubscribedAt)) {
          existing.unsubscribedAt = rp.unsubscribedAt;
          lib.queue = lib.queue.filter((q) => q.podcastId !== existing.id);
        }
      } else if (existing.unsubscribedAt != null && remoteSubscribedAt > existing.unsubscribedAt) {
        existing.unsubscribedAt = null;
        existing.subscribedAt = remoteSubscribedAt;
      }
    } else {
      lib.podcasts[rp.id] = newPodcast({
        id: rp.id,
        title: rp.title,
        feedUrl: rp.feedUrl,
        artworkUrl: rp.artworkUrl,
        author: rp.author,
        description: rp.description,
        priority: rp.rules.priority,
        skipIntroSeconds: rp.rules.skipIntroSeconds,
        skipOutroSeconds: rp.rules.skipOutroSeconds,
        playbackSpeed: rp.rules.playbackSpeed,
        autoAddToQueue: rp.rules.autoAddToQueue,
        maxEpisodesInQueue: rp.rules.maxEpisodesInQueue,
        category: rp.rules.category,
        customDonationUrl: rp.rules.customDonationUrl,
        playOrderStrategy: rp.rules.playOrderStrategy,
        newsRetentionDays: rp.rules.newsRetentionDays,
        consecutivePlayLimit: rp.rules.consecutivePlayLimit,
        subscribedAt: remoteSubscribedAt,
        unsubscribedAt: rp.unsubscribedAt,
        settingsUpdatedAt: rp.settingsUpdatedAt,
      });
      if (rp.unsubscribedAt == null) {
        mergedPodcasts++;
        newlySubscribed.push(rp.id);
      }
    }
  }

  // 2. Episodes
  for (const re of remote.episodes) {
    const local = lib.episodes[re.id];
    if (!local) {
      lib.pending[re.id] = {
        podcastId: re.id.includes('::') ? re.id.slice(0, re.id.indexOf('::')) : '',
        pos: re.pos,
        completed: re.completed,
        completedAt: re.completedAt,
        dismissed: re.dismissed,
        dismissReason: re.dismissReason,
        skippedAt: re.skippedAt,
        skipCount: re.skipCount,
        updatedAt: re.updatedAt,
      };
      continue;
    }

    let newCompleted = local.isCompleted;
    let newCompletedAt = local.completedAt;
    let newPos = local.playbackPositionSeconds;
    let newDismissed = local.isDismissed;
    let newDismissReason = local.dismissReason;
    let newSkippedAt = local.skippedAt;
    let newSkipCount = local.skipCount;
    const newUpdatedAt = Math.max(local.updatedAt, re.updatedAt);

    // Completion & explicit unplayed
    if (local.isCompleted && !re.completed) {
      const localCompTime = local.completedAt ?? local.updatedAt;
      if (re.unplayedExplicit && re.updatedAt > localCompTime) {
        newCompleted = false;
        newCompletedAt = null;
        newPos = 0;
        newDismissed = false;
        newDismissReason = 'UNPLAYED';
        newSkippedAt = null;
        newSkipCount = 0;
      } else {
        newCompleted = true;
      }
    } else if (!local.isCompleted && re.completed) {
      newCompleted = true;
      newCompletedAt = re.completedAt ?? re.updatedAt;
      newPos = local.durationSeconds > 0 ? local.durationSeconds : Math.max(local.playbackPositionSeconds, re.pos);
    } else if (local.isCompleted && re.completed) {
      newCompleted = true;
      const m = Math.max(local.completedAt ?? 0, re.completedAt ?? 0);
      newCompletedAt = m > 0 ? m : null;
    }

    // Position (if not completed)
    if (!newCompleted && re.updatedAt > local.updatedAt) newPos = re.pos;

    // Dismissed: newer side wins
    if (re.dismissed !== local.isDismissed) {
      if (re.updatedAt > local.updatedAt) {
        newDismissed = re.dismissed;
        newDismissReason = re.dismissReason;
      }
    } else if (re.dismissed && re.updatedAt > local.updatedAt) {
      newDismissReason = re.dismissReason ?? local.dismissReason;
    }

    // Skips
    if (!(newCompleted || (re.unplayedExplicit && re.updatedAt > (local.completedAt ?? local.updatedAt)))) {
      newSkipCount = Math.max(local.skipCount, re.skipCount);
      const m = Math.max(local.skippedAt ?? 0, re.skippedAt ?? 0);
      newSkippedAt = m > 0 ? m : null;
    }

    const changed =
      newCompleted !== local.isCompleted ||
      newCompletedAt !== local.completedAt ||
      newPos !== local.playbackPositionSeconds ||
      newDismissed !== local.isDismissed ||
      newDismissReason !== local.dismissReason ||
      newSkippedAt !== local.skippedAt ||
      newSkipCount !== local.skipCount;

    if (changed) {
      Object.assign(local, {
        playbackPositionSeconds: newPos,
        isCompleted: newCompleted,
        completedAt: newCompletedAt,
        isDismissed: newDismissed,
        dismissReason: newDismissReason,
        skippedAt: newSkippedAt,
        skipCount: newSkipCount,
        updatedAt: newUpdatedAt,
      });
      mergedEpisodes++;
    }
  }

  // 3a. Pins removed by hand on the other device come out here too, unless re-pinned since (R2.7)
  const removed = remote.manualQueueRemoved || {};
  const unpins = mergeUnpins(lib.unpins || {}, removed);
  for (const pin of pinnedItems(lib)) {
    if (removalWins(pin.addedAt, removed[pin.episodeId])) {
      lib.queue = lib.queue.filter((q) => q.episodeId !== pin.episodeId);
      mergedQueue++;
    }
  }
  if (Object.keys(removed).length > 0) lib.unpins = unpins;

  // 3b. Remote pins, skipping any removed by hand after they were pinned
  for (const rmq of remote.manualQueue) {
    if (!pinWins(rmq.updatedAt, unpins[rmq.episodeId])) continue;
    const ep = lib.episodes[rmq.episodeId];
    if (!ep || ep.isCompleted || ep.isDismissed) continue;
    const existing = lib.queue.find((q) => q.episodeId === rmq.episodeId);
    if (!existing) {
      lib.queue.push({
        episodeId: rmq.episodeId,
        podcastId: ep.podcastId,
        orderIndex: rmq.manualIndex,
        addedAt: rmq.updatedAt,
        isManual: true,
        manualIndex: rmq.manualIndex,
      });
      mergedQueue++;
    } else if (rmq.updatedAt > existing.addedAt) {
      Object.assign(existing, { isManual: true, manualIndex: rmq.manualIndex, orderIndex: rmq.manualIndex, addedAt: rmq.updatedAt });
    }
  }

  // 4. Inspirations (bookmarks): upsert
  for (const clip of remote.inspirations) lib.inspirations[clip.id] = clip;

  return {
    podcastsSynced: mergedPodcasts,
    episodesSynced: mergedEpisodes,
    queueChanges: mergedQueue,
    newlySubscribed,
    queueCount: lib.queue.length,
  };
}
