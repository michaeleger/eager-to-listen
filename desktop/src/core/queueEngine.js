// Fair-interleaving queue engine. Line-for-line port of android/.../domain/QueueEngine.kt so the
// PC and the phone build the same queue from the same library. Change both together.

export const Strategy = Object.freeze({
  CHRONOLOGICAL: 'CHRONOLOGICAL',
  REVERSE_CHRONOLOGICAL: 'REVERSE_CHRONOLOGICAL',
  NEWS: 'NEWS',
  EVERGREEN: 'EVERGREEN',
});

export const STRATEGY_LABELS = Object.freeze({
  CHRONOLOGICAL: ['Chronological', 'Serial stories & courses (oldest unplayed first)'],
  REVERSE_CHRONOLOGICAL: ['Reverse Chronological', 'Always the newest unplayed episode'],
  NEWS: ['News', 'Updates first, last X unplayed days in chronological order'],
  EVERGREEN: ['Evergreen / Knowledge', 'Newest unplayed episode first, then next newest unplayed'],
});

export function parseStrategy(s) {
  return Object.prototype.hasOwnProperty.call(Strategy, s) ? s : Strategy.EVERGREEN;
}

const DAY_MS = 86_400_000;

// Kotlin compareBy chains; strings compare by UTF-16 code units like Kotlin's String.compareTo
function cmp(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}
function chain(...keys) {
  return (x, y) => {
    for (const [fn, dir] of keys) {
      const c = cmp(fn(x), fn(y)) * dir;
      if (c !== 0) return c;
    }
    return 0;
  };
}
const asc = (fn) => [fn, 1];
const desc = (fn) => [fn, -1];

function coerceIn(v, lo, hi) {
  return Math.min(Math.max(v, lo), hi);
}

/**
 * @param {object} p
 * @param {Array<{id,title,priority,strategy,newsRetentionDays,consecutiveLimit,maxInQueue,autoAdd,lastTurnAt}>} p.feeds
 * @param {Array<{id,feedId,publishedAt,feedOrder,isCompleted,isDismissed,skippedAt,skipCount}>} p.episodes
 * @param {Array<{episodeId,manualIndex}>} p.pinned
 * @returns {string[]} episode IDs in play order
 */
export function buildQueue({ feeds, episodes, pinned, nowPlayingId, now, globalLimit, defaultNewsDays }) {
  // 1. Completed or dismissed episodes never appear (Invariant I1)
  const eligibleById = new Map();
  for (const e of episodes) if (!e.isCompleted && !e.isDismissed) eligibleById.set(e.id, e);

  const placed = new Set();
  const result = [];

  // 2. Now playing stays #1 if eligible
  let nowPlayingFeedId = null;
  if (nowPlayingId != null) {
    const ep = eligibleById.get(nowPlayingId);
    if (ep) {
      placed.add(ep.id);
      result.push(ep.id);
      nowPlayingFeedId = ep.feedId;
    }
  }

  // 3. Pinned items by manualIndex (kept even for feeds with autoAdd off)
  const sortedPinned = [...pinned].sort(chain(asc((p) => p.manualIndex)));
  for (const pin of sortedPinned) {
    const ep = eligibleById.get(pin.episodeId);
    if (ep && !placed.has(ep.id)) {
      placed.add(ep.id);
      result.push(ep.id);
    }
  }

  // 4. Per-feed candidates
  const feedQueues = new Map();
  const feedLimits = new Map();
  const feedTiers = new Map();
  const feedsById = new Map(feeds.map((f) => [f.id, f]));

  for (const feed of feeds) {
    if (!feed.autoAdd) continue;
    const unplaced = episodes.filter(
      (e) => e.feedId === feed.id && !e.isCompleted && !e.isDismissed && !placed.has(e.id),
    );
    if (unplaced.length === 0) continue;

    const nonSkipped = unplaced.filter((e) => e.skippedAt == null);
    const skipped = unplaced.filter((e) => e.skippedAt != null);

    let nonSkippedOrdered;
    switch (parseStrategy(feed.strategy)) {
      case Strategy.REVERSE_CHRONOLOGICAL:
      case Strategy.EVERGREEN:
        // Newest unplayed first, then next newest; the back catalog never restarts at episode 1
        nonSkippedOrdered = [...nonSkipped].sort(chain(desc((e) => e.publishedAt), asc((e) => e.feedOrder)));
        break;
      case Strategy.CHRONOLOGICAL:
        nonSkippedOrdered = [...nonSkipped].sort(chain(asc((e) => e.publishedAt), desc((e) => e.feedOrder)));
        break;
      case Strategy.NEWS: {
        const days = feed.newsRetentionDays > 0 ? feed.newsRetentionDays : defaultNewsDays;
        const cutoff = now - days * DAY_MS;
        nonSkippedOrdered = nonSkipped
          .filter((e) => e.publishedAt > 0 && e.publishedAt >= cutoff)
          .sort(chain(asc((e) => e.publishedAt), asc((e) => e.feedOrder)));
        break;
      }
    }

    // Skipped episodes go to the end of the feed's rotation
    const skippedOrdered = [...skipped].sort(
      chain(asc((e) => e.skipCount), asc((e) => e.skippedAt ?? 0), asc((e) => e.feedOrder)),
    );

    const full = [...nonSkippedOrdered, ...skippedOrdered];
    if (full.length === 0) continue;

    // Pinned items don't count toward maxInQueue
    const capped = feed.maxInQueue > 0 ? full.slice(0, feed.maxInQueue) : full;
    if (capped.length > 0) {
      feedQueues.set(feed.id, capped);
      feedLimits.set(feed.id, feed.consecutiveLimit > 0 ? feed.consecutiveLimit : globalLimit);
      // Tier 0: NEWS feeds with fresh non-skipped items
      feedTiers.set(
        feed.id,
        parseStrategy(feed.strategy) === Strategy.NEWS && nonSkippedOrdered.length > 0
          ? 0
          : coerceIn(feed.priority, 1, 5),
      );
    }
  }

  // 5. Round-robin by tier, up to each feed's limit per turn
  let firstPass = true;
  const remaining = () => [...feedQueues.values()].some((q) => q.length > 0);
  while (remaining()) {
    const active = [...feedQueues.entries()]
      .filter(([, q]) => q.length > 0)
      .map(([id]) => id)
      .sort(
        chain(
          asc((id) => feedTiers.get(id) ?? 3),
          asc((id) => feedsById.get(id)?.lastTurnAt ?? 0),
          asc((id) => feedsById.get(id)?.title ?? ''),
          asc((id) => id),
        ),
      );

    let took = 0;
    for (const feedId of active) {
      const list = feedQueues.get(feedId);
      const limit = feedLimits.get(feedId) ?? globalLimit;
      const effective = firstPass && nowPlayingFeedId === feedId ? Math.max(limit - 1, 0) : limit;
      const n = Math.min(effective, list.length);
      for (let i = 0; i < n; i++) {
        const ep = list.shift();
        placed.add(ep.id);
        result.push(ep.id);
        took++;
      }
    }
    // Guard the desktop against a zero limit (Android would spin); not reachable with sane settings
    if (took === 0 && !firstPass) break;
    firstPass = false;
  }

  return result;
}
