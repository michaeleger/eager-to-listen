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
  searchSource: '',
  queueTab: 'queue', // queue | hot | unplayed | played
  rclone: null,
  finder: null, // { busy, results, error } for "Find my sync folder"
  browser: null, // { target, folders, hasSyncFile, busy, error } for picking a folder by hand
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

// Same featured shows the phone shows on Discover (PodcastIndexClient.PROMOTED_PODCASTS)
const FEATURED = [
  { title: 'Eager to Be Healthy - a Health and Happiness Podcast', author: 'Michael Scott Eger', feedUrl: 'https://api.riverside.com/hosting/drlxDc7x.rss', artworkUrl: 'https://hosting-media.riverside.com/media/podcasts/25c060e8-e598-4782-920d-b96b89a1ffb0/logos/6046bb02-7f6e-4dfa-9ece-f617d9ce3508.jpeg' },
  { title: 'No Agenda Show', author: 'Adam Curry & John C. Dvorak', feedUrl: 'https://feeds.noagendaassets.com/noagenda.xml', artworkUrl: 'https://noagendaassets.com/enc/1792709117.49_na-1914-art-feed.jpg' },
  { title: 'Podcasting 2.0', author: 'Adam Curry & Dave Jones', feedUrl: 'https://feeds.podcastindex.org/pc20.xml', artworkUrl: 'https://noagendaassets.com/enc/1684513486.722_pcifeedimage.png' },
  { title: 'Huberman Lab', author: 'Dr. Andrew Huberman', feedUrl: 'https://feeds.megaphone.fm/hubermanlab', artworkUrl: '' },
  { title: 'The Peter Attia Drive', author: 'Peter Attia, MD', feedUrl: 'https://peterattiadrive.libsyn.com/rss', artworkUrl: '' },
];
const CATEGORIES = ['News', 'Health & Fitness', 'Self-Help', 'Technology', 'Science', 'Society & Culture', 'Business', 'History', 'True Crime', 'Comedy', 'Education'];

const hasPodcastIndex = () => !!(state.lib.settings.podcastIndexKey && state.lib.settings.podcastIndexSecret);

async function podcastIndex(path) {
  const body = await invoke('podcastindex_get', { path, apiKey: state.lib.settings.podcastIndexKey, apiSecret: state.lib.settings.podcastIndexSecret });
  return (JSON.parse(body).feeds || [])
    .filter((f) => f.url)
    .map((f) => ({ title: f.title || 'Untitled', author: f.author || '', feedUrl: f.url, artworkUrl: f.artwork || f.image || '' }));
}

async function appleSearch(term) {
  const url = `https://itunes.apple.com/search?media=podcast&limit=25&term=${encodeURIComponent(term)}`;
  const res = await invoke('fetch_text', { url, etag: null, lastModified: null });
  return (JSON.parse(res.body).results || [])
    .filter((r) => r.feedUrl)
    .map((r) => ({ title: r.collectionName, author: r.artistName, feedUrl: r.feedUrl, artworkUrl: r.artworkUrl100 }));
}

