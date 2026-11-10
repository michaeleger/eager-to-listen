// One sync pass, same steps as android/.../data/sync/SyncManager.performSync:
// read every sync copy (main file + Drive conflict copies), merge each, write one consolidated
// main file safely, then delete the conflict copies.

import { parse, merge, serialize, isSyncFileName, SYNC_FILE } from './sync.js';
import { rebuildQueue } from './library.js';
import * as FP from './syncFingerprint.js';

/**
 * @param {object} lib the library (changed in place)
 * @param {object} io  { list(target), read(target, name), write(target, json), remove(target, name) }
 * @param {object} [opts]
 * @param {(ids: string[]) => Promise<void>} [opts.onNewShows] called before writing when the phone
 *   added shows this PC doesn't have; it should load their feeds. The same files are then merged
 *   once more, so pins and progress for those episodes (skipped the first time, because the
 *   episodes didn't exist here yet) land now instead of being dropped from the file we write.
 * @param {boolean} [opts.force] the Sync button: always read, merge and write
 * @returns {Promise<{podcasts:number, episodes:number, newlySubscribed:string[], files:number, message:string,
 *   plan:'FULL'|'WRITE_ONLY'|'NOTHING_TO_DO', wrote:boolean, archiveNeeded:boolean, bookmarksFp:string}>}
 *
 * When io.stat exists (name, size and modified time of the folder's files, without opening them),
 * the pass first checks whether anything changed on either side since the last one and does only
 * the work that's needed: see syncFingerprint.js.
 */
export async function runSync(lib, io, { now = Date.now(), onNewShows = null, force = false } = {}) {
  const target = lib.settings.syncFolder;
  if (!target) throw new Error('No sync folder set. Add one in Settings.');

  // What we remembered after the last sync, if it was for this same folder
  const saved = lib.syncFp && lib.syncFp.target === target ? lib.syncFp : null;
  const held = Object.keys(lib.removedUnarchived || {}).length;
  const stats = io.stat ? await io.stat(target) : null;
  const allNames = stats ? stats.map((f) => f.name) : await io.list(target);
  const folderNow = stats ? FP.ofFolder(stats) : null;
  const lastFullWriteAt = saved?.fullWriteAt || 0;
  const periodicDue = now - lastFullWriteAt > FP.FULL_SYNC_EVERY_MS || now < lastFullWriteAt;
  const before = serialize(lib, lib.deviceId, now);
  const plan = stats
    ? FP.plan({
        force,
        folderNow,
        folderAtLastSync: saved?.folder ?? null,
        localNow: FP.ofLocalState(before),
        localAtLastSync: saved?.local ?? null,
        hasMainFile: allNames.includes(SYNC_FILE),
        lastFullSyncAt: lastFullWriteAt,
        now,
      })
    : 'FULL';
  if (plan === 'NOTHING_TO_DO') {
    return { podcasts: 0, episodes: 0, newlySubscribed: [], files: 0, message: 'Already up to date', plan, wrote: false, archiveNeeded: saved.bookmarks == null, bookmarksFp: FP.ofBookmarks(before, held) };
  }

  // When the folder hasn't changed since our last sync there is nothing new in it to read
  const names = plan === 'WRITE_ONLY' ? [] : allNames.filter(isSyncFileName);
  let mainFileRead = false;
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
      if (name === SYNC_FILE) mainFileRead = true;
    } catch (e) {
      // The main file exists but couldn't be read (half uploaded, connection dropped, damaged).
      // Stop: writing now would replace it, and its backup, with this computer's state only.
      if (name === SYNC_FILE) {
        throw new Error(`The sync file couldn't be read, so nothing was changed. Try again in a few minutes. (${e.message || e})`);
      }
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
  const exported = serialize(lib, lib.deviceId, now);
  const localAfterMerge = FP.ofLocalState(exported);
  // Nothing new here for the folder? Then the file there already covers this computer.
  const skipWrite =
    plan === 'FULL' &&
    stats != null &&
    FP.canSkipWriteAfterMerge({ mustWrite: force || periodicDue, mainFileRead, localAfterMerge, localAtLastSync: saved?.local ?? null });
  if (!skipWrite) await io.write(target, JSON.stringify(exported, null, 2));

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
  const bookmarksFp = FP.ofBookmarks(exported, held);
  // The archive pass (run by the caller) is skipped when only listening progress changed
  const archiveNeeded = !(plan === 'WRITE_ONLY' && saved?.bookmarks === bookmarksFp);
  if (stats) {
    lib.syncFp = {
      target,
      folder: FP.ofFolder(await io.stat(target)),
      local: localAfterMerge,
      // Set by the caller once the archive pass has succeeded (noteArchiveDone)
      bookmarks: archiveNeeded ? null : bookmarksFp,
      fullWriteAt: plan === 'FULL' && !skipWrite ? now : lastFullWriteAt,
    };
  }
  const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
  const message = `Synced ${plural(podcasts, 'show')}, ${plural(episodes, 'episode')}${problems.length ? ` (skipped: ${problems.join('; ')})` : ''}`;
  return { podcasts, episodes, newlySubscribed: fresh, files: names.length, message, plan, wrote: !skipWrite, archiveNeeded, bookmarksFp };
}

/**
 * Call after the bookmark archive pass that follows a sync succeeds: the archive file just
 * changed, so take the folder's fingerprint again, and remember the bookmarks are saved.
 */
export async function noteArchiveDone(lib, io, bookmarksFp) {
  if (!io.stat || !lib.syncFp || lib.syncFp.target !== lib.settings.syncFolder) return;
  lib.syncFp.folder = FP.ofFolder(await io.stat(lib.settings.syncFolder));
  lib.syncFp.bookmarks = bookmarksFp;
}
