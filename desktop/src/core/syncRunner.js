// One sync pass, same steps as android/.../data/sync/SyncManager.performSync:
// read every sync copy (main file + Drive conflict copies), merge each, write one consolidated
// main file safely, then delete the conflict copies.

import { parse, merge, serialize, isSyncFileName, SYNC_FILE } from './sync.js';
import { rebuildQueue } from './library.js';

/**
 * @param {object} lib the library (changed in place)
 * @param {object} io  { list(target), read(target, name), write(target, json), remove(target, name) }
 * @param {object} [opts]
 * @param {(ids: string[]) => Promise<void>} [opts.onNewShows] called before writing when the phone
 *   added shows this PC doesn't have; it should load their feeds. The same files are then merged
 *   once more, so pins and progress for those episodes (skipped the first time, because the
 *   episodes didn't exist here yet) land now instead of being dropped from the file we write.
 * @returns {Promise<{podcasts:number, episodes:number, newlySubscribed:string[], files:number, message:string}>}
 */
export async function runSync(lib, io, { now = Date.now(), onNewShows = null } = {}) {
  const target = lib.settings.syncFolder;
  if (!target) throw new Error('No sync folder set. Add one in Settings.');

  const names = (await io.list(target)).filter(isSyncFileName);
  let podcasts = 0;
  let episodes = 0;
  const newlySubscribed = [];
  const problems = [];
  const unreadable = new Set();
  const merged = [];

  for (const name of names) {
    try {
      const text = await io.read(target, name);
      if (text.trim()) {
        const remote = parse(text, now);
        if (remote.deviceId !== lib.deviceId || remote.writtenAt > (lib.lastSyncAt || 0)) {
          const s = merge(lib, remote);
          merged.push(remote);
          podcasts += s.podcastsSynced;
          episodes += s.episodesSynced;
          newlySubscribed.push(...s.newlySubscribed);
        }
      }
    } catch (e) {
      problems.push(`${name}: ${e.message || e}`);
      unreadable.add(name);
    }
  }

  const fresh = [...new Set(newlySubscribed)];
  if (fresh.length && onNewShows) {
    await onNewShows(fresh);
    for (const remote of merged) episodes += merge(lib, remote).episodesSynced;
  }

  rebuildQueue(lib, { now });
  await io.write(target, JSON.stringify(serialize(lib, lib.deviceId, now), null, 2));

  for (const name of names) {
    // A copy we couldn't read is left alone rather than deleted with its contents unmerged
    if (name !== SYNC_FILE && !unreadable.has(name)) {
      try {
        await io.remove(target, name);
      } catch {
        /* leave it; the next sync will merge it again harmlessly */
      }
    }
  }

  lib.lastSyncAt = now;
  const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
  const message = `Synced ${plural(podcasts, 'show')}, ${plural(episodes, 'episode')}${problems.length ? ` (skipped: ${problems.join('; ')})` : ''}`;
  return { podcasts, episodes, newlySubscribed: fresh, files: names.length, message };
}
