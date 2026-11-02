// Eager to Listen desktop: UI controller. Podcast rules live in core/ (shared with Android);
// this file wires them to the window, the audio element and the native commands.

import * as L from './core/library.js';
import { normalize, sameFeed } from './core/feedUrl.js';
import { parseFeed } from './core/feedParser.js';
import { runSync } from './core/syncRunner.js';
import { STRATEGY_LABELS } from './core/queueEngine.js';

// Show unexpected errors in the window instead of failing silently
function showFatal(msg) {
  const m = document.querySelector('#main');
  if (m) m.insertAdjacentHTML('afterbegin', `<div class="card" style="border:1px solid var(--danger)"><p><strong>Something went wrong.</strong> ${String(msg).replace(/</g, '&lt;')}</p></div>`);
}
window.addEventListener('error', (e) => showFatal(`${e.message} (${(e.filename || '').split('/').pop()}:${e.lineno})`));
window.addEventListener('unhandledrejection', (e) => showFatal(e.reason?.message || e.reason));

const T = window.__TAURI__;
const invoke = T.core.invoke;
const listen = T.event.listen;
const fileSrc = T.core.convertFileSrc;

const $ = (sel) => document.querySelector(sel);
const audio = $('#audio');
const main = $('#main');

const state = {
  lib: L.emptyLibrary(),
  view: 'queue',
  showId: null,
  nowPlaying: null, // episode id loaded in the player
  refreshing: false,
  syncing: false,
  syncMessage: '',
  syncError: false,
  downloads: {}, // episodeId -> {received,total}
  searchResults: null,
  rclone: null,
};

// ---------------------------------------------------------------- helpers

const esc = (s) =>
  String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const stripHtml = (s) => {
  const d = document.createElement('div');
  d.innerHTML = String(s ?? '').replace(/<(script|style)[\s\S]*?<\/\1>/gi, '');
  return d.textContent || '';
};
function fmtTime(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
}
function fmtDate(ms) {
  if (!ms) return '';
  const d = new Date(ms);
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: d.getFullYear() === new Date().getFullYear() ? undefined : 'numeric' });
}
function ago(ms) {
  if (!ms) return 'never';
  const m = Math.round((Date.now() - ms) / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m} min ago`;
  const h = Math.round(m / 60);
  return h < 24 ? `${h} hr ago` : `${Math.round(h / 24)} days ago`;
}
let toastTimer;
function toast(msg, ms = 3500) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}
const art = (url) => (url ? esc(url) : 'icon.png');

// ---------------------------------------------------------------- persistence

let saveTimer = null;
function persist() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(saveNow, 800);
}
async function saveNow() {
  clearTimeout(saveTimer);
  try {
    await invoke('save_library', { json: JSON.stringify(state.lib) });
  } catch (e) {
    toast(`Couldn't save your library: ${e}`);
  }
}

async function loadLibrary() {
  const text = await invoke('load_library');
  if (text) {
    const loaded = JSON.parse(text);
    const fresh = L.emptyLibrary();
    state.lib = { ...fresh, ...loaded, settings: { ...fresh.settings, ...(loaded.settings || {}) } };
  }
  if (!state.lib.deviceId) {
    state.lib.deviceId = `desktop-${crypto.randomUUID()}`;
    persist();
  }
}

// ---------------------------------------------------------------- feeds

async function fetchAndParse(feedUrl, podcastId, cache = {}) {
  const res = await invoke('fetch_text', { url: feedUrl, etag: cache.etag ?? null, lastModified: cache.lastModified ?? null });
  if (res.status === 304) return { notModified: true };
  if (res.status >= 400) throw new Error(`The feed returned an error (${res.status}).`);
  const parsed = parseFeed(res.body, podcastId);
  return { parsed, etag: res.etag, lastModified: res.lastModified };
}

async function refreshShow(p) {
  try {
    const r = await fetchAndParse(p.feedUrl, p.id, { etag: p.httpEtag, lastModified: p.httpLastModified });
    if (r.notModified) {
      p.lastRefreshedAt = Date.now();
      return 0;
    }
    p.httpEtag = r.etag ?? null;
    p.httpLastModified = r.lastModified ?? null;
    p.lastError = null;
    return L.applyFeedRefresh(state.lib, p.id, r.parsed).added;
  } catch (e) {
    p.lastError = String(e.message || e);
    return 0;
  }
}

async function refreshAll({ quiet = false } = {}) {
  if (state.refreshing) return;
  state.refreshing = true;
  if (!quiet) toast('Checking for new episodes…');
  const shows = L.subscribed(state.lib).filter((p) => !p.id.startsWith('local:'));
  let added = 0;
  const workers = Array.from({ length: 4 }, async () => {
    while (shows.length) added += await refreshShow(shows.shift());
  });
  await Promise.all(workers);
  L.rebuildQueue(state.lib, { nowPlayingId: activeId() });
  state.refreshing = false;
  persist();
  render();
  if (!quiet || added) toast(added ? `${added} new episode${added === 1 ? '' : 's'}` : 'No new episodes');
}