async function searchDirectory(term, { category = null } = {}) {
  state.searchResults = 'loading';
  render();
  let results = [];
  let source = '';
  try {
    if (hasPodcastIndex()) {
      try {
        results = category
          ? await podcastIndex(`podcasts/bycategory?cat=${encodeURIComponent(category)}&max=30`)
          : await podcastIndex(`search/byterm?q=${encodeURIComponent(term)}&max=30`);
        source = 'Podcast Index';
      } catch (e) {
        toast(`${e} Using Apple Podcasts instead.`, 6000);
      }
    }
    if (!results.length && !source) {
      results = await appleSearch(category || term);
      source = 'Apple Podcasts';
    }
    // Pin the house shows on matching searches, as the phone does
    const q = (category || term).toLowerCase();
    const pins = [];
    if (q.includes('no agenda')) pins.push(FEATURED[1]);
    if (q.includes('eager') || q === 'health' || q.includes('wellness') || q.includes('self help') || q.includes('self-help')) pins.push(FEATURED[0]);
    state.searchResults = [...pins, ...results.filter((r) => !pins.some((p) => sameFeed(p.feedUrl, r.feedUrl)))];
    state.searchSource = source;
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
  if (document.hasFocus()) main.focus({ preventScroll: true });
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
  const lib = state.lib;
  const items = [...lib.queue].sort((a, b) => a.orderIndex - b.orderIndex);
  const shows = L.subscribed(lib).length;
  const tabs = [
    ['queue', `Queue (${items.length})`],
    ['hot', `Hot List (${L.hotList(lib).length})`],
    ['unplayed', 'Unplayed'],
    ['played', 'Played'],
  ];
  const tabBar = `<div class="row-actions" role="tablist">${tabs
    .map(([k, label]) => `<button role="tab" aria-selected="${state.queueTab === k}" class="${state.queueTab === k ? 'btn' : 'ghost'} small" data-action="queue-tab" data-tab="${k}">${label}</button>`)
    .join('')}</div>`;

  if (state.queueTab !== 'queue') {
    const list = state.queueTab === 'hot' ? L.hotList(lib) : state.queueTab === 'unplayed' ? L.unplayedList(lib) : L.playedList(lib);
    const queued = new Map(lib.queue.map((q) => [q.episodeId, q]));
    const empty = { hot: 'No Priority 1 episodes waiting. Set a show to Priority 1 on its page.', unplayed: 'Nothing unplayed.', played: 'Nothing played yet.' }[state.queueTab];
    return `<h1>Queue</h1>${tabBar}
      <div class="list">${list.map((e) => episodeRow(e, { queueItem: queued.get(e.id) })).join('') || `<div class="empty">${empty}</div>`}</div>`;
  }

  if (!items.length) {
    return `<h1>Queue</h1>${tabBar}
      <div class="empty">${
        shows
          ? '<p>Your queue is empty. Every show is caught up, or its episodes are still loading.</p><button class="btn" data-action="refresh-all">Check for new episodes</button>'
          : `<p>No shows yet.</p><p>The fastest start is syncing with your phone: set your Drive sync folder in Settings and your shows, queue and progress come over.</p>
             <div class="row-actions"><button class="btn" data-view="settings">Set up phone sync</button><button class="ghost" data-view="add">Add a show by hand</button></div>`
      }</div>`;
  }
  const rule = lib.settings.queueSortRule || 'PROGRAMMED_FAIR';
  const order = Object.entries(L.QUEUE_SORT_RULES)
    .map(([k, label]) => `<option value="${k}" ${rule === k ? 'selected' : ''}>${esc(label)}</option>`)
    .join('');
  return `<h1>Queue</h1>${tabBar}
    <div class="row-actions" style="align-items:center">
      <label for="queueOrder" class="sub" style="margin:0">Order</label>
      <select id="queueOrder" style="width:auto;max-width:420px" aria-label="Queue order">${order}</select>
      <button class="ghost small" data-action="clear-queue">Clear pinned & rebuild</button>
    </div>
    <p class="sub">${rule === 'PROGRAMMED_FAIR' ? 'Programmed like a radio station: shows take turns, with your back-to-back limit and play orders. Pinned episodes stay on top.' : 'Pinned episodes and the episode on top stay put; the rest follow this order.'}</p>
    <div class="list">${items
      .map((q) => {
        const ep = lib.episodes[q.episodeId];
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
      <select id="r-limit" name="consecutivePlayLimit">${opt([-1, 1, 2, 3, 4, 5], p.consecutivePlayLimit, (v) => (v === -1 ? 'Use global setting' : v))}</select>
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

function resultRows(list) {
  return `<div class="list">${list
    .map((x) => {
      const have = Object.values(state.lib.podcasts).some((p) => p.unsubscribedAt == null && sameFeed(p.feedUrl, x.feedUrl));
      return `<div class="item"><img src="${art(x.artworkUrl)}" alt="" loading="lazy" />
        <div class="meta"><div class="title">${esc(x.title)}</div><div class="line2">${esc(x.author)}</div></div>
        <div class="actions">${have ? '<span class="tag done">Subscribed</span>' : `<button class="btn small" data-action="subscribe" data-url="${esc(x.feedUrl)}">Subscribe</button>`}</div></div>`;
    })
    .join('')}</div>`;
}

function viewAdd() {
  const r = state.searchResults;
  const pi = hasPodcastIndex();
  const results =
    r === 'loading'
      ? '<p class="sub">Searching…</p>'
      : Array.isArray(r)
        ? r.length
          ? `<p class="sub">From ${esc(state.searchSource)}</p>${resultRows(r)}`
          : '<p class="sub">Nothing found.</p>'
        : `<h2>Featured</h2>${resultRows(FEATURED)}`;
  return `<h1>Add a show</h1>
    <p class="sub">${pi ? 'Searching Podcast Index (Podcasting 2.0 directory), with Apple Podcasts as the backup.' : 'Searching Apple Podcasts. Add your Podcast Index key in Settings to search the Podcasting 2.0 directory instead.'}</p>
    <form class="search" id="searchForm"><input type="search" name="q" placeholder="Search shows" aria-label="Search shows" /><button class="btn">Search</button></form>
    <form class="search" id="urlForm"><input type="text" name="url" placeholder="https://… feed address (RSS)" aria-label="Feed address" /><button class="ghost">Subscribe</button></form>
    ${pi ? `<div class="row-actions">${CATEGORIES.map((c) => `<button class="ghost small" data-action="browse-category" data-cat="${esc(c)}">${esc(c)}</button>`).join('')}</div>` : ''}
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

// "remote:" / "remote:A/B" (rclone) or "/home/me/A" (local) path helpers for the folder browser
function childOf(target, name) {
  if (target.endsWith(':') || target.endsWith('/')) return target + name;
  return `${target}/${name}`;
}
function parentOf(target) {
  if (target.endsWith(':') || target === '/') return null;
  const i = target.lastIndexOf('/');
  if (i >= 0) return i === 0 ? '/' : target.slice(0, i);
  const c = target.indexOf(':');
  return c >= 0 ? target.slice(0, c + 1) : null;
}

async function findSyncFolder() {
  state.finder = { busy: true, results: [] };
  render();
  try {
    const results = await invoke('sync_find', { remote: null });
    state.finder = { busy: false, results };
  } catch (e) {
    state.finder = { busy: false, results: [], error: String(e) };
  }
  if (state.view === 'settings') render();
}

async function browse(target) {
  state.browser = { target, folders: [], hasSyncFile: false, busy: true };
  render();
  try {
    const r = await invoke('sync_browse', { target });
    state.browser = { ...r, busy: false };
  } catch (e) {
    state.browser = { target, folders: [], hasSyncFile: false, busy: false, error: String(e) };
  }
  if (state.view === 'settings') render();
}

function useSyncFolder(target) {
  state.lib.settings.syncFolder = target;
  state.finder = null;
  state.browser = null;
  persist();
  toast(`Sync folder set to ${target}`);
  syncNow();
}

function viewSettings() {
  const s = state.lib.settings;
  const rc = state.rclone;
  const rcLine = !rc
    ? 'Checking for rclone…'
    : rc.installed
      ? `rclone found at <code>${esc(rc.path || 'rclone')}</code> (${esc(rc.version || '')}). Drive connections: ${rc.remotes.length ? rc.remotes.map((r) => `<code>${esc(r)}</code>`).join(' ') : 'none yet. Run <code>rclone config</code> once to connect Google Drive.'}`
      : `<span style="color:var(--danger)">rclone isn't installed.</span> No sudo needed: put the rclone program in <code>~/.local/bin</code> (steps in the desktop README), then run <code>rclone config</code> once to connect Google Drive.`;
  const opt = (vals, cur, fmt = (v) => v) => vals.map((v) => `<option value="${v}" ${Number(cur) === v ? 'selected' : ''}>${fmt(v)}</option>`).join('');
  const f = state.finder;
  const finder = !f
    ? ''
    : f.busy
      ? '<p class="sub">Searching your Drive for eagertolisten_sync.json… a big Drive can take a minute.</p>'
      : f.error
        ? `<p class="sub" style="color:var(--danger)">${esc(f.error)}</p>`
        : f.results.length
          ? `<div class="list" style="margin-bottom:14px">${f.results
              .map(
                (r, i) => `<div class="item" style="grid-template-columns:1fr auto">
              <div class="meta"><div class="title">${esc(r.target)}</div>
                <div class="line2">${i === 0 ? '<span class="tag pin">Most recent</span>' : ''}Last written ${r.modTime ? esc(new Date(r.modTime).toLocaleString()) : 'unknown'}</div></div>
              <div class="actions"><button class="btn small" data-action="use-sync-folder" data-target="${esc(r.target)}">Use this folder</button></div></div>`,
              )
              .join('')}</div>`
          : `<p class="sub">No <code>eagertolisten_sync.json</code> found. Sync once from the phone (Settings → Google Drive Sync → Sync now), then search again, or pick a folder below.</p>`;
  const b = state.browser;
  const browser = !b
    ? ''
    : `<div class="card"><p><strong>${esc(b.target)}</strong>${b.hasSyncFile ? ' <span class="tag done">has the sync file</span>' : ''}</p>
        ${b.error ? `<p style="color:var(--danger)">${esc(b.error)}</p>` : ''}
        <div class="row-actions">
          <button class="btn small" data-action="use-sync-folder" data-target="${esc(b.target)}">Use this folder</button>
          ${parentOf(b.target) ? `<button class="ghost small" data-action="browse" data-target="${esc(parentOf(b.target))}">↑ Up</button>` : ''}
          <button class="ghost small" data-action="browse-close">Close</button>
        </div>
        ${b.busy ? '<p class="sub">Loading…</p>' : `<div class="row-actions">${b.folders
          .map((name) => `<button class="ghost small" data-action="browse" data-target="${esc(childOf(b.target, name))}">📁 ${esc(name)}</button>`)
          .join('') || '<span class="sub">No folders here.</span>'}</div>`}
      </div>`;
  return `<h1>Settings</h1>
    <h2>Phone sync</h2>
    <div class="card">
      <p>The PC reads and writes the same <code>eagertolisten_sync.json</code> your phone keeps in Google Drive, so shows, queue, progress and bookmarks match on both. It needs the folder you picked on the phone, wherever that is.</p>
      <p>${rcLine}</p>
      <div class="row-actions">
        <button class="btn small" data-action="find-sync-folder" ${!rc?.installed || f?.busy ? 'disabled' : ''}>Find my sync folder</button>
        ${rc?.installed ? rc.remotes.map((r) => `<button class="ghost small" data-action="browse" data-target="${esc(r)}">Browse ${esc(r)}</button>`).join('') : ''}
      </div>
    </div>
    ${finder}${browser}
    <form class="form" id="syncForm">
      <label for="s-folder">Sync folder</label><input id="s-folder" type="text" name="syncFolder" value="${esc(s.syncFolder || '')}" autocomplete="off" spellcheck="false" />
      <div class="hint">${s.syncFolder ? 'Set.' : 'Not set yet.'} An rclone folder looks like <code>remote:Path/To/Folder</code>; a folder on this computer is a full path.</div>
      <label for="s-auto">Sync automatically</label><input id="s-auto" type="checkbox" name="autoSync" ${s.autoSync ? 'checked' : ''} />
      <div class="hint">After each episode, when you pause, and every 15 minutes.</div>
      <span></span><div class="row-actions"><button class="btn" type="submit">Save & sync now</button></div>
    </form>
    <p class="sub">Last sync: ${ago(state.lib.lastSyncAt)}${state.syncMessage ? ` · ${esc(state.syncMessage)}` : ''}</p>
    <h2>Curated Airwaves (Radio U): Anti-Binge Rules</h2>
    <p class="sub">Programs your queue like a personal radio station: fair rotation comes before priority, so you aren't stuck bingeing one show. Each show's own rules (priority, play order, limit, skip intro/outro, speed) are on its page under Shows.</p>
    <form class="form" id="radioForm">
      <label for="p-order">Queue order</label><select id="p-order" name="queueSortRule">${Object.entries(L.QUEUE_SORT_RULES)
        .map(([k, label]) => `<option value="${k}" ${(s.queueSortRule || 'PROGRAMMED_FAIR') === k ? 'selected' : ''}>${esc(label)}</option>`)
        .join('')}</select>
      <label for="p-limit">Max episodes in a row per show</label><select id="p-limit" name="globalConsecutiveLimit">${opt([1, 2, 3, 4, 5], s.globalConsecutiveLimit)}</select>
      <div class="hint">How many episodes of one show play in a row before another show gets a turn.</div>
      <label for="p-news">News: keep episodes for</label><select id="p-news" name="defaultNewsDays">${opt([1, 2, 3, 5, 7], s.defaultNewsDays, (v) => `${v} day${v === 1 ? '' : 's'}`)}</select>
      <div class="hint">Default for News shows that don't set their own window.</div>
      <span></span><div><button class="btn" type="submit">Save</button></div>
    </form>
    <h2>Player</h2>
    <form class="form" id="playerForm">
      <label for="p-back">Rewind</label><select id="p-back" name="seekBackSeconds">${opt([10, 15, 30, 45, 60, 90], s.seekBackSeconds, (v) => `${v} seconds`)}</select>
      <label for="p-fwd">Forward</label><select id="p-fwd" name="seekForwardSeconds">${opt([10, 15, 30, 45, 60, 90], s.seekForwardSeconds, (v) => `${v} seconds`)}</select>
      <span></span><div><button class="btn" type="submit">Save</button></div>
    </form>
    <h2>Podcast Index</h2>
    <p class="sub">Search the open Podcasting 2.0 directory. Get a free key at <button class="link" data-action="open-url" data-url="https://api.podcastindex.org/signup">api.podcastindex.org</button>; it stays on this computer and isn't synced. Without it, search uses Apple Podcasts.</p>
    <form class="form" id="piForm">
      <label for="pi-key">API key</label><input id="pi-key" type="text" name="podcastIndexKey" value="${esc(s.podcastIndexKey || '')}" autocomplete="off" spellcheck="false" />
      <label for="pi-secret">API secret</label><input id="pi-secret" type="password" name="podcastIndexSecret" value="${esc(s.podcastIndexSecret || '')}" autocomplete="off" />
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
  if (document.hasFocus()) m.querySelector('button')?.focus();
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
    case 'queue-tab':
      state.queueTab = el.dataset.tab;
      render();
      break;
    case 'browse-category':
      searchDirectory('', { category: el.dataset.cat });
      break;
    case 'find-sync-folder':
      findSyncFolder();
      break;
    case 'browse':
      browse(el.dataset.target);
      break;
    case 'browse-close':
      state.browser = null;
      render();
      break;
    case 'use-sync-folder':
      useSyncFolder(el.dataset.target);
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
  } else if (f.id === 'radioForm') {
    Object.assign(lib.settings, {
      globalConsecutiveLimit: Number(data.globalConsecutiveLimit),
      defaultNewsDays: Number(data.defaultNewsDays),
    });
    L.setSortRule(lib, data.queueSortRule, { nowPlayingId: activeId() });
    persist();
    render();
    toast('Saved');
  } else if (f.id === 'playerForm') {
    Object.assign(lib.settings, {
      seekBackSeconds: Number(data.seekBackSeconds),
      seekForwardSeconds: Number(data.seekForwardSeconds),
    });
    persist();
    render();
    toast('Saved');
  } else if (f.id === 'piForm') {
    lib.settings.podcastIndexKey = (data.podcastIndexKey || '').trim();
    lib.settings.podcastIndexSecret = (data.podcastIndexSecret || '').trim();
    persist();
    render();
    toast(hasPodcastIndex() ? 'Podcast Index key saved' : 'Podcast Index key cleared');
  }
});

document.addEventListener('change', (e) => {
  if (e.target.id === 'queueOrder') {
    L.setSortRule(state.lib, e.target.value, { nowPlayingId: activeId() });
    persist();
    render();
    return;
  }
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
