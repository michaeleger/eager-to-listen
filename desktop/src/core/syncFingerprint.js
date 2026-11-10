// "Has anything changed since the last sync?" Mirrors android/.../data/sync/SyncFingerprint.kt;
// change both together.
//
// A sync used to download, merge and re-upload the whole file every time. Now each side is summed
// up in a short fingerprint:
//  - the folder: name, size and modified time of every sync and bookmark-archive file in it
//  - this device: a hash of what it would write (minus the time stamp)
// If the folder's fingerprint matches the one saved after the last sync, nothing there needs
// reading. If this device's matches too, nothing needs writing either.

import { isSyncFileName } from './sync.js';

/** Even with matching fingerprints, do a full pass (and write) this often, as a safety net. */
export const FULL_SYNC_EVERY_MS = 6 * 60 * 60 * 1000;

const isArchiveFileName = (name) =>
  typeof name === 'string' && name.startsWith('eagertolisten_bookmarks') && name.endsWith('.jsonl') && !name.endsWith('.tmp.jsonl');

/** The files that make up the shared state: sync file, its conflict copies, the bookmark archive. */
export const isSharedFile = (name) => isSyncFileName(name) || isArchiveFileName(name);

/** @param {{name:string,size:number,modTime:string|number}[]} files */
export function ofFolder(files) {
  return files
    .filter((f) => isSharedFile(f.name))
    .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
    .map((f) => `${f.name}:${f.size}:${f.modTime}`)
    .join('|');
}

// cyrb53: a small, fast string hash. This only has to notice change, not resist tampering.
function hash(str) {
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return `${(4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(16)}:${str.length}`;
}

/** Hash of what this device would write, ignoring when it was written. */
export function ofLocalState(exported) {
  const { writtenAt, ...rest } = exported;
  return hash(JSON.stringify(rest));
}

/** Hash of the bookmark part of the export, plus how many removed bookmarks still wait for the archive. */
export function ofBookmarks(exported, heldCount = 0) {
  return hash(`${JSON.stringify(exported.inspirations ?? [])}|${JSON.stringify(exported.inspirationsRemoved ?? [])}|${heldCount}`);
}

/**
 * NOTHING_TO_DO: neither side changed. WRITE_ONLY: only this device changed, so write without
 * reading. FULL: read and merge everything.
 */
export function plan({ force, folderNow, folderAtLastSync, localNow, localAtLastSync, hasMainFile, lastFullSyncAt, now }) {
  if (force || folderAtLastSync == null || localAtLastSync == null) return 'FULL';
  if (now - lastFullSyncAt > FULL_SYNC_EVERY_MS || now < lastFullSyncAt) return 'FULL';
  if (folderNow !== folderAtLastSync || !hasMainFile) return 'FULL';
  if (localNow !== localAtLastSync) return 'WRITE_ONLY';
  return 'NOTHING_TO_DO';
}

/**
 * After a full pass: the file only needs writing again if this device now holds something the
 * folder doesn't. If the merge left this device exactly as it was at its last write, the other
 * device's file already includes everything from here. Skipping that write also stops two devices
 * from re-triggering each other forever.
 */
export function canSkipWriteAfterMerge({ mustWrite, mainFileRead, localAfterMerge, localAtLastSync }) {
  return !mustWrite && mainFileRead && localAtLastSync != null && localAfterMerge === localAtLastSync;
}