async function subscribe(rawUrl) {
  const url = normalize(rawUrl) || rawUrl.trim();
  if (!/^https?:\/\//i.test(url)) return toast('Paste the full feed address, starting with https://');
  const existing = Object.values(state.lib.podcasts).find((p) => sameFeed(p.feedUrl, url) || sameFeed(p.id, url));
  if (existing && existing.unsubscribedAt == null) {
    toast(`You already follow ${existing.title}.`);
    return openShow(existing.id);
  }
  toast('Fetching the feed…');
  try {
    const r = await fetchAndParse(url, existing?.id ?? url);
    const now = Date.now();
    if (existing) {
      existing.unsubscribedAt = null;
      existing.subscribedAt = now;
    } else {
      state.lib.podcasts[url] = L.newPodcast({
        id: url,
        feedUrl: url,
        ...r.parsed.podcast,
        subscribedAt: now,
        // 0 so a phone that already follows this show keeps its own rules on the first sync
        settingsUpdatedAt: 0,
        httpEtag: r.etag ?? null,
        httpLastModified: r.lastModified ?? null,
      });
    }
    const id = existing?.id ?? url;
    L.applyFeedRefresh(state.lib, id, r.parsed);
    L.rebuildQueue(state.lib, { nowPlayingId: activeId() });
    persist();
    syncSoon();
    toast(`Subscribed to ${state.lib.podcasts[id].title}`);
    openShow(id);
  } catch (e) {
    toast(String(e.message || e), 6000);
  }
}

async function searchDirectory(term) {
  state.searchResults = 'loading';
  render();
  try {
    const url = `https://itunes.apple.com/search?media=podcast&limit=25&term=${encodeURIComponent(term)}`;
    const res = await invoke('fetch_text', { url, etag: null, lastModified: null });
    state.searchResults = (JSON.parse(res.body).results || []).filter((r) => r.feedUrl);
  } catch (e) {
    state.searchResults = [];
    toast(`Search failed: ${e}`);
  }
  render();
}

// ---------------------------------------------------------------- sync

const syncIo = {
  list: (target) => invoke('sync_list', { target }),
  read: (target, name) => invoke('sync_read', { target, name }),
  write: (target, json) => invoke('sync_write', { target, json }),
  remove: (target, name) => invoke('sync_delete', { target, name }),
};

let syncTimer = null;
function syncSoon(ms = 4000) {
  if (!state.lib.settings.syncFolder || !state.lib.settings.autoSync) return;
  clearTimeout(syncTimer);
  syncTimer = setTimeout(() => syncNow({ quiet: true }), ms);
}

async function syncNow({ quiet = false } = {}) {
  if (!state.lib.settings.syncFolder) {
    if (!quiet) {
      toast('Set your sync folder first.');
      go('settings');
    }
    return;
  }
  if (state.syncing) return;
  state.syncing = true;
  state.syncMessage = 'Syncing with your phone…';
  state.syncError = false;
  renderChrome();
  try {
    saveProgressNow();
    const res = await runSync(state.lib, syncIo, {
      // New shows from the phone: load their episodes before the file is written back
      onNewShows: async (ids) => {
        for (const id of ids) {
          const p = state.lib.podcasts[id];
          if (p) await refreshShow(p);
        }
      },
    });
    state.syncMessage = `${res.message} · ${new Date().toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}`;
    L.rebuildQueue(state.lib, { nowPlayingId: activeId() });
    persist();
    if (!quiet) toast(res.message);
  } catch (e) {
    state.syncError = true;
    state.syncMessage = `Sync failed: ${e.message || e}`;
    if (!quiet) toast(state.syncMessage, 6000);
  } finally {
    state.syncing = false;
    render();
  }
}

// ---------------------------------------------------------------- playback

const activeId = () => state.nowPlaying;
const nowEp = () => (state.nowPlaying ? state.lib.episodes[state.nowPlaying] : null);
const showOf = (ep) => (ep ? state.lib.podcasts[ep.podcastId] : null);
let lastProgressSave = 0;
let finishing = false;

function play(episodeId, { autoplay = true } = {}) {
  const ep = state.lib.episodes[episodeId];
  if (!ep) return;
  if (state.nowPlaying && state.nowPlaying !== episodeId) saveProgressNow();
  state.nowPlaying = episodeId;
  state.lib.nowPlayingId = episodeId;
  finishing = false;
  const show = showOf(ep);
  audio.src = ep.downloadPath ? fileSrc(ep.downloadPath) : ep.audioUrl;
  audio.dataset.episode = episodeId;
  audio.playbackRate = show?.playbackSpeed || 1;
  if (autoplay) audio.play().catch((e) => toast(`Can't play this episode: ${e.message || e}`, 6000));
  L.rebuildQueue(state.lib, { nowPlayingId: episodeId });
  persist();
  updateMediaSession(ep, show);
  render();
}

audio.addEventListener('loadedmetadata', () => {
  const ep = state.lib.episodes[audio.dataset.episode];
  if (!ep) return;
  const show = showOf(ep);
  if (!ep.durationSeconds && Number.isFinite(audio.duration)) ep.durationSeconds = Math.round(audio.duration);
  const resumeAt = ep.playbackPositionSeconds;
  if (resumeAt > 0 && (!Number.isFinite(audio.duration) || resumeAt < audio.duration - 5)) audio.currentTime = resumeAt;
  else if (show?.skipIntroSeconds > 0) audio.currentTime = show.skipIntroSeconds;
  audio.playbackRate = show?.playbackSpeed || 1;
  $('#speed').value = String(audio.playbackRate);
});

audio.addEventListener('timeupdate', () => {
  const ep = nowEp();
  if (!ep) return;
  const t = audio.currentTime;
  if (Date.now() - lastProgressSave > 10_000 && !audio.paused) saveProgressNow();
  const show = showOf(ep);
  if (show?.skipOutroSeconds > 0 && Number.isFinite(audio.duration) && audio.duration - t <= show.skipOutroSeconds && t > 30) {
    finishCurrent();
  }
  renderProgress();
});
audio.addEventListener('play', () => render());
audio.addEventListener('pause', () => {
  saveProgressNow();
  render();
  syncSoon(30_000);
});
audio.addEventListener('ended', () => finishCurrent());
audio.addEventListener('error', () => {
  if (audio.dataset.episode) toast("This episode wouldn't load. Check your connection, or download it first.", 6000);
});

function saveProgressNow() {
  const ep = nowEp();
  if (!ep || !audio.src || audio.readyState === 0) return;
  lastProgressSave = Date.now();
  L.updatePlaybackProgress(state.lib, ep.id, audio.currentTime);
  persist();
}

async function finishCurrent() {
  if (finishing) return;
  finishing = true;
  const ep = nowEp();
  if (!ep) return;
  const path = ep.downloadPath;
  L.markFinished(state.lib, ep.id);
  if (path) {
    ep.downloadPath = null;
    invoke('delete_download', { path }).catch(() => {});
  }
  persist();
  syncSoon();
  playNextInQueue();
}

function playNextInQueue() {
  const next = L.queuedEpisodes(state.lib).find((e) => e.id !== state.nowPlaying);
  if (next) play(next.id);
  else {
    audio.pause();
    audio.removeAttribute('src');
    state.nowPlaying = null;
    state.lib.nowPlayingId = null;
    render();
    toast("That's the end of your queue.");
  }
}

function skipEpisode() {
  const ep = nowEp();
  if (!ep) return;
  L.markSkipped(state.lib, ep.id, audio.currentTime || ep.playbackPositionSeconds);
  persist();
  syncSoon();
  playNextInQueue();
}

function seek(delta) {
  if (!audio.src) return;
  const dur = Number.isFinite(audio.duration) ? audio.duration : Infinity;
  audio.currentTime = Math.max(0, Math.min(dur - 1, audio.currentTime + delta));
  saveProgressNow();
}

function togglePlay() {
  if (!audio.src) {
    const first = L.queuedEpisodes(state.lib)[0];
    if (first) play(first.id);
    return;
  }
  audio.paused ? audio.play() : audio.pause();
}

function updateMediaSession(ep, show) {
  if (!('mediaSession' in navigator)) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: ep.title,
    artist: show?.title || ep.podcastTitle,
    album: 'Eager to Listen',
    artwork: ep.artworkUrl ? [{ src: ep.artworkUrl, sizes: '512x512' }] : [],
  });
  const s = state.lib.settings;
  const handlers = {
    play: () => audio.play(),
    pause: () => audio.pause(),
    seekbackward: () => seek(-s.seekBackSeconds),
    seekforward: () => seek(s.seekForwardSeconds),
    // Same default as the phone's car/headset buttons: Next/Previous jump within the episode
    previoustrack: () => seek(-s.seekBackSeconds),
    nexttrack: () => seek(s.seekForwardSeconds),
  };
  for (const [k, fn] of Object.entries(handlers)) {
    try {
      navigator.mediaSession.setActionHandler(k, fn);
    } catch {
      /* not supported here */
    }
  }
}

