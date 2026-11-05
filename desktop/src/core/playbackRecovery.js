// Playback error recovery, ported from the Android 0.9.3 work (PlaybackCoordinator.classifyError and
// retryDelayMs, PlaybackService.computeSafeStartPositionMs, CrashReporter.redactUrls) with the review
// follow-ups: no signal waits instead of skipping, and the skip list never loops through the queue.
//
// The desktop plays through an <audio> element, so errors arrive as HTML MediaError codes rather
// than ExoPlayer codes. There are no audio-device errors to tell apart here.

export const MEDIA_ERR = { ABORTED: 1, NETWORK: 2, DECODE: 3, SRC_NOT_SUPPORTED: 4 };

export const MAX_NETWORK_RETRIES = 2; // online but the source fails: 2 quick retries, then the next episode
export const MAX_OFFLINE_RETRIES = 20; // no network: ~8.5 minutes of backoff rides out a dropped connection
export const MAX_LOG_ENTRIES = 25;

/**
 * What to do about a playback error.
 * - IGNORE: the load was cancelled (we changed episodes); nothing is wrong
 * - STREAM_INSTEAD: the downloaded file failed; delete it and stream from the saved spot
 * - RETRY_NETWORK / RETRY_OFFLINE: try the same source again after retryDelayMs
 * - CHECK_FEED: the source looks gone (bad link, unsupported); refresh the feed in case the link moved
 * - SKIP: give up on it for this session and play the next episode
 */
export function classifyError({ code, retryCount = 0, online = true, local = false, localFallbackTried = false, feedChecked = false }) {
  if (code === MEDIA_ERR.ABORTED) return 'IGNORE';
  if (local && !localFallbackTried) return 'STREAM_INSTEAD';
  if (!online) return retryCount < MAX_OFFLINE_RETRIES ? 'RETRY_OFFLINE' : 'SKIP';
  if (code === MEDIA_ERR.DECODE || code === MEDIA_ERR.SRC_NOT_SUPPORTED) {
    // WebKit also reports a server hiccup as "not supported", so retry once before checking the feed
    if (retryCount < 1) return 'RETRY_NETWORK';
    return feedChecked ? 'SKIP' : 'CHECK_FEED';
  }
  return retryCount < MAX_NETWORK_RETRIES ? 'RETRY_NETWORK' : 'SKIP';
}

/** Wait before retry number `retryCount` (1-based). */
export function retryDelayMs(resolution, retryCount) {
  const attempt = Math.max(1, retryCount);
  if (resolution === 'RETRY_NETWORK') return Math.min(attempt * 1500, 3000);
  if (resolution === 'RETRY_OFFLINE') return Math.min(1000 * 2 ** Math.min(attempt, 5), 30_000);
  return 0;
}

/**
 * Where to start an episode. Never at (or a hair before) the very end: that ends the episode at
 * once or fails to load. In that case start from the intro skip, or the top.
 */
export function safeStartPosition({ resumeAt = 0, durationSec = 0, skipIntroSec = 0 }) {
  const intro = Math.max(0, skipIntroSec || 0);
  const start = resumeAt > 0 ? resumeAt : intro;
  if (durationSec > 0 && start >= durationSec - 5) return intro > 0 && intro < durationSec - 5 ? intro : 0;
  return start;
}

/**
 * The next episode to play after one failed: the first queued episode not excluded and not
 * skipped this session. When every one has been skipped this returns null (stop), rather than
 * clearing the skips and cycling through a queue that can't play.
 */
export function pickNextPlayable(queued, { exclude = [], skipped = new Set() } = {}) {
  const ex = new Set(exclude);
  return queued.find((e) => !ex.has(e.id) && !skipped.has(e.id)) || null;
}

/** Private and premium feeds put the listener's key in the link. Keep scheme, host and path only. */
export function redactUrls(text) {
  return String(text ?? '')
    .replace(/(\w+:\/\/)[^/@\s]+@/g, '$1')
    .replace(/((?:https?|file|asset):\/\/[^\s?#]*)[?#][^\s)\]]*/g, '$1?[removed]');
}

/** Add an entry to the local playback log (newest first, capped). Links are redacted on the way in. */
export function logPlaybackProblem(log, entry, now = Date.now()) {
  const clean = { at: now, ...entry };
  for (const k of ['url', 'feed', 'detail']) if (clean[k]) clean[k] = redactUrls(clean[k]);
  return [clean, ...(Array.isArray(log) ? log : [])].slice(0, MAX_LOG_ENTRIES);
}

/** The log as plain text, for copying into an email or a bug report. */
export function formatPlaybackLog(log, header = '') {
  const lines = header ? [header, ''] : [];
  if (!log?.length) return [...lines, 'No playback problems recorded.'].join('\n');
  for (const e of log) {
    lines.push(`=== ${new Date(e.at).toISOString()} · ${e.event}`);
    if (e.episode) lines.push(`Episode: ${e.episode}`);
    if (e.show) lines.push(`Show: ${e.show}`);
    if (e.url) lines.push(`Source: ${e.url}`);
    if (e.code) lines.push(`Error: ${e.code}${e.detail ? ` (${e.detail})` : ''}`);
    if (e.position != null) lines.push(`Position: ${e.position}s`);
    if (e.action) lines.push(`What the app did: ${e.action}`);
    lines.push('');
  }
  return lines.join('\n').trimEnd() + '\n';
}