function saveIdea() {
  const ep = nowEp();
  if (!ep) return toast('Start an episode first.');
  const id = crypto.randomUUID();
  const show = showOf(ep);
  state.lib.inspirations[id] = {
    id,
    episodeId: ep.id,
    podcastId: ep.podcastId,
    podcastTitle: show?.title || ep.podcastTitle,
    episodeTitle: ep.title,
    timestampSeconds: Math.floor(audio.currentTime),
    audioUrl: ep.audioUrl,
    note: '',
    quoteText: '',
    tags: '',
    createdAt: Date.now(),
  };
  persist();
  syncSoon();
  toast(`Bookmarked at ${fmtTime(audio.currentTime)}`);
}

function playBookmark(clipId) {
  const c = state.lib.inspirations[clipId];
  if (!c) return;
  const ep = state.lib.episodes[c.episodeId];
  if (!ep) return toast("That episode isn't in your library on this PC yet.");
  ep.playbackPositionSeconds = c.timestampSeconds;
  play(ep.id);
}

// ---------------------------------------------------------------- downloads

async function download(episodeId) {
  const ep = state.lib.episodes[episodeId];
  if (!ep || ep.downloadPath) return;
  state.downloads[episodeId] = { received: 0, total: null };
  render();
  try {
    const stem = `${(state.lib.podcasts[ep.podcastId]?.title || 'show').slice(0, 40)}-${ep.title.slice(0, 60)}-${(ep.id.length * 7919 + ep.publishedAt) % 1e9}`;
    ep.downloadPath = await invoke('download_episode', { episodeId, url: ep.audioUrl, fileStem: stem });
    persist();
    toast('Downloaded');
  } catch (e) {
    toast(`Download failed: ${e}`, 6000);
  } finally {
    delete state.downloads[episodeId];
    render();
  }
}

async function removeDownload(episodeId) {
  const ep = state.lib.episodes[episodeId];
  if (!ep?.downloadPath) return;
  await invoke('delete_download', { path: ep.downloadPath }).catch(() => {});
  ep.downloadPath = null;
  persist();
  render();
}

listen('download-progress', (e) => {
  const p = e.payload;
  if (state.downloads[p.episodeId]) {
    state.downloads[p.episodeId] = p;
    const el = document.querySelector(`[data-dl="${CSS.escape(p.episodeId)}"]`);
    if (el) el.textContent = p.total ? `${Math.round((p.received / p.total) * 100)}%` : `${Math.round(p.received / 1e6)} MB`;
  }
});

// ---------------------------------------------------------------- views

function go(view, opts = {}) {
  state.view = view;
  if (opts.showId) state.showId = opts.showId;
  render();
  main.scrollTop = 0;
  main.focus({ preventScroll: true });
}
const openShow = (id) => go('show', { showId: id });

function episodeRow(ep, { context = 'queue', queueItem = null } = {}) {
  const show = showOf(ep);
  const playing = ep.id === state.nowPlaying;
  const pct = ep.durationSeconds ? Math.min(100, (ep.playbackPositionSeconds / ep.durationSeconds) * 100) : 0;
  const tags = [];
  if (queueItem?.isManual) tags.push('<span class="tag pin">Pinned</span>');
  if (ep.isCompleted) tags.push('<span class="tag done">Played</span>');
  else if (ep.skippedAt) tags.push('<span class="tag">Skipped</span>');
  if (ep.downloadPath) tags.push('<span class="tag done">✓ Downloaded</span>');
  const dl = state.downloads[ep.id];
  const line2 = [context === 'queue' ? esc(show?.title || ep.podcastTitle) : '', fmtDate(ep.publishedAt), ep.durationSeconds ? fmtTime(ep.durationSeconds) : '']
    .filter(Boolean)
    .join(' · ');
  return `<div class="item ${playing ? 'playing' : ''}">
    <img src="${art(ep.artworkUrl || show?.artworkUrl)}" alt="" loading="lazy" />
    <div class="meta">
      <div class="title" title="${esc(ep.title)}">${esc(ep.title)}</div>
      <div class="line2">${tags.join('')}${line2}</div>
      ${pct > 0 && !ep.isCompleted ? `<div class="bar"><i style="width:${pct.toFixed(1)}%"></i></div>` : ''}
    </div>
    <div class="actions">
      ${dl ? `<span class="tag" data-dl="${esc(ep.id)}">…</span>` : ''}
      <button class="icon-btn" data-action="episode-menu" data-id="${esc(ep.id)}" title="More">⋮</button>
      <button class="btn small" data-action="play" data-id="${esc(ep.id)}">${playing && !audio.paused ? 'Playing' : ep.playbackPositionSeconds > 0 && !ep.isCompleted ? 'Resume' : 'Play'}</button>
    </div>
  </div>`;
}

function viewQueue() {
  const items = [...state.lib.queue].sort((a, b) => a.orderIndex - b.orderIndex);
  const shows = L.subscribed(state.lib).length;
  if (!items.length) {
    return `<h1>Queue</h1>
      <div class="empty">${
        shows
          ? '<p>Your queue is empty. Every show is caught up, or its episodes are still loading.</p><button class="btn" data-action="refresh-all">Check for new episodes</button>'
          : `<p>No shows yet.</p><p>The fastest start is syncing with your phone: set your Drive sync folder in Settings and your shows, queue and progress come over.</p>
             <div class="row-actions"><button class="btn" data-view="settings">Set up phone sync</button><button class="ghost" data-view="add">Add a show by hand</button></div>`
      }</div>`;
  }
  return `<h1>Queue</h1>
    <p class="sub">${items.length} episodes, programmed fairly across your shows. Pinned episodes stay on top.</p>
    <div class="row-actions"><button class="ghost small" data-action="clear-queue">Clear pinned & rebuild</button></div>
    <div class="list">${items
      .map((q) => {
        const ep = state.lib.episodes[q.episodeId];
        return ep ? episodeRow(ep, { queueItem: q }) : '';
      })
      .join('')}</div>`;
}

function viewShows() {
  const shows = L.subscribed(state.lib).sort((a, b) => a.priority - b.priority || a.title.localeCompare(b.title));
  if (!shows.length) return `<h1>Shows</h1><div class="empty"><p>No shows yet.</p><button class="btn" data-view="add">Add a show</button></div>`;
  return `<h1>Shows</h1><p class="sub">${shows.length} show${shows.length === 1 ? '' : 's'}, highest priority first.</p>
    <div class="list">${shows
      .map((p) => {
        const unplayed = Object.values(state.lib.episodes).filter((e) => e.podcastId === p.id && !e.isCompleted && !e.isDismissed).length;
        return `<div class="item">
          <img src="${art(p.artworkUrl)}" alt="" loading="lazy" />
          <div class="meta"><div class="title">${esc(p.title)}</div>
            <div class="line2"><span class="tag">P${p.priority}</span><span class="tag">${esc(STRATEGY_LABELS[p.playOrderStrategy]?.[0] || p.playOrderStrategy)}</span>${unplayed} unplayed${p.lastError ? ` · <span style="color:var(--danger)">${esc(p.lastError)}</span>` : ''}</div></div>
          <div class="actions"><button class="ghost small" data-action="open-show" data-id="${esc(p.id)}">Open</button></div>
        </div>`;
      })
      .join('')}</div>`;
}

function viewShow() {
  const p = state.lib.podcasts[state.showId];
  if (!p) return viewShows();
  const eps = Object.values(state.lib.episodes)
    .filter((e) => e.podcastId === p.id)
    .sort((a, b) => b.publishedAt - a.publishedAt);
  const queued = new Map(state.lib.queue.map((q) => [q.episodeId, q]));
  const strat = Object.entries(STRATEGY_LABELS)
    .map(([k, [label]]) => `<option value="${k}" ${p.playOrderStrategy === k ? 'selected' : ''}>${label}</option>`)
    .join('');
  const opt = (vals, cur, fmt = (v) => v) => vals.map((v) => `<option value="${v}" ${Number(cur) === v ? 'selected' : ''}>${fmt(v)}</option>`).join('');
  return `<button class="link" data-view="shows">← All shows</button>
    <div class="show-head" style="margin-top:12px">
      <img src="${art(p.artworkUrl)}" alt="" />
      <div><h1>${esc(p.title)}</h1><p class="sub">${esc(p.author)}${p.unsubscribedAt ? ' · Unsubscribed' : ''}</p>
        <div class="show-desc">${esc(stripHtml(p.description)).slice(0, 600)}</div>
        <div class="row-actions" style="margin-top:10px">
          ${p.customDonationUrl || p.fundingUrl ? `<button class="btn small" data-action="open-url" data-url="${esc(p.customDonationUrl || p.fundingUrl)}">♥ Support Show</button>` : ''}
          <button class="ghost small" data-action="refresh-show" data-id="${esc(p.id)}">Check for new episodes</button>
          ${p.unsubscribedAt ? `<button class="btn small" data-action="resubscribe" data-id="${esc(p.id)}">Subscribe again</button>` : `<button class="ghost small danger" data-action="unsubscribe" data-id="${esc(p.id)}">Unsubscribe</button>`}
        </div></div>
    </div>
    <h2>Rules for this show</h2>
    <form class="form" id="rulesForm" data-id="${esc(p.id)}">
      <label for="r-priority">Priority</label>
      <select id="r-priority" name="priority">${opt([1, 2, 3, 4, 5], p.priority, (v) => `${v}${v === 1 ? ' (highest)' : v === 5 ? ' (lowest)' : ''}`)}</select>
      <label for="r-strategy">Play order</label><select id="r-strategy" name="playOrderStrategy">${strat}</select>
      <div class="hint">${esc(STRATEGY_LABELS[p.playOrderStrategy]?.[1] || '')}</div>
      <label for="r-news">News: keep episodes for</label>
      <select id="r-news" name="newsRetentionDays">${opt([1, 2, 3, 5, 7, 14], p.newsRetentionDays, (v) => `${v} day${v === 1 ? '' : 's'}`)}</select>
      <label for="r-max">Most episodes in the queue</label><select id="r-max" name="maxEpisodesInQueue">${opt([1, 2, 3, 5, 10, 20, 50], p.maxEpisodesInQueue)}</select>
      <label for="r-limit">Back-to-back limit</label>
      <select id="r-limit" name="consecutivePlayLimit">${opt([-1, 1, 2, 3, 5], p.consecutivePlayLimit, (v) => (v === -1 ? 'Use global setting' : v))}</select>
      <label for="r-auto">Add new episodes to queue</label><input id="r-auto" type="checkbox" name="autoAddToQueue" ${p.autoAddToQueue ? 'checked' : ''} />
      <label for="r-intro">Skip intro (seconds)</label><input id="r-intro" type="number" min="0" max="600" name="skipIntroSeconds" value="${p.skipIntroSeconds}" />
      <label for="r-outro">Skip outro (seconds)</label><input id="r-outro" type="number" min="0" max="600" name="skipOutroSeconds" value="${p.skipOutroSeconds}" />
      <label for="r-speed">Speed</label><select id="r-speed" name="playbackSpeed">${[0.8, 1, 1.1, 1.25, 1.5, 1.75, 2]
        .map((v) => `<option value="${v}" ${Number(p.playbackSpeed) === v ? 'selected' : ''}>${v}×</option>`)
        .join('')}</select>
      <span></span><div><button class="btn" type="submit">Save rules</button></div>
    </form>
    <h2>Episodes (${eps.length})</h2>
    <div class="list">${eps.map((e) => episodeRow(e, { context: 'show', queueItem: queued.get(e.id) })).join('') || '<div class="empty">No episodes loaded yet.</div>'}</div>`;
}

function viewAdd() {
  const r = state.searchResults;
  const results =
    r === 'loading'
      ? '<p class="sub">Searching…</p>'
      : Array.isArray(r)
        ? r.length
          ? `<div class="list">${r
              .map((x) => {
                const have = Object.values(state.lib.podcasts).some((p) => p.unsubscribedAt == null && sameFeed(p.feedUrl, x.feedUrl));
                return `<div class="item"><img src="${art(x.artworkUrl100)}" alt="" loading="lazy" />
                <div class="meta"><div class="title">${esc(x.collectionName)}</div><div class="line2">${esc(x.artistName)}</div></div>
                <div class="actions">${have ? '<span class="tag done">Subscribed</span>' : `<button class="btn small" data-action="subscribe" data-url="${esc(x.feedUrl)}">Subscribe</button>`}</div></div>`;
              })
              .join('')}</div>`
          : '<p class="sub">Nothing found.</p>'
        : '';
  return `<h1>Add a show</h1><p class="sub">Search the Apple Podcasts directory, or paste a feed address.</p>
    <form class="search" id="searchForm"><input type="search" name="q" placeholder="Search shows" aria-label="Search shows" /><button class="btn">Search</button></form>
    <form class="search" id="urlForm"><input type="text" name="url" placeholder="https://… feed address (RSS)" aria-label="Feed address" /><button class="ghost">Subscribe</button></form>
    ${results}`;
}

function viewBookmarks() {
  const clips = Object.values(state.lib.inspirations).sort((a, b) => b.createdAt - a.createdAt);
  if (!clips.length) return `<h1>Bookmarks</h1><div class="empty"><p>No bookmarks yet. Press ☆ in the player to save the spot you're at. Bookmarks from your phone show up here after a sync.</p></div>`;
  return `<h1>Bookmarks</h1><p class="sub">Saved spots, from this PC and your phone.</p>
    <div class="list">${clips
      .map(
        (c) => `<div class="item"><img src="${art(state.lib.podcasts[c.podcastId]?.artworkUrl)}" alt="" loading="lazy" />
        <div class="meta"><div class="title">${esc(c.episodeTitle)}</div>
          <div class="line2">${esc(c.podcastTitle)} · at ${fmtTime(c.timestampSeconds)}${c.note ? ` · ${esc(c.note)}` : ''}</div></div>
        <div class="actions"><button class="btn small" data-action="play-bookmark" data-id="${esc(c.id)}">Play from here</button></div></div>`,
      )
      .join('')}</div>`;
}

function viewSettings() {
  const s = state.lib.settings;
  const rc = state.rclone;
  const rcLine = !rc
    ? 'Checking for rclone…'
    : rc.installed
      ? `rclone found (${esc(rc.version || '')}). Remotes: ${rc.remotes.length ? rc.remotes.map((r) => `<code>${esc(r)}</code>`).join(' ') : 'none yet'}`
      : `<span style="color:var(--danger)">rclone isn't installed.</span> Install it with <code>sudo apt install rclone</code>, then run <code>rclone config</code> and add a Google Drive remote.`;
  const opt = (vals, cur, fmt = (v) => v) => vals.map((v) => `<option value="${v}" ${Number(cur) === v ? 'selected' : ''}>${fmt(v)}</option>`).join('');
  return `<h1>Settings</h1>
    <h2>Phone sync</h2>
    <div class="card">
      <p>The PC reads and writes the same <code>eagertolisten_sync.json</code> your phone keeps in Google Drive, so shows, queue, progress and bookmarks match on both.</p>
      <p>Use the same Drive folder you picked on the phone (Settings → Google Drive Sync). With rclone, write it as <code>remote:Folder</code>, for example <code>gdrive:EagerToListen</code>. A plain folder path works too, if that folder already mirrors Drive.</p>
      <p>${rcLine}</p>
    </div>
    <form class="form" id="syncForm">
      <label for="s-folder">Sync folder</label><input id="s-folder" type="text" name="syncFolder" value="${esc(s.syncFolder || '')}" placeholder="gdrive:EagerToListen" />
      <label for="s-auto">Sync automatically</label><input id="s-auto" type="checkbox" name="autoSync" ${s.autoSync ? 'checked' : ''} />
      <div class="hint">After each episode, when you pause, and every 15 minutes.</div>
      <span></span><div class="row-actions"><button class="btn" type="submit">Save & sync now</button></div>
    </form>
    <p class="sub">Last sync: ${ago(state.lib.lastSyncAt)}${state.syncMessage ? ` · ${esc(state.syncMessage)}` : ''}</p>
    <h2>Player</h2>
    <form class="form" id="playerForm">
      <label for="p-back">Rewind</label><select id="p-back" name="seekBackSeconds">${opt([10, 15, 30, 45, 60, 90], s.seekBackSeconds, (v) => `${v} seconds`)}</select>
      <label for="p-fwd">Forward</label><select id="p-fwd" name="seekForwardSeconds">${opt([10, 15, 30, 45, 60, 90], s.seekForwardSeconds, (v) => `${v} seconds`)}</select>
      <label for="p-limit">Back-to-back limit (all shows)</label><select id="p-limit" name="globalConsecutiveLimit">${opt([1, 2, 3, 5], s.globalConsecutiveLimit)}</select>
      <div class="hint">How many episodes of one show play in a row before another show gets a turn.</div>
      <label for="p-news">News: keep episodes for</label><select id="p-news" name="defaultNewsDays">${opt([1, 2, 3, 5, 7], s.defaultNewsDays, (v) => `${v} day${v === 1 ? '' : 's'}`)}</select>
      <span></span><div><button class="btn" type="submit">Save</button></div>
    </form>
    <h2>About</h2>
    <p class="sub">Eager to Listen desktop, alpha. Device ID <code>${esc(state.lib.deviceId)}</code>. Keys: Space play/pause, ← rewind, → forward.</p>`;
}

function render() {
  const views = { queue: viewQueue, shows: viewShows, show: viewShow, add: viewAdd, bookmarks: viewBookmarks, settings: viewSettings };
  main.innerHTML = (views[state.view] || viewQueue)();
  renderChrome();
}

function renderChrome() {
  document.querySelectorAll('.nav').forEach((b) => b.classList.toggle('active', b.dataset.view === state.view || (state.view === 'show' && b.dataset.view === 'shows')));
  $('#queueCount').textContent = state.lib.queue.length || '';
  const st = $('#syncStatus');
  st.textContent = state.lib.settings.syncFolder ? state.syncMessage || `Last sync ${ago(state.lib.lastSyncAt)}` : 'Phone sync not set up';
  st.classList.toggle('error', state.syncError);
  const ep = nowEp();
  const show = showOf(ep);
  $('#npTitle').textContent = ep ? ep.title : 'Nothing playing';
  $('#npShow').textContent = show ? show.title : '';
  const want = ep ? ep.artworkUrl || show?.artworkUrl || 'icon.png' : 'icon.png';
  const npArt = $('#npArt');
  if (npArt.dataset.want !== want) {
    npArt.dataset.want = want;
    npArt.src = want;
  }
  $('#playBtn').textContent = audio.src && !audio.paused ? '❚❚' : '▶';
  $('#backLabel').textContent = state.lib.settings.seekBackSeconds;
  $('#fwdLabel').textContent = state.lib.settings.seekForwardSeconds;
  renderProgress();
}

let scrubbing = false;
function renderProgress() {
  if (scrubbing) return;
  const dur = Number.isFinite(audio.duration) ? audio.duration : nowEp()?.durationSeconds || 0;
  const sc = $('#scrubber');
  sc.max = Math.floor(dur);
  sc.value = Math.floor(audio.currentTime || 0);
  $('#npTime').textContent = fmtTime(audio.currentTime);
  $('#npDuration').textContent = fmtTime(dur);
}

// ---------------------------------------------------------------- menus

function closeMenu() {
  document.querySelector('.menu')?.remove();
}

function episodeMenu(id, anchor) {
  closeMenu();
  const ep = state.lib.episodes[id];
  if (!ep) return;
  const inQueue = state.lib.queue.some((q) => q.episodeId === id);
  const items = [
    ['play-next', 'Play next'],
    !inQueue && !ep.isCompleted ? ['add-to-queue', 'Add to queue'] : null,
    inQueue ? ['remove-from-queue', 'Remove from queue'] : null,
    ep.isCompleted ? ['mark-unplayed', 'Mark as unplayed'] : ['mark-played', 'Mark as played'],
    ep.downloadPath ? ['remove-download', 'Remove download'] : ['download', 'Download'],
    ['open-show', 'Go to show'],
  ].filter(Boolean);
  const m = document.createElement('div');
  m.className = 'menu';
  m.setAttribute('role', 'menu');
  m.innerHTML = items.map(([a, label]) => `<button role="menuitem" data-action="${a}" data-id="${esc(a === 'open-show' ? ep.podcastId : id)}">${label}</button>`).join('');
  document.body.appendChild(m);
  const r = anchor.getBoundingClientRect();
  const mr = m.getBoundingClientRect();
  m.style.left = `${Math.min(r.right - mr.width, window.innerWidth - mr.width - 8)}px`;
  m.style.top = `${r.bottom + mr.height > window.innerHeight - 8 ? r.top - mr.height : r.bottom}px`;
  m.querySelector('button')?.focus();
}

// ---------------------------------------------------------------- events

document.addEventListener('click', (e) => {
  const viewBtn = e.target.closest('[data-view]');
  if (viewBtn) {
    closeMenu();
    return go(viewBtn.dataset.view);
  }
  const el = e.target.closest('[data-action]');
  if (!el) return closeMenu();
  const id = el.dataset.id;
  const a = el.dataset.action;
  if (a !== 'episode-menu') closeMenu();
  const lib = state.lib;
  const after = (msg) => {
    persist();
    syncSoon();
    render();
    if (msg) toast(msg);
  };
  switch (a) {
    case 'play':
      if (id === state.nowPlaying && audio.src) togglePlay();
      else play(id);
      break;
    case 'episode-menu':
      episodeMenu(id, el);
      break;
    case 'play-next':
      L.playNext(lib, id);
      after('Playing next');
      break;
    case 'add-to-queue':
      L.addToQueue(lib, id);
      after('Added to queue');
      break;
    case 'remove-from-queue':
      L.removeFromQueue(lib, id);
      after('Removed from queue');
      break;
    case 'mark-played':
      if (id === state.nowPlaying) return finishCurrent();
      L.markFinished(lib, id);
      after('Marked as played');
      break;
    case 'mark-unplayed':
      L.markUnplayed(lib, id);
      after('Marked as unplayed');
      break;
    case 'download':
      download(id);
      break;
    case 'remove-download':
      removeDownload(id);
      break;
    case 'open-show':
      openShow(id);
      break;
    case 'open-now-playing-show':
      if (nowEp()) openShow(nowEp().podcastId);
      break;
    case 'refresh-all':
      refreshAll();
      break;
    case 'refresh-show': {
      const p = lib.podcasts[id];
      if (p) refreshShow(p).then((n) => { L.rebuildQueue(lib, { nowPlayingId: activeId() }); after(n ? `${n} new episodes` : 'No new episodes'); });
      break;
    }
    case 'unsubscribe':
      if (confirm(`Unsubscribe from ${lib.podcasts[id]?.title}? Your phone will follow on the next sync.`)) {
        L.unsubscribe(lib, id);
        L.rebuildQueue(lib, { nowPlayingId: activeId() });
        after('Unsubscribed');
      }
      break;
    case 'resubscribe':
      subscribe(lib.podcasts[id].feedUrl);
      break;
    case 'subscribe':
      subscribe(el.dataset.url);
      break;
    case 'clear-queue':
      if (confirm('Unpin everything and rebuild the queue from your rules?')) {
        L.clearQueue(lib);
        L.rebuildQueue(lib, { nowPlayingId: activeId() });
        after('Queue rebuilt');
      }
      break;
    case 'sync-now':
      syncNow();
      break;
    case 'toggle-play':
      togglePlay();
      break;
    case 'seek-back':
      seek(-lib.settings.seekBackSeconds);
      break;
    case 'seek-forward':
      seek(lib.settings.seekForwardSeconds);
      break;
    case 'skip-episode':
      skipEpisode();
      break;
    case 'save-idea':
      saveIdea();
      break;
    case 'play-bookmark':
      playBookmark(id);
      break;
    case 'open-url':
      T.opener.openUrl(el.dataset.url).catch((err) => toast(`Couldn't open the link: ${err}`));
      break;
  }
});

document.addEventListener('submit', (e) => {
  e.preventDefault();
  const f = e.target;
  const data = Object.fromEntries(new FormData(f));
  const lib = state.lib;
  if (f.id === 'searchForm' && data.q?.trim()) searchDirectory(data.q.trim());
  else if (f.id === 'urlForm' && data.url?.trim()) subscribe(data.url.trim());
  else if (f.id === 'rulesForm') {
    L.updateRules(lib, f.dataset.id, {
      priority: Number(data.priority),
      playOrderStrategy: data.playOrderStrategy,
      newsRetentionDays: Number(data.newsRetentionDays),
      maxEpisodesInQueue: Number(data.maxEpisodesInQueue),
      consecutivePlayLimit: Number(data.consecutivePlayLimit),
      autoAddToQueue: data.autoAddToQueue === 'on',
      skipIntroSeconds: Math.max(0, Number(data.skipIntroSeconds) || 0),
      skipOutroSeconds: Math.max(0, Number(data.skipOutroSeconds) || 0),
      playbackSpeed: Number(data.playbackSpeed),
    });
    if (nowEp()?.podcastId === f.dataset.id) audio.playbackRate = Number(data.playbackSpeed);
    persist();
    syncSoon();
    render();
    toast('Rules saved');
  } else if (f.id === 'syncForm') {
    lib.settings.syncFolder = data.syncFolder.trim() || null;
    lib.settings.autoSync = data.autoSync === 'on';
    persist();
    render();
    if (lib.settings.syncFolder) syncNow();
  } else if (f.id === 'playerForm') {
    Object.assign(lib.settings, {
      seekBackSeconds: Number(data.seekBackSeconds),
      seekForwardSeconds: Number(data.seekForwardSeconds),
      globalConsecutiveLimit: Number(data.globalConsecutiveLimit),
      defaultNewsDays: Number(data.defaultNewsDays),
    });
    L.rebuildQueue(lib, { nowPlayingId: activeId() });
    persist();
    render();
    toast('Saved');
  }
});

document.addEventListener('change', (e) => {
  if (e.target.id === 'r-strategy') {
    const hint = e.target.parentElement.querySelector('#r-strategy + .hint');
    if (hint) hint.textContent = STRATEGY_LABELS[e.target.value]?.[1] || '';
  }
});

const scrubber = $('#scrubber');
scrubber.addEventListener('input', () => {
  scrubbing = true;
  $('#npTime').textContent = fmtTime(Number(scrubber.value));
});
scrubber.addEventListener('change', () => {
  scrubbing = false;
  if (audio.src) audio.currentTime = Number(scrubber.value);
  saveProgressNow();
});
$('#speed').addEventListener('change', (e) => {
  audio.playbackRate = Number(e.target.value);
});

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') return closeMenu();
  if (e.target.closest('input, select, textarea')) return;
  if (e.code === 'Space' && !e.target.closest('button')) {
    e.preventDefault();
    togglePlay();
  } else if (e.key === 'ArrowLeft') seek(-state.lib.settings.seekBackSeconds);
  else if (e.key === 'ArrowRight') seek(state.lib.settings.seekForwardSeconds);
});

// Artwork that fails to load (offline, blocked host) falls back to the app icon
document.addEventListener(
  'error',
  (e) => {
    const img = e.target;
    if (img instanceof HTMLImageElement && !img.src.endsWith('/icon.png')) img.src = 'icon.png';
  },
  true,
);

window.addEventListener('beforeunload', () => {
  saveProgressNow();
  saveNow();
});

// ---------------------------------------------------------------- start

async function start() {
  await loadLibrary();
  // Put the last episode back in the player, paused, where you left it
  const last = state.lib.nowPlayingId && state.lib.episodes[state.lib.nowPlayingId];
  if (last && !last.isCompleted) play(last.id, { autoplay: false });
  else L.rebuildQueue(state.lib);
  render();
  invoke('rclone_status').then((r) => {
    state.rclone = r;
    if (state.view === 'settings') render();
  });
  if (state.lib.settings.syncFolder) await syncNow({ quiet: true });
  refreshAll({ quiet: true });
  setInterval(() => syncSoon(0), 15 * 60_000);
  setInterval(() => refreshAll({ quiet: true }), 60 * 60_000);
  setInterval(renderChrome, 60_000);
}

start().catch((e) => {
  main.innerHTML = `<div class="empty"><p>Something went wrong starting up:</p><p><code>${esc(e.message || e)}</code></p></div>`;
});
