import { QueueManager } from './queueManager.js';
import { TranscriptParser } from './transcriptParser.js';
import { ChapterParser } from './chapterParser.js';
import { Importer } from './importer.js';
import { DriveSync } from './driveSync.js';
import { runTests } from './tests.js';

// Initialize core components
const qm = new QueueManager();
const driveSync = new DriveSync();
const audio = document.getElementById('native-audio-player');

// State
let subscriptions = JSON.parse(localStorage.getItem('dc_subscriptions') || '[]').map(sub => ({
  ...sub,
  episodes: (sub.episodes || []).filter(e => !e.isLive)
}));
let currentEpisode = JSON.parse(localStorage.getItem('dc_current_episode') || 'null');
if (currentEpisode && currentEpisode.isLive) {
  const lastActive = parseInt(localStorage.getItem('dc_live_last_active') || '0', 10);
  if (lastActive > 0 && (Date.now() - lastActive) > 60 * 60 * 1000) {
    console.log('Previous live session expired (> 1 hour). Reverting saved player state.');
    currentEpisode = null;
    localStorage.removeItem('dc_current_episode');
    localStorage.removeItem('dc_live_last_active');
  }
}
let currentCues = []; // Active episode transcript cues
let activePodcastUrl = ''; // For per-podcast settings modal
let feedRefreshCount = 0;
let autoSyncTimer = null;

// ----------------------------------------------------
// ONE-HANDED MOBILE NOTIFICATIONS & CONFIRMATIONS
// ----------------------------------------------------
let toastTimeout = null;

export function showToast(message, type = 'info', duration = 2600) {
  const container = document.getElementById('toast-container');
  if (!container) return;

  // Clear existing active toast
  const existing = container.querySelector('.toast');
  if (existing) {
    existing.remove();
  }
  if (toastTimeout) {
    clearTimeout(toastTimeout);
    toastTimeout = null;
  }

  // Adjust container position if mini player is active
  const miniPlayer = document.getElementById('mini-player');
  const isMiniPlayerActive = miniPlayer && !miniPlayer.classList.contains('hidden');
  if (isMiniPlayerActive) {
    document.body.classList.add('has-active-player');
  } else {
    document.body.classList.remove('has-active-player');
  }

  const toast = document.createElement('div');
  toast.className = `toast toast-${type}`;
  
  let iconName = 'info';
  if (type === 'success') iconName = 'check';
  else if (type === 'warning') iconName = 'alert-triangle';
  else if (type === 'error') iconName = 'alert-circle';

  toast.innerHTML = `
    <span class="toast-icon"><i data-lucide="${iconName}"></i></span>
    <span class="toast-text">${message}</span>
  `;

  // Tap to dismiss immediately (one-handed convenience)
  toast.addEventListener('click', () => {
    toast.classList.remove('toast-visible');
    toast.classList.add('toast-hiding');
    setTimeout(() => {
      if (toast.parentNode) toast.remove();
    }, 200);
  });

  container.appendChild(toast);
  lucide.createIcons();

  // Force reflow for smooth animation
  void toast.offsetWidth;
  toast.classList.add('toast-visible');

  toastTimeout = setTimeout(() => {
    toast.classList.remove('toast-visible');
    toast.classList.add('toast-hiding');
    setTimeout(() => {
      if (toast.parentNode) toast.remove();
    }, 220);
  }, duration);
}

export function showConfirmSheet({
  title = 'Confirm Action',
  message = 'Are you sure you want to proceed?',
  confirmText = 'Confirm',
  cancelText = 'Cancel',
  isDanger = false,
  icon = 'help-circle',
  onConfirm = null
} = {}) {
  const overlay = document.getElementById('confirm-sheet-modal');
  if (!overlay) {
    if (confirm(message)) {
      if (onConfirm) onConfirm();
    }
    return;
  }

  const titleEl = document.getElementById('confirm-sheet-title');
  const msgEl = document.getElementById('confirm-sheet-message');
  const iconEl = document.getElementById('confirm-sheet-icon');
  const cancelBtn = document.getElementById('confirm-sheet-cancel-btn');
  const acceptBtn = document.getElementById('confirm-sheet-accept-btn');

  if (titleEl) titleEl.textContent = title;
  if (msgEl) msgEl.textContent = message;
  if (iconEl) {
    iconEl.className = `bottom-sheet-icon ${isDanger ? 'danger' : ''}`;
    iconEl.innerHTML = `<i data-lucide="${isDanger ? 'alert-triangle' : icon}"></i>`;
  }
  if (cancelBtn) cancelBtn.textContent = cancelText;
  if (acceptBtn) {
    acceptBtn.textContent = confirmText;
    acceptBtn.className = `btn ${isDanger ? 'btn-danger' : 'btn-primary'} btn-lg`;
  }

  lucide.createIcons();
  overlay.classList.remove('hidden', 'closing');

  const closeSheet = () => {
    overlay.classList.add('closing');
    setTimeout(() => {
      overlay.classList.add('hidden');
      overlay.classList.remove('closing');
    }, 200);
  };

  const handleCancel = (e) => {
    e.stopPropagation();
    closeSheet();
    cleanup();
  };

  const handleAccept = (e) => {
    e.stopPropagation();
    closeSheet();
    cleanup();
    if (onConfirm) onConfirm();
  };

  const handleOverlayClick = (e) => {
    if (e.target === overlay) {
      closeSheet();
      cleanup();
    }
  };

  function cleanup() {
    cancelBtn?.removeEventListener('click', handleCancel);
    acceptBtn?.removeEventListener('click', handleAccept);
    overlay?.removeEventListener('click', handleOverlayClick);
  }

  cancelBtn?.addEventListener('click', handleCancel);
  acceptBtn?.addEventListener('click', handleAccept);
  overlay?.addEventListener('click', handleOverlayClick);
}

export function updateSubscribeButtonStates() {
  // 1. Curated buttons in Discover tab
  document.querySelectorAll('.subscribe-curated-btn').forEach(btn => {
    const feedUrl = btn.getAttribute('data-url');
    if (!feedUrl) return;
    const isSubbed = subscriptions.some(s => s.url === feedUrl);
    if (isSubbed) {
      btn.classList.add('btn-outline', 'is-subscribed');
      btn.classList.remove('btn-primary');
      btn.innerHTML = `<i data-lucide="check" style="width: 13px; height: 13px;"></i> Subscribed`;
    } else {
      btn.classList.remove('btn-outline', 'is-subscribed');
      btn.classList.add('btn-primary');
      btn.innerHTML = `<i data-lucide="plus" style="width: 13px; height: 13px;"></i> Subscribe`;
    }
  });

  // 2. Sample button on My Feeds empty state
  const sampleBtn = document.getElementById('subscribe-sample-btn');
  if (sampleBtn) {
    const isSampleSubbed = subscriptions.some(s => s.url === '/sample-feed.xml');
    if (isSampleSubbed) {
      sampleBtn.classList.add('btn-outline', 'is-subscribed');
      sampleBtn.classList.remove('btn-primary');
      sampleBtn.innerHTML = `<i data-lucide="check" style="width: 14px; height: 14px;"></i> Subscribed to Sample`;
    } else {
      sampleBtn.classList.remove('btn-outline', 'is-subscribed');
      sampleBtn.classList.add('btn-primary');
      sampleBtn.innerHTML = `Subscribe to Sample Podcast`;
    }
  }

  // 3. Search result buttons
  document.querySelectorAll('.sub-search-btn').forEach(btn => {
    const feedUrl = btn.getAttribute('data-url');
    if (!feedUrl) return;
    const isSubbed = subscriptions.some(s => s.url === feedUrl);
    if (isSubbed) {
      btn.classList.add('btn-outline', 'is-subscribed');
      btn.classList.remove('btn-primary');
      btn.innerHTML = `<i data-lucide="check" style="width: 12px; height: 12px;"></i> Subscribed`;
    } else {
      btn.classList.remove('btn-outline', 'is-subscribed');
      btn.classList.add('btn-primary');
      btn.innerHTML = `<i data-lucide="plus" style="width: 12px; height: 12px;"></i> Subscribe`;
    }
  });

  lucide.createIcons();
}

// Initialize App
document.addEventListener('DOMContentLoaded', () => {
  initApp();
});

function initApp() {
  setupNavigation();
  setupEventListeners();
  renderQueue();
  renderSubscriptions();
  updateSubscribeButtonStates();
  setupAudioPlayer();
  setupDriveSync();
  
  if (currentEpisode) {
    loadEpisodeState(currentEpisode);
  }
  
  // Reload icons
  lucide.createIcons();
}

// ----------------------------------------------------
// UI NAVIGATION & TAB ROUTING
// ----------------------------------------------------
function setupNavigation() {
  const navItems = document.querySelectorAll('.nav-item');
  const panels = document.querySelectorAll('.tab-panel');

  navItems.forEach(item => {
    item.addEventListener('click', () => {
      const tabId = item.getAttribute('data-tab');
      
      navItems.forEach(n => n.classList.remove('active'));
      panels.forEach(p => p.classList.remove('active'));
      
      item.classList.add('active');
      const targetPanel = document.getElementById(tabId);
      if (targetPanel) targetPanel.classList.add('active');
      
      // Hide podcast details overlay when navigating tabs
      if (tabId !== 'tab-subscriptions') {
        document.getElementById('podcast-detail-view').classList.add('hidden');
      }

      updateSubscribeButtonStates();
      lucide.createIcons();
    });
  });
}

// ----------------------------------------------------
// EVENT LISTENERS
// ----------------------------------------------------
function setupEventListeners() {
  // Discover Feed Search
  document.getElementById('search-btn').addEventListener('click', performSearch);
  document.getElementById('search-input').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') performSearch();
  });

  // Manual RSS Add
  document.getElementById('manual-rss-btn').addEventListener('click', handleManualRssAdd);

  // Global Queue Settings
  const autoPlayNextCheck = document.getElementById('setting-auto-play-next');
  const autoRemoveCheck = document.getElementById('setting-auto-remove');

  autoPlayNextCheck.checked = qm.settings.autoPlayNext;
  autoRemoveCheck.checked = qm.settings.autoRemovePlayed;

  autoPlayNextCheck.addEventListener('change', () => qm.toggleAutoPlayNext());
  autoRemoveCheck.addEventListener('change', () => qm.toggleAutoRemovePlayed());

  // Queue Sort selector
  const queueSortSelect = document.getElementById('queue-sort-select');
  queueSortSelect.value = qm.settings.sortOrder;
  queueSortSelect.addEventListener('change', (e) => {
    qm.setSortOrder(e.target.value);
    renderQueue();
  });

  // Clear Queue
  document.getElementById('clear-queue-btn').addEventListener('click', () => {
    showConfirmSheet({
      title: 'Clear Play Queue',
      message: 'Are you sure you want to remove all episodes from the queue?',
      confirmText: 'Clear Queue',
      isDanger: true,
      icon: 'trash-2',
      onConfirm: () => {
        qm.clearQueue();
        renderQueue();
        showToast('Play queue cleared', 'info');
      }
    });
  });

  // Subscriptions detail close
  document.getElementById('close-detail-btn').addEventListener('click', () => {
    document.getElementById('podcast-detail-view').classList.add('hidden');
  });

  // Unsubscribe btn
  document.getElementById('unsubscribe-btn').addEventListener('click', handleUnsubscribe);

  // Quick refresh btn
  document.getElementById('quick-refresh-btn').addEventListener('click', refreshAllFeeds);

  // Subscribe to sample button (on Subscriptions page empty state)
  document.getElementById('subscribe-sample-btn').addEventListener('click', (e) => {
    const isSubbed = subscriptions.some(s => s.url === '/sample-feed.xml');
    if (isSubbed) {
      showToast('Already subscribed to sample podcast', 'info');
      return;
    }
    subscribeToFeed('/sample-feed.xml', e.currentTarget);
  });

  // Curated podcast subscriptions
  document.querySelectorAll('.subscribe-curated-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const feedUrl = e.currentTarget.getAttribute('data-url');
      const isSubbed = subscriptions.some(s => s.url === feedUrl);
      if (isSubbed) {
        const p = subscriptions.find(s => s.url === feedUrl);
        showToast(`Already subscribed to ${p ? p.title : 'this podcast'}`, 'info');
        return;
      }
      subscribeToFeed(feedUrl, e.currentTarget);
    });
  });

  // Per-Podcast Settings Modal actions & Auto-Save
  document.getElementById('podcast-settings-btn').addEventListener('click', openPodcastSettingsModal);
  document.getElementById('close-modal-btn').addEventListener('click', closePodcastSettingsModal);
  document.getElementById('save-podcast-settings-btn').addEventListener('click', savePodcastSettings);
  document.getElementById('podcast-priority-select')?.addEventListener('change', autoSavePodcastSettings);
  document.getElementById('podcast-limit-select')?.addEventListener('change', autoSavePodcastSettings);
  document.getElementById('podcast-autoadd-checkbox')?.addEventListener('change', autoSavePodcastSettings);
  document.getElementById('podcast-custom-donation-input')?.addEventListener('input', autoSavePodcastSettings);

  // Boost Modal actions & Quick Sats Chips
  document.getElementById('player-boost-btn')?.addEventListener('click', () => openBoostModal());
  document.getElementById('notes-boost-btn')?.addEventListener('click', () => openBoostModal());
  document.getElementById('close-boost-modal-btn')?.addEventListener('click', closeBoostModal);
  document.getElementById('send-boost-btn')?.addEventListener('click', sendBoost);
  document.getElementById('copy-boost-details-btn')?.addEventListener('click', copyBoostDetails);

  document.querySelectorAll('.boost-chip-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      document.querySelectorAll('.boost-chip-btn').forEach(b => {
        b.classList.remove('active');
        b.style.background = '';
        b.style.color = '';
        b.style.borderColor = '';
      });
      const chip = e.currentTarget;
      chip.classList.add('active');
      chip.style.background = 'var(--color-burnt-orange)';
      chip.style.color = '#fff';
      chip.style.borderColor = 'var(--color-burnt-orange)';
      const sats = chip.getAttribute('data-sats');
      const amtInput = document.getElementById('boost-amount-input');
      if (amtInput) amtInput.value = sats;
    });
  });

  // Run Unit Tests
  document.getElementById('run-tests-btn').addEventListener('click', handleRunTests);

  // Migration: OPML file upload
  document.getElementById('import-opml-input').addEventListener('change', handleOpmlImport);
  
  // Migration: SQLite DB upload
  document.getElementById('import-db-input').addEventListener('change', handleDbImport);
}

function isNativePlatform() {
  return typeof window !== 'undefined' && 
         ((window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform()) ||
          window.location.protocol === 'capacitor:' ||
          (window.location.protocol === 'https:' && window.location.hostname === 'localhost' && !window.location.port));
}

// ----------------------------------------------------
// PODCAST RSS INGESTION & PARSING
// ----------------------------------------------------
async function fetchRssFeed(url) {
  const isLocal = url.startsWith('/') || url.startsWith('http://localhost') || url.startsWith('https://localhost');
  
  // On native Android client, fetch directly (no local Node server required)
  if (isNativePlatform() && !isLocal) {
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    return await response.text();
  }

  // On desktop web browser, route via local CORS proxy if needed
  const targetUrl = isLocal ? url : `/api/proxy?url=${encodeURIComponent(url)}`;
  const response = await fetch(targetUrl);
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`);
  }
  const text = await response.text();
  return text;
}

export function parseChaptersFromDescription(description) {
  return ChapterParser.parseFromDescription(description);
}

function parsePodcastValue(element) {
  if (!element) return null;
  let valNode = null;
  const nodes = element.getElementsByTagName('podcast:value');
  if (nodes.length > 0) valNode = nodes[0];
  else {
    for (const ch of element.children) {
      if (ch.localName === 'value') { valNode = ch; break; }
    }
  }
  if (!valNode) return null;

  const type = valNode.getAttribute('type') || 'lightning';
  const method = valNode.getAttribute('method') || 'keysend';
  const suggested = parseFloat(valNode.getAttribute('suggested')) || null;
  const recipients = [];

  let rNodes = Array.from(valNode.getElementsByTagName('podcast:valueRecipient'));
  if (rNodes.length === 0) {
    for (const ch of valNode.children) {
      if (ch.localName === 'valueRecipient') rNodes.push(ch);
    }
  }

  rNodes.forEach(r => {
    const address = r.getAttribute('address') || '';
    if (address) {
      recipients.push({
        name: r.getAttribute('name') || 'Recipient',
        type: r.getAttribute('type') || 'node',
        address,
        split: parseInt(r.getAttribute('split'), 10) || 1,
        customKey: r.getAttribute('customKey') || null,
        customValue: r.getAttribute('customValue') || null,
        fee: r.getAttribute('fee') === 'true'
      });
    }
  });

  return { type, method, suggested, recipients };
}

function parseRssXml(xmlText, feedUrl) {
  const parser = new DOMParser();
  const doc = parser.parseFromString(xmlText, 'text/xml');
  
  const channel = doc.querySelector('channel');
  if (!channel) throw new Error('Invalid RSS feed structure');

  const title = channel.querySelector('title')?.textContent || 'Untitled Podcast';
  
  // Author parsing
  let author = channel.querySelector('author')?.textContent || '';
  if (!author) {
    // Try itunes namespace
    const itunesAuthor = channel.getElementsByTagName('itunes:author');
    if (itunesAuthor.length > 0) author = itunesAuthor[0].textContent;
  }
  if (!author) author = 'Unknown Author';

  // Description parsing
  const description = channel.querySelector('description')?.textContent || '';

  // Image parsing
  let image = '';
  const itunesImage = channel.getElementsByTagName('itunes:image');
  if (itunesImage.length > 0) {
    image = itunesImage[0].getAttribute('href');
  } else {
    image = channel.querySelector('image url')?.textContent || '';
  }
  if (!image) image = 'https://images.unsplash.com/photo-1590602847861-f357a9332bbc?q=80&w=300&h=300&fit=crop';

  // Podcasting 2.0 Funding tag parsing
  let channelFundingUrl = '';
  let channelFundingMessage = '';
  const fundingNodes = channel.getElementsByTagName('podcast:funding');
  if (fundingNodes.length > 0) {
    channelFundingUrl = fundingNodes[0].getAttribute('url') || '';
    channelFundingMessage = fundingNodes[0].textContent.trim();
  } else {
    for (const ch of channel.children) {
      if (ch.localName === 'funding') {
        channelFundingUrl = ch.getAttribute('url') || '';
        channelFundingMessage = ch.textContent.trim();
        break;
      }
    }
  }

  // Podcasting 2.0 Value (V4V Lightning)
  const channelValue = parsePodcastValue(channel);

  const episodes = [];
  let activeLiveItem = null;

  // Podcasting 2.0 Live Streams parsing (<podcast:liveItem status="live|pending|ended">)
  const liveItemNodes = Array.from(channel.getElementsByTagName('podcast:liveItem'));
  if (liveItemNodes.length === 0) {
    for (const child of channel.children) {
      if (child.localName === 'liveItem') {
        liveItemNodes.push(child);
      }
    }
  }

  for (const item of liveItemNodes) {
    const rawStatus = (item.getAttribute('status') || '').trim().toLowerCase();
    // Podcasting 2.0 Live Beacon Spec: status must strictly equal "live"
    if (rawStatus !== 'live') continue;

    // Validate active time window (start and end timestamps)
    const startAttr = item.getAttribute('start');
    const endAttr = item.getAttribute('end');
    const now = Date.now();

    if (startAttr) {
      const startTime = new Date(startAttr).getTime();
      if (!isNaN(startTime) && now < startTime) {
        continue; // Scheduled in future, not live yet
      }
    }
    if (endAttr) {
      const endTime = new Date(endAttr).getTime();
      if (!isNaN(endTime) && now > endTime) {
        continue; // Scheduled window has ended
      }
    }

    const epTitle = item.querySelector('title')?.textContent || 'Live Broadcast';
    const epDesc = item.querySelector('description')?.textContent || '';
    const pubDate = item.querySelector('pubDate')?.textContent || new Date().toISOString();
    
    let audioUrl = item.querySelector('enclosure')?.getAttribute('url') || '';
    if (!audioUrl) {
      const contentLinks = item.getElementsByTagName('podcast:contentLink');
      if (contentLinks.length > 0) {
        audioUrl = contentLinks[0].getAttribute('href') || '';
      }
    }

    if (audioUrl) {
      const guid = item.querySelector('guid')?.textContent || audioUrl;
      const chapters = parseChaptersFromDescription(epDesc);
      activeLiveItem = {
        id: `${feedUrl}_live_${guid}`,
        podcastUrl: feedUrl,
        podcastTitle: title,
        coverArt: image,
        title: `🔴 [LIVE] ${epTitle}`,
        description: epDesc.replace(/<[^>]*>/g, '').trim(),
        rawDescription: epDesc,
        pubDate,
        audioUrl,
        duration: 'Live',
        isLive: true,
        liveStatus: 'live',
        transcriptUrl: '',
        transcriptType: '',
        chapters,
        fundingUrl: channelFundingUrl,
        value: channelValue
      };
      break; // Found legit active live beacon
    }
  }

  // Episodes parsing
  const items = channel.querySelectorAll('item');

  items.forEach(item => {
    const epTitle = item.querySelector('title')?.textContent || 'Untitled Episode';
    const epDesc = item.querySelector('description')?.textContent || '';
    const pubDate = item.querySelector('pubDate')?.textContent || new Date().toISOString();
    
    // Audio enclosure
    const enclosure = item.querySelector('enclosure');
    const audioUrl = enclosure ? enclosure.getAttribute('url') : '';
    
    let duration = '0:00';
    const itunesDuration = item.getElementsByTagName('itunes:duration');
    if (itunesDuration.length > 0) {
      duration = itunesDuration[0].textContent;
    }

    let guid = item.querySelector('guid')?.textContent || audioUrl;

    // Podcast 2.0 Transcript tag parsing
    let transcriptUrl = '';
    let transcriptType = '';
    const transcriptNodes = item.getElementsByTagName('podcast:transcript');
    
    if (transcriptNodes.length > 0) {
      transcriptUrl = transcriptNodes[0].getAttribute('url');
      transcriptType = transcriptNodes[0].getAttribute('type') || '';
    } else {
      for (const child of item.children) {
        if (child.localName === 'transcript') {
          transcriptUrl = child.getAttribute('url');
          transcriptType = child.getAttribute('type') || '';
          break;
        }
      }
    }

    // Standardize transcript file type relative to Express proxy or local server
    if (transcriptUrl && !transcriptUrl.startsWith('http') && !transcriptUrl.startsWith('/')) {
      try {
        const base = new URL(feedUrl);
        transcriptUrl = new URL(transcriptUrl, base).href;
      } catch (e) {}
    }

    // Podcast 2.0 Item Funding & Value
    let itemFundingUrl = '';
    const itemFundingNodes = item.getElementsByTagName('podcast:funding');
    if (itemFundingNodes.length > 0) {
      itemFundingUrl = itemFundingNodes[0].getAttribute('url') || '';
    } else {
      for (const ch of item.children) {
        if (ch.localName === 'funding') {
          itemFundingUrl = ch.getAttribute('url') || '';
          break;
        }
      }
    }

    const itemValue = parsePodcastValue(item) || channelValue;

    // Parse chapters from description
    const chapters = parseChaptersFromDescription(epDesc);

    if (audioUrl) {
      episodes.push({
        id: `${feedUrl}_${guid}`,
        title: epTitle,
        description: epDesc.replace(/<[^>]*>/g, '').trim(), // Clean HTML descriptions
        rawDescription: epDesc,
        pubDate,
        audioUrl,
        duration,
        transcriptUrl,
        transcriptType,
        chapters,
        fundingUrl: itemFundingUrl || channelFundingUrl,
        value: itemValue
      });
    }
  });

  return {
    title,
    url: feedUrl,
    image,
    author,
    description: description.replace(/<[^>]*>/g, '').trim(),
    fundingUrl: channelFundingUrl,
    fundingMessage: channelFundingMessage,
    value: channelValue,
    liveItem: activeLiveItem,
    episodes
  };
}

async function subscribeToFeed(url, triggerBtn = null) {
  const existing = subscriptions.find(s => s.url === url);
  if (existing) {
    showToast(`Already subscribed to ${existing.title || 'this podcast'}`, 'info');
    updateSubscribeButtonStates();
    return false;
  }

  const quickRefreshBtn = document.getElementById('quick-refresh-btn');
  if (quickRefreshBtn) quickRefreshBtn.classList.add('spin');

  if (triggerBtn) {
    triggerBtn.disabled = true;
    triggerBtn.innerHTML = `<i data-lucide="loader" class="spin" style="width: 13px; height: 13px;"></i> Subscribing...`;
    lucide.createIcons();
  }

  try {
    const xml = await fetchRssFeed(url);
    const podcast = parseRssXml(xml, url);
    
    subscriptions.push(podcast);
    saveSubscriptions();
    renderSubscriptions();
    updateSubscribeButtonStates();
    
    // Auto-enqueue initial episodes based on podcast limits (default is keep unlimited / auto-add latest)
    const settings = qm.getPodcastSettings(url);
    if (settings.autoAdd && podcast.episodes.length > 0) {
      // Enqueue the most recent episode
      enqueueEpisode(podcast.episodes[0], podcast);
    }
    
    showToast(`Subscribed to: ${podcast.title}`, 'success');
    scheduleAutoSync(1500);
    return true;
  } catch (err) {
    console.error('Failed to subscribe:', err);
    showToast(`Could not subscribe to feed: ${err.message}`, 'error');
    updateSubscribeButtonStates();
    return false;
  } finally {
    if (quickRefreshBtn) quickRefreshBtn.classList.remove('spin');
    if (triggerBtn) {
      triggerBtn.disabled = false;
      updateSubscribeButtonStates();
    }
  }
}

function saveSubscriptions() {
  localStorage.setItem('dc_subscriptions', JSON.stringify(subscriptions));
}

async function refreshAllFeeds() {
  if (subscriptions.length === 0) {
    showToast('No podcast subscriptions to refresh', 'info');
    return;
  }
  
  const quickRefreshBtn = document.getElementById('quick-refresh-btn');
  if (quickRefreshBtn) quickRefreshBtn.classList.add('spin');

  let addedCount = 0;

  for (let i = 0; i < subscriptions.length; i++) {
    const currentSub = subscriptions[i];
    try {
      const xml = await fetchRssFeed(currentSub.url);
      const updatedSub = parseRssXml(xml, currentSub.url);
      
      // Update episodes & details
      subscriptions[i] = {
        ...currentSub,
        ...updatedSub
      };
      
      // Check for new episodes that aren't in local storage yet
      const currentGuids = new Set(currentSub.episodes.map(e => e.id));
      const newEpisodes = updatedSub.episodes.filter(e => !currentGuids.has(e.id));
      
      const settings = qm.getPodcastSettings(currentSub.url);
      if (settings.autoAdd) {
        // Add new episodes (newest first, so we reverse it to queue in chronological order if multiple)
        [...newEpisodes].reverse().forEach(ep => {
          const success = enqueueEpisode(ep, updatedSub);
          if (success) addedCount++;
        });
      }
    } catch (err) {
      console.error(`Failed to refresh feed ${currentSub.title}:`, err);
    }
  }

  saveSubscriptions();
  renderSubscriptions();
  renderQueue();
  updateSubscribeButtonStates();
  
  if (quickRefreshBtn) quickRefreshBtn.classList.remove('spin');
  if (addedCount > 0) {
    showToast(`Refreshed! Added ${addedCount} new episode${addedCount > 1 ? 's' : ''} to queue`, 'success');
  } else {
    showToast('All feeds up to date (no new episodes)', 'info');
  }
}

// ----------------------------------------------------
// PLAY QUEUE STATE SYNCS
// ----------------------------------------------------
function enqueueEpisode(ep, podcast) {
  const pSettings = qm.getPodcastSettings(podcast.url);
  const queueItem = {
    id: ep.id,
    podcastUrl: podcast.url,
    podcastTitle: podcast.title,
    podcastPriority: pSettings.priority,
    coverArt: podcast.image,
    title: ep.title,
    audioUrl: ep.audioUrl,
    pubDate: ep.pubDate,
    durationText: ep.duration,
    duration: 0, // Will be computed on load
    progress: 0,
    played: false,
    transcriptUrl: ep.transcriptUrl,
    transcriptType: ep.transcriptType
  };
  
  const added = qm.addEpisode(queueItem);
  if (added) {
    renderQueue();
  }
  return added;
}

// ----------------------------------------------------
// UI RENDERING
// ----------------------------------------------------

// Render Tab 1: Play Queue
function renderQueue() {
  const queueList = document.getElementById('queue-list');
  const emptyState = document.getElementById('queue-empty-state');
  
  queueList.innerHTML = '';

  if (qm.queue.length === 0) {
    emptyState.classList.remove('hidden');
    return;
  } else {
    emptyState.classList.add('hidden');
  }

  qm.queue.forEach((item, index) => {
    const card = document.createElement('div');
    card.className = `queue-item ${currentEpisode && currentEpisode.id === item.id ? 'active' : ''}`;
    card.setAttribute('data-id', item.id);
    
    // Convert priority to name
    const pNames = ['Highest', 'High', 'Medium', 'Low', 'Lowest'];
    const pName = pNames[item.podcastPriority - 1] || 'Medium';

    card.innerHTML = `
      <div class="drag-handle" title="Shift Item">
        <i data-lucide="menu"></i>
      </div>
      <img src="${item.coverArt}" alt="Podcast Cover" class="queue-item-image">
      <div class="queue-item-details">
        <h4>${item.title}</h4>
        <div class="podcast-meta">
          <span>${item.podcastTitle}</span>
          <span class="priority-badge priority-${item.podcastPriority}">${pName}</span>
        </div>
      </div>
      <div class="queue-item-actions">
        ${qm.settings.sortOrder === 'manual' ? `
          <button class="icon-btn move-up-btn" ${index === 0 ? 'disabled' : ''} title="Move Up">
            <i data-lucide="chevron-up"></i>
          </button>
          <button class="icon-btn move-down-btn" ${index === qm.queue.length - 1 ? 'disabled' : ''} title="Move Down">
            <i data-lucide="chevron-down"></i>
          </button>
        ` : ''}
        <button class="icon-btn play-btn" title="Play Now">
          <i data-lucide="play"></i>
        </button>
        <button class="icon-btn remove-btn danger" title="Remove from Queue">
          <i data-lucide="x"></i>
        </button>
      </div>
    `;

    // Bind event actions
    card.querySelector('.play-btn').addEventListener('click', () => playEpisodeDirectly(item));
    card.querySelector('.remove-btn').addEventListener('click', () => {
      qm.removeEpisode(item.id);
      renderQueue();
    });

    if (qm.settings.sortOrder === 'manual') {
      card.querySelector('.move-up-btn').addEventListener('click', () => {
        if (index > 0) shiftQueueItem(index, index - 1);
      });
      card.querySelector('.move-down-btn').addEventListener('click', () => {
        if (index < qm.queue.length - 1) shiftQueueItem(index, index + 1);
      });
    }

    queueList.appendChild(card);
  });

  lucide.createIcons();
}

function shiftQueueItem(from, to) {
  const newQueue = [...qm.queue];
  const item = newQueue.splice(from, 1)[0];
  newQueue.splice(to, 0, item);
  qm.setQueueOrder(newQueue);
  renderQueue();
}

// Render Tab 2: Subscriptions Grid
function renderSubscriptions() {
  const grid = document.getElementById('subscriptions-grid');
  const emptyState = document.getElementById('subs-empty-state');

  grid.innerHTML = '';

  if (subscriptions.length === 0) {
    emptyState.classList.remove('hidden');
    return;
  } else {
    emptyState.classList.add('hidden');
  }

  subscriptions.forEach(sub => {
    const card = document.createElement('div');
    card.className = 'podcast-card';
    
    const settings = qm.getPodcastSettings(sub.url);

    card.innerHTML = `
      <img src="${sub.image}" alt="${sub.title}" class="podcast-card-img" onerror="this.src='https://images.unsplash.com/photo-1590602847861-f357a9332bbc?q=80&w=150&h=150&fit=crop'">
      <h4>${sub.title}</h4>
      <div class="podcast-priority-dot p-dot-${settings.priority}" title="Priority: ${settings.priority}"></div>
    `;

    card.addEventListener('click', () => openPodcastDetail(sub));
    grid.appendChild(card);
  });
}

// Open Single Podcast Detail View
function openPodcastDetail(podcast) {
  activePodcastUrl = podcast.url;
  
  document.getElementById('detail-podcast-title').textContent = podcast.title;
  document.getElementById('detail-podcast-image').src = podcast.image;
  document.getElementById('detail-podcast-author').textContent = podcast.author || 'Unknown';
  document.getElementById('detail-podcast-desc').textContent = podcast.description || '';

  const settings = qm.getPodcastSettings(podcast.url);
  const supportUrl = settings?.customDonationUrl || podcast.fundingUrl;
  const detailSupportBtn = document.getElementById('detail-support-btn');
  const detailBoostBtn = document.getElementById('detail-boost-btn');

  if (supportUrl) {
    if (detailSupportBtn) {
      detailSupportBtn.href = supportUrl;
      detailSupportBtn.classList.remove('hidden');
    }
  } else {
    if (detailSupportBtn) detailSupportBtn.classList.add('hidden');
  }

  if (podcast.value && podcast.value.recipients && podcast.value.recipients.length > 0) {
    if (detailBoostBtn) {
      detailBoostBtn.classList.remove('hidden');
      detailBoostBtn.onclick = () => openBoostModal(podcast, null);
    }
  } else {
    if (detailBoostBtn) detailBoostBtn.classList.add('hidden');
  }

  const listContainer = document.getElementById('detail-episode-list');
  listContainer.innerHTML = '';

  // Podcasting 2.0 Live Broadcast Banner (strictly when live beacon is active)
  if (podcast.liveItem) {
    const liveCard = document.createElement('div');
    liveCard.className = 'episode-row live-episode-banner';
    liveCard.style.border = '2px solid #ef4444';
    liveCard.style.background = 'rgba(239, 68, 68, 0.1)';
    liveCard.innerHTML = `
      <div class="episode-row-header">
        <h5>${podcast.liveItem.title}</h5>
        <span class="badge badge-danger" style="background:#ef4444; color:#fff; font-weight:bold; padding:2px 8px; border-radius:4px;">LIVE NOW</span>
      </div>
      <p class="episode-row-desc">${podcast.liveItem.description || 'Live stream broadcast is currently on air.'}</p>
      <div class="episode-row-actions">
        <div class="ep-badges">
          <span class="badge badge-danger">Live Beacon Active</span>
        </div>
        <div class="ep-action-btns">
          <button class="btn btn-sm btn-primary play-live-btn" style="background:#ef4444; border-color:#ef4444; color:#fff;">
            <i data-lucide="radio"></i> Tune In Live
          </button>
        </div>
      </div>
    `;
    const playLiveBtn = liveCard.querySelector('.play-live-btn');
    playLiveBtn.onclick = () => {
      playEpisodeDirectly(podcast.liveItem);
    };
    listContainer.appendChild(liveCard);
  }

  podcast.episodes.forEach(ep => {
    const row = document.createElement('div');
    row.className = 'episode-row';
    
    const isQueued = qm.queue.some(q => q.id === ep.id);
    const dateStr = new Date(ep.pubDate).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });

    row.innerHTML = `
      <div class="episode-row-header">
        <h5>${ep.title}</h5>
        <span class="episode-row-time">${dateStr}</span>
      </div>
      <p class="episode-row-desc">${ep.description}</p>
      <div class="episode-row-actions">
        <div class="ep-badges">
          <span class="badge">${ep.duration}</span>
          ${ep.transcriptUrl ? '<span class="badge badge-transcript">Transcript 2.0</span>' : ''}
        </div>
        <div class="ep-action-btns">
          <button class="btn btn-outline btn-sm play-now-btn">
            <i data-lucide="play" style="width: 14px; height: 14px;"></i> Play
          </button>
          <button class="btn btn-primary btn-sm enqueue-btn" ${isQueued ? 'disabled' : ''}>
            <i data-lucide="${isQueued ? 'check' : 'plus'}" style="width: 14px; height: 14px;"></i> 
            ${isQueued ? 'Queued' : 'Queue'}
          </button>
        </div>
      </div>
    `;

    row.querySelector('.play-now-btn').addEventListener('click', () => {
      // Play immediately (inserts into queue first)
      enqueueEpisode(ep, podcast);
      const queueItem = qm.queue.find(q => q.id === ep.id);
      playEpisodeDirectly(queueItem);
    });

    row.querySelector('.enqueue-btn').addEventListener('click', (e) => {
      const success = enqueueEpisode(ep, podcast);
      if (success) {
        e.target.setAttribute('disabled', 'true');
        e.target.innerHTML = `<i data-lucide="check" style="width: 14px; height: 14px;"></i> Queued`;
        lucide.createIcons();
      }
    });

    listContainer.appendChild(row);
  });

  document.getElementById('podcast-detail-view').classList.remove('hidden');
  lucide.createIcons();
}

function handleUnsubscribe() {
  if (!activePodcastUrl) return;
  const p = subscriptions.find(s => s.url === activePodcastUrl);
  if (!p) return;

  showConfirmSheet({
    title: 'Unsubscribe',
    message: `Are you sure you want to unsubscribe from "${p.title}"?`,
    confirmText: 'Unsubscribe',
    isDanger: true,
    icon: 'user-minus',
    onConfirm: () => {
      // 1. Remove subscription
      subscriptions = subscriptions.filter(s => s.url !== activePodcastUrl);
      saveSubscriptions();
      renderSubscriptions();
      updateSubscribeButtonStates();
      
      // 2. Remove associated episodes from active queue
      qm.queue.filter(q => q.podcastUrl === activePodcastUrl).forEach(q => {
        qm.removeEpisode(q.id);
      });
      renderQueue();

      document.getElementById('podcast-detail-view').classList.add('hidden');
      showToast(`Unsubscribed from ${p.title}`, 'info');
      scheduleAutoSync(1500);
    }
  });
}

// ----------------------------------------------------
// PODCAST SEARCH & DISCOVER (iTunes API)
// ----------------------------------------------------
async function performSearch() {
  const query = document.getElementById('search-input').value.trim();
  if (!query) return;

  const btn = document.getElementById('search-btn');
  btn.innerHTML = `<i data-lucide="loader" class="spin" style="width: 16px; height: 16px;"></i> Searching...`;
  lucide.createIcons();

  const resultsSection = document.getElementById('search-results-section');
  const resultsGrid = document.getElementById('search-results-grid');
  
  resultsGrid.innerHTML = '';
  resultsSection.classList.remove('hidden');

  try {
    const url = `https://itunes.apple.com/search?media=podcast&term=${encodeURIComponent(query)}&limit=15`;
    const response = await fetch(url);
    if (!response.ok) throw new Error('Search failed');
    const data = await response.json();

    if (!data.results || data.results.length === 0) {
      resultsGrid.innerHTML = `<div class="empty-state"><p>No podcasts found matching "${query}".</p></div>`;
      return;
    }

    data.results.forEach(item => {
      const card = document.createElement('div');
      card.className = 'search-result-card';
      
      const isSubbed = subscriptions.some(s => s.url === item.feedUrl);

      card.innerHTML = `
        <img src="${item.artworkUrl100}" alt="Art" onerror="this.src='https://images.unsplash.com/photo-1590602847861-f357a9332bbc?q=80&w=150&h=150&fit=crop'">
        <div class="search-result-info">
          <div>
            <h4>${item.collectionName}</h4>
            <p class="artist">${item.artistName}</p>
          </div>
          <button class="btn ${isSubbed ? 'btn-outline is-subscribed' : 'btn-primary'} btn-sm sub-search-btn" data-url="${item.feedUrl}">
            <i data-lucide="${isSubbed ? 'check' : 'plus'}" style="width: 12px; height: 12px;"></i>
            ${isSubbed ? 'Subscribed' : 'Subscribe'}
          </button>
        </div>
      `;

      card.querySelector('.sub-search-btn').addEventListener('click', async (e) => {
        const btnEl = e.currentTarget;
        if (subscriptions.some(s => s.url === item.feedUrl)) {
          showToast(`Already subscribed to ${item.collectionName}`, 'info');
          return;
        }
        await subscribeToFeed(item.feedUrl, btnEl);
      });

      resultsGrid.appendChild(card);
    });

  } catch (err) {
    console.error('Search error:', err);
    resultsGrid.innerHTML = `<div class="empty-state danger"><p>Error searching. Check your connection.</p></div>`;
  } finally {
    btn.innerHTML = 'Search';
    lucide.createIcons();
  }
}

function handleManualRssAdd() {
  const input = document.getElementById('manual-rss-input');
  const url = input.value.trim();
  if (!url) {
    showToast('Please enter an RSS feed URL', 'warning');
    return;
  }
  subscribeToFeed(url);
  input.value = '';
}

// ----------------------------------------------------
// PODCAST SETTINGS MODAL & AUTO-SAVE
// ----------------------------------------------------
function openPodcastSettingsModal() {
  if (!activePodcastUrl) return;
  
  const podcast = subscriptions.find(s => s.url === activePodcastUrl);
  if (!podcast) return;

  document.getElementById('settings-modal-podcast-title').textContent = podcast.title;
  
  const settings = qm.getPodcastSettings(activePodcastUrl);
  document.getElementById('podcast-priority-select').value = settings.priority;
  document.getElementById('podcast-limit-select').value = settings.maxEpisodes;
  document.getElementById('podcast-autoadd-checkbox').checked = settings.autoAdd;

  const customDonationInput = document.getElementById('podcast-custom-donation-input');
  if (customDonationInput) {
    customDonationInput.value = settings.customDonationUrl || '';
  }

  document.getElementById('podcast-settings-modal').classList.remove('hidden');
}

function autoSavePodcastSettings() {
  if (!activePodcastUrl) return;

  const priority = parseInt(document.getElementById('podcast-priority-select').value, 10);
  const maxEpisodes = document.getElementById('podcast-limit-select').value;
  const autoAdd = document.getElementById('podcast-autoadd-checkbox').checked;
  const customDonationUrl = document.getElementById('podcast-custom-donation-input')?.value.trim() || '';

  qm.updatePodcastSettings(activePodcastUrl, {
    priority,
    maxEpisodes,
    autoAdd,
    customDonationUrl
  });

  renderSubscriptions();
  renderQueue();
  scheduleAutoSync(1500);
}

function closePodcastSettingsModal() {
  autoSavePodcastSettings();
  document.getElementById('podcast-settings-modal').classList.add('hidden');
}

function savePodcastSettings() {
  autoSavePodcastSettings();
  closePodcastSettingsModal();
  showToast('Settings saved & queue re-sorted', 'success');
}

// ----------------------------------------------------
// PODCASTING 2.0 VALUE FOR VALUE (V4V) BOOST MODAL
// ----------------------------------------------------
let activeBoostData = null;
let activeBoostPodcast = null;
let activeBoostEpisode = null;

function openBoostModal(podcast = null, episode = null) {
  activeBoostPodcast = podcast || (currentEpisode ? subscriptions.find(s => s.url === currentEpisode.podcastUrl) : null);
  activeBoostEpisode = episode || currentEpisode;
  activeBoostData = activeBoostEpisode?.value || activeBoostPodcast?.value;

  if (!activeBoostData || !activeBoostData.recipients || activeBoostData.recipients.length === 0) {
    showToast('No Podcasting 2.0 Lightning Value data configured for this show', 'warning');
    return;
  }

  const modal = document.getElementById('boost-modal');
  if (!modal) return;

  document.getElementById('boost-modal-podcast-title').textContent = activeBoostPodcast?.title || 'Podcast';
  document.getElementById('boost-modal-episode-title').textContent = activeBoostEpisode?.title || 'Episode';

  const defaultSats = activeBoostData.suggested || 1000;
  const amountInput = document.getElementById('boost-amount-input');
  if (amountInput) amountInput.value = defaultSats;

  // Render recipients breakdown
  const recipientsContainer = document.getElementById('boost-recipients-breakdown');
  if (recipientsContainer) {
    const totalSplit = activeBoostData.recipients.reduce((sum, r) => sum + (r.split || 1), 0) || 1;
    let html = `<div style="font-weight: 600; margin-bottom: 6px; color: var(--color-burnt-orange);">Recipients (${activeBoostData.recipients.length})</div>`;
    activeBoostData.recipients.forEach(r => {
      const pct = Math.round((r.split / totalSplit) * 100);
      const shortAddr = r.address.length > 24 ? r.address.substring(0, 10) + '...' + r.address.substring(r.address.length - 8) : r.address;
      html += `
        <div style="display: flex; justify-content: space-between; padding: 3px 0; border-bottom: 1px solid rgba(255,255,255,0.05); font-size: 0.78rem;">
          <span style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 75%;">${r.name} <span style="color: var(--text-muted); font-size: 0.72rem;">(${shortAddr})</span></span>
          <span style="font-weight: bold; color: var(--color-burnt-orange);">${pct}%${r.fee ? ' (fee)' : ''}</span>
        </div>
      `;
    });
    recipientsContainer.innerHTML = html;
  }

  modal.classList.remove('hidden');
}

function closeBoostModal() {
  const modal = document.getElementById('boost-modal');
  if (modal) modal.classList.add('hidden');
}

async function sendBoost() {
  if (!activeBoostData || !activeBoostData.recipients || activeBoostData.recipients.length === 0) return;

  const sats = parseInt(document.getElementById('boost-amount-input').value, 10) || 1000;
  const message = document.getElementById('boost-message-input')?.value.trim() || '';
  const sender = document.getElementById('boost-sender-input')?.value.trim() || '';
  const primary = activeBoostData.recipients.find(r => !r.fee) || activeBoostData.recipients[0];

  const fullMsg = sender ? `${message} (from: ${sender})` : message;
  const encodedMsg = encodeURIComponent(fullMsg);

  // WebLN check
  if (window.webln) {
    try {
      await window.webln.enable();
      if (primary.address && !primary.address.includes('@')) {
        await window.webln.keysend({
          destination: primary.address,
          amount: sats,
          customRecords: {
            '7629169': JSON.stringify({
              podcast: activeBoostPodcast?.title,
              episode: activeBoostEpisode?.title,
              message: fullMsg,
              sender_name: sender,
              app_name: 'EagerToListen'
            })
          }
        });
        showToast(`⚡ Boost of ${sats.toLocaleString()} sats sent via WebLN!`, 'success');
        closeBoostModal();
        return;
      }
    } catch (e) {
      console.log('WebLN failed or rejected, falling back to lightning URI:', e);
    }
  }

  // Construct Lightning Intent URI
  let uri = '';
  if (primary.address.includes('@')) {
    const msats = sats * 1000;
    uri = `lightning:${primary.address}?amount=${msats}${message ? `&comment=${encodedMsg}` : ''}`;
  } else {
    uri = `lightning:${primary.address}?amount=${sats}${message ? `&message=${encodedMsg}` : ''}`;
  }

  try {
    window.location.href = uri;
    showToast(`⚡ Opening Lightning wallet for ${sats.toLocaleString()} sats...`, 'info');
  } catch (err) {
    copyBoostDetails();
  }
  closeBoostModal();
}

function copyBoostDetails() {
  if (!activeBoostData) return;
  const sats = parseInt(document.getElementById('boost-amount-input').value, 10) || 1000;
  const message = document.getElementById('boost-message-input')?.value.trim() || '';
  const sender = document.getElementById('boost-sender-input')?.value.trim() || '';
  const primary = activeBoostData.recipients.find(r => !r.fee) || activeBoostData.recipients[0];

  const details = [
    `⚡ Podcasting 2.0 Boost Details`,
    `Podcast: ${activeBoostPodcast?.title || ''}`,
    `Episode: ${activeBoostEpisode?.title || ''}`,
    `Amount: ${sats} satoshis`,
    `Recipient: ${primary?.name} (${primary?.address})`,
    message ? `Message: ${message}` : '',
    sender ? `Sender: ${sender}` : ''
  ].filter(Boolean).join('\n');

  navigator.clipboard.writeText(details).then(() => {
    showToast('Boost details copied to clipboard!', 'success');
  }).catch(() => {
    showToast('Copied to clipboard', 'info');
  });
}

// ----------------------------------------------------
// AUDIO PLAYER LOGIC
// ----------------------------------------------------
function setupAudioPlayer() {
  const playBtns = document.querySelectorAll('.play-pause-btn');
  const speedSlider = document.getElementById('playback-speed-slider');
  const speedDisplay = document.getElementById('speed-display');
  const progressSlider = document.getElementById('player-progress-slider');
  const volumeSlider = document.getElementById('player-volume-slider');
  
  const elapsedLabel = document.getElementById('player-time-elapsed');
  const remainingLabel = document.getElementById('player-time-remaining');
  
  const miniProgress = document.getElementById('mini-progress-fill');
  
  // Expand full player
  document.getElementById('mini-player-expand-trigger').addEventListener('click', expandFullPlayer);
  document.getElementById('close-player-btn').addEventListener('click', collapseFullPlayer);
  
  // Toggle covers & transcript panels
  const toggleModeBtn = document.getElementById('toggle-player-mode-btn');
  if (toggleModeBtn) {
    toggleModeBtn.addEventListener('click', togglePlayerViewMode);
  }

  // 3-view switcher: Cover Art, Episode Notes & Chapters, Transcript
  document.getElementById('player-view-cover-btn')?.addEventListener('click', () => switchPlayerPane('cover'));
  document.getElementById('player-view-notes-btn')?.addEventListener('click', () => switchPlayerPane('notes'));
  document.getElementById('player-view-transcript-btn')?.addEventListener('click', () => switchPlayerPane('transcript'));

  // Play / Pause event
  playBtns.forEach(btn => {
    btn.addEventListener('click', togglePlayback);
  });

  // Native audio binding updates
  audio.addEventListener('play', () => {
    playBtns.forEach(btn => {
      btn.innerHTML = `<i data-lucide="pause"></i>`;
    });
    lucide.createIcons();
    updateMiniPlayerUI();
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = 'playing';
      updateMediaSessionPosition();
    }
  });

  audio.addEventListener('pause', () => {
    playBtns.forEach(btn => {
      btn.innerHTML = `<i data-lucide="play"></i>`;
    });
    lucide.createIcons();
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = 'paused';
    }
    scheduleAutoSync(2500);
  });

  audio.addEventListener('timeupdate', () => {
    if (currentEpisode && currentEpisode.isLive) {
      localStorage.setItem('dc_live_last_active', Date.now().toString());
    }
    if (!audio.duration) return;
    
    const curr = audio.currentTime;
    const dur = audio.duration;
    const pct = (curr / dur) * 100;
    
    // Update sliders
    progressSlider.value = pct;
    miniProgress.style.width = `${pct}%`;
    
    // Update labels
    elapsedLabel.textContent = formatTime(curr);
    remainingLabel.textContent = `-${formatTime(dur - curr)}`;

    // Sync state
    if (currentEpisode) {
      qm.updateProgress(currentEpisode.id, curr, dur);
      // Persist playback progress with timestamp for cross-device sync
      localStorage.setItem(`dc_prog_${currentEpisode.audioUrl}`, JSON.stringify({
        progress: curr,
        duration: dur,
        played: dur && (curr / dur >= 0.99 || curr >= dur - 2),
        updatedAt: Date.now()
      }));
    }

    // Sync transcripts highlight
    syncTranscriptHighlight(curr);

    // Sync Android lock screen / notification position
    updateMediaSessionPosition();
  });

  audio.addEventListener('durationchange', () => {
    if (currentEpisode && audio.duration) {
      currentEpisode.duration = audio.duration;
      localStorage.setItem('dc_current_episode', JSON.stringify(currentEpisode));
      updateMediaSessionPosition();
    }
  });

  audio.addEventListener('ended', handlePlaybackEnded);

  audio.addEventListener('error', (e) => {
    console.warn('Audio playback error encountered:', e);
    if (currentEpisode && currentEpisode.isLive) {
      console.log('Live stream ended or connection lost. Reverting to playlist queue.');
      handlePlaybackEnded();
    }
  });

  // Scrub bar
  progressSlider.addEventListener('input', (e) => {
    if (!audio.duration) return;
    const targetTime = (e.target.value / 100) * audio.duration;
    audio.currentTime = targetTime;
    updateMediaSessionPosition();
  });

  // Speed slider
  speedSlider.addEventListener('input', (e) => {
    const val = parseFloat(e.target.value);
    audio.playbackRate = val;
    speedDisplay.textContent = `${val.toFixed(1)}x`;
    updateMediaSessionPosition();
  });

  // Volume slider
  volumeSlider.addEventListener('input', (e) => {
    audio.volume = parseFloat(e.target.value);
  });

  // Backward 15s (DoggCatcher / Podcast standard)
  document.getElementById('player-skip-back-btn').addEventListener('click', () => {
    audio.currentTime = Math.max(0, audio.currentTime - 15);
    updateMediaSessionPosition();
  });

  // Forward 90s (Skip intro / ads standard)
  document.getElementById('player-skip-forward-btn').addEventListener('click', () => {
    if (audio.duration && (audio.currentTime + 90 >= audio.duration - 1.5)) {
      handlePlaybackEnded();
    } else {
      audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + 90);
      updateMediaSessionPosition();
    }
  });

  // Transcript Search input
  const searchInput = document.getElementById('transcript-search');
  searchInput.addEventListener('input', (e) => {
    filterTranscript(e.target.value);
  });
}

function formatTime(secs) {
  if (isNaN(secs)) return '0:00';
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = Math.floor(secs % 60);
  
  if (h > 0) {
    return `${h}:${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
  }
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

function togglePlayback() {
  if (!audio.src) return;
  
  if (audio.paused) {
    if (currentEpisode && currentEpisode.isLive) {
      const lastActive = parseInt(localStorage.getItem('dc_live_last_active') || '0', 10);
      if (lastActive > 0 && (Date.now() - lastActive) > 60 * 60 * 1000) {
        console.log('Live station paused for > 1 hour. Reverting to playlist queue.');
        localStorage.removeItem('dc_live_last_active');
        if (qm.queue.length > 0) {
          playEpisodeDirectly(qm.queue[0]);
          return;
        }
      }
    }
    audio.play().catch(err => console.error('Play request failed:', err));
  } else {
    audio.pause();
  }
}

function playEpisodeDirectly(queueItem) {
  if (!queueItem) return;

  currentEpisode = queueItem;
  localStorage.setItem('dc_current_episode', JSON.stringify(currentEpisode));
  
  if (queueItem.isLive) {
    localStorage.setItem('dc_live_last_active', Date.now().toString());
  } else {
    localStorage.removeItem('dc_live_last_active');
  }

  loadEpisodeState(queueItem);
  audio.play().catch(err => console.error('Playback failed:', err));

  // Expand player overlay
  expandFullPlayer();
}

function loadEpisodeState(item) {
  document.getElementById('mini-player').classList.remove('hidden');
  document.body.classList.add('has-active-player');
  
  // Update Mini Player labels
  document.getElementById('mini-player-cover').src = item.coverArt;
  document.getElementById('mini-player-title').textContent = item.title;
  document.getElementById('mini-player-podcast').textContent = item.podcastTitle;

  // Update Full Player labels
  document.getElementById('player-cover-art').src = item.coverArt;
  document.getElementById('player-episode-title').textContent = item.title;
  document.getElementById('player-podcast-title').textContent = item.podcastTitle;
  
  // Live Stream Badge
  const liveBadge = document.getElementById('player-live-badge');
  if (liveBadge) {
    if (item.isLive) {
      liveBadge.classList.remove('hidden');
      liveBadge.textContent = item.liveStatus === 'pending' ? '⏳ UPCOMING LIVE STREAM' : '● LIVE STREAM';
    } else {
      liveBadge.classList.add('hidden');
    }
  }

  // Creator Support & Boost Buttons
  const sub = subscriptions.find(s => s.url === item.podcastUrl);
  const settings = qm.getPodcastSettings(item.podcastUrl);
  const supportUrl = settings?.customDonationUrl || item.fundingUrl || sub?.fundingUrl || '';
  const valueData = item.value || sub?.value;

  const playerSupportBtn = document.getElementById('player-support-btn');
  const notesSupportBtn = document.getElementById('notes-support-btn');
  const playerBoostBtn = document.getElementById('player-boost-btn');
  const notesBoostBtn = document.getElementById('notes-boost-btn');
  const notesDonationContainer = document.getElementById('player-notes-donation-actions');

  if (supportUrl) {
    if (playerSupportBtn) {
      playerSupportBtn.href = supportUrl;
      playerSupportBtn.classList.remove('hidden');
    }
    if (notesSupportBtn) {
      notesSupportBtn.href = supportUrl;
      notesSupportBtn.classList.remove('hidden');
    }
  } else {
    if (playerSupportBtn) playerSupportBtn.classList.add('hidden');
    if (notesSupportBtn) notesSupportBtn.classList.add('hidden');
  }

  if (valueData && valueData.recipients && valueData.recipients.length > 0) {
    if (playerBoostBtn) playerBoostBtn.classList.remove('hidden');
    if (notesBoostBtn) notesBoostBtn.classList.remove('hidden');
  } else {
    if (playerBoostBtn) playerBoostBtn.classList.add('hidden');
    if (notesBoostBtn) notesBoostBtn.classList.add('hidden');
  }

  if (notesDonationContainer) {
    if (supportUrl || (valueData && valueData.recipients && valueData.recipients.length > 0)) {
      notesDonationContainer.classList.remove('hidden');
    } else {
      notesDonationContainer.classList.add('hidden');
    }
  }

  // Populate Episode Notes & Chapters
  renderEpisodeNotes(item);

  // Set audio source
  const isDiffSource = audio.getAttribute('data-id') !== item.id;
  if (isDiffSource) {
    audio.src = item.audioUrl;
    audio.setAttribute('data-id', item.id);
    audio.currentTime = item.progress || 0;
    
    // Set speed rate
    const currentSpeed = parseFloat(document.getElementById('playback-speed-slider').value);
    audio.playbackRate = currentSpeed;

    // Load Transcript
    loadTranscript(item);
  }

  setupMediaSession(item);
  updateMiniPlayerUI();
  renderQueue(); // Re-render to highlight active queue item
}

function updateMiniPlayerUI() {
  const miniPlayBtn = document.getElementById('mini-play-btn');
  if (audio.paused) {
    miniPlayBtn.innerHTML = `<i data-lucide="play"></i>`;
  } else {
    miniPlayBtn.innerHTML = `<i data-lucide="pause"></i>`;
  }
  lucide.createIcons();
}

// ----------------------------------------------------
// NATIVE ANDROID MEDIASESSION (LOCK SCREEN & NOTIFICATION)
// ----------------------------------------------------
function setupMediaSession(item) {
  if (!('mediaSession' in navigator) || !item) return;

  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: item.title || 'Playing Episode',
      artist: item.podcastTitle || 'Eager to Listen',
      album: 'Eager to Listen',
      artwork: [
        { src: item.coverArt || '', sizes: '96x96', type: 'image/jpeg' },
        { src: item.coverArt || '', sizes: '128x128', type: 'image/jpeg' },
        { src: item.coverArt || '', sizes: '192x192', type: 'image/jpeg' },
        { src: item.coverArt || '', sizes: '256x256', type: 'image/jpeg' },
        { src: item.coverArt || '', sizes: '384x384', type: 'image/jpeg' },
        { src: item.coverArt || '', sizes: '512x512', type: 'image/jpeg' }
      ]
    });

    navigator.mediaSession.setActionHandler('play', () => {
      audio.play().catch(e => console.error(e));
    });

    navigator.mediaSession.setActionHandler('pause', () => {
      audio.pause();
    });

    // 15-second rewind
    navigator.mediaSession.setActionHandler('seekbackward', (details) => {
      const skipSecs = details.seekOffset || 15;
      audio.currentTime = Math.max(0, audio.currentTime - skipSecs);
      updateMediaSessionPosition();
    });

    // 90-second forward skip
    navigator.mediaSession.setActionHandler('seekforward', (details) => {
      const skipSecs = details.seekOffset || 90;
      if (audio.duration && (audio.currentTime + skipSecs >= audio.duration - 1.5)) {
        handlePlaybackEnded();
      } else {
        audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + skipSecs);
        updateMediaSessionPosition();
      }
    });

    navigator.mediaSession.setActionHandler('seekto', (details) => {
      if (details.seekTime !== undefined && details.seekTime !== null) {
        audio.currentTime = details.seekTime;
        updateMediaSessionPosition();
      }
    });

    navigator.mediaSession.setActionHandler('previoustrack', () => {
      const skipSecs = 15;
      audio.currentTime = Math.max(0, audio.currentTime - skipSecs);
      updateMediaSessionPosition();
    });

    navigator.mediaSession.setActionHandler('nexttrack', () => {
      // In car audio / Android MediaSession notification:
      // "next" hardware / lock screen action advances 90s through unwanted parts / ads.
      // If repeatedly skipped near or past the end, it naturally concludes the episode and advances.
      const skipSecs = 90;
      if (audio.duration && (audio.currentTime + skipSecs >= audio.duration - 1.5)) {
        handlePlaybackEnded();
      } else {
        audio.currentTime = Math.min(audio.duration || 0, audio.currentTime + skipSecs);
        updateMediaSessionPosition();
      }
    });
  } catch (err) {
    console.warn('MediaSession initialization error:', err);
  }
}

function updateMediaSessionPosition() {
  if (!('mediaSession' in navigator) || !audio.duration || isNaN(audio.duration)) return;
  try {
    navigator.mediaSession.setPositionState({
      duration: audio.duration,
      playbackRate: audio.playbackRate,
      position: audio.currentTime
    });
  } catch (e) {}
}

function handlePlaybackEnded() {
  if (!currentEpisode) return;

  const wasLive = currentEpisode.isLive;
  if (wasLive) {
    localStorage.removeItem('dc_live_last_active');
  }

  // Set current playing progress to 100% to trigger deletion if auto-remove is on
  const result = qm.updateProgress(currentEpisode.id, audio.duration, audio.duration);
  
  // If playing next is supported or reverting from live broadcast
  if ((qm.settings.autoPlayNext || wasLive) && qm.queue.length > 0) {
    // Play next item in queue (which has now shifted to index 0 if the previous was deleted)
    const nextItem = qm.queue[0];
    if (nextItem) {
      playEpisodeDirectly(nextItem);
    } else {
      currentEpisode = null;
      localStorage.removeItem('dc_current_episode');
      document.getElementById('mini-player').classList.add('hidden');
      document.body.classList.remove('has-active-player');
      collapseFullPlayer();
    }
  } else {
    // Reset player
    audio.pause();
    audio.currentTime = 0;
  }

  renderQueue();
  scheduleAutoSync(1000);
}

// 1-Hour Live Inactivity Watchdog: Automatically reverts to playlist queue if paused on live stream
function checkLiveInactivity() {
  if (currentEpisode && currentEpisode.isLive && audio.paused) {
    const lastActive = parseInt(localStorage.getItem('dc_live_last_active') || '0', 10);
    if (lastActive > 0 && (Date.now() - lastActive) >= 60 * 60 * 1000) {
      console.log('Live station paused for > 1 hour. Auto-reverting to playlist queue.');
      localStorage.removeItem('dc_live_last_active');
      if (qm.queue.length > 0) {
        playEpisodeDirectly(qm.queue[0]);
        audio.pause(); // Prime without blasting audio
      }
    }
  }
}
setInterval(checkLiveInactivity, 60000);

function expandFullPlayer() {
  document.getElementById('full-player-overlay').classList.remove('hidden');
}

function collapseFullPlayer() {
  document.getElementById('full-player-overlay').classList.add('hidden');
}

function switchPlayerPane(mode) {
  const panes = {
    cover: document.getElementById('player-cover-view'),
    notes: document.getElementById('player-notes-view'),
    transcript: document.getElementById('player-transcript-view')
  };
  const btns = {
    cover: document.getElementById('player-view-cover-btn'),
    notes: document.getElementById('player-view-notes-btn'),
    transcript: document.getElementById('player-view-transcript-btn')
  };

  Object.keys(panes).forEach(k => {
    if (panes[k]) {
      if (k === mode) panes[k].classList.add('active');
      else panes[k].classList.remove('active');
    }
    if (btns[k]) {
      if (k === mode) btns[k].classList.add('active');
      else btns[k].classList.remove('active');
    }
  });
}

function renderEpisodeNotes(episode) {
  const container = document.getElementById('player-notes-content');
  if (!container || !episode) return;

  let html = '';

  // Chapters (pre-parsed or extracted from description)
  const chapters = (episode.chapters && episode.chapters.length > 0)
    ? episode.chapters
    : parseChaptersFromDescription(episode.rawDescription || episode.description);

  if (chapters && chapters.length > 0) {
    html += `
      <div class="notes-chapters-card" style="margin-bottom: 20px; padding: 12px; background: rgba(255,255,255,0.04); border-radius: 8px; border: 1px solid rgba(255,255,255,0.08);">
        <h4 style="font-size: 0.95rem; font-weight: 600; color: #fff; margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between;">
          <span><i data-lucide="list" style="width: 15px; height: 15px; vertical-align: middle; margin-right: 6px; color: var(--accent-orange, #EB5E17);"></i>Chapters (${chapters.length})</span>
        </h4>
        <div class="notes-chapters-list" style="display: flex; flex-direction: column; gap: 4px;">
    `;

    chapters.forEach(ch => {
      const timeStr = formatTime(ch.startTime);
      html += `
        <button class="chapter-seek-btn" data-start="${ch.startTime}" style="display: flex; justify-content: space-between; align-items: center; padding: 6px 8px; border-radius: 6px; background: transparent; border: none; color: #ddd; text-align: left; cursor: pointer; transition: background 0.15s ease;">
          <span style="font-size: 0.85rem; font-weight: 500;">${ch.title}</span>
          <span style="color: var(--accent-orange, #EB5E17); font-family: monospace; font-size: 0.8rem; margin-left: 8px; flex-shrink: 0;">${timeStr}</span>
        </button>
      `;
    });

    html += `
        </div>
      </div>
    `;
  }

  // Description / Shownotes
  const desc = episode.rawDescription || episode.description || 'No description available.';
  html += `
    <div class="notes-description-body" style="color: #bbb; line-height: 1.6; font-size: 0.9rem;">
      ${desc}
    </div>
  `;

  container.innerHTML = html;
  lucide.createIcons();

  container.querySelectorAll('.chapter-seek-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const start = parseFloat(btn.getAttribute('data-start')) || 0;
      audio.currentTime = start;
      if (audio.paused) {
        audio.play().catch(e => console.error(e));
      }
      showToast(`Jumped to chapter at ${formatTime(start)}`, 'info', 1500);
    });
  });
}

function togglePlayerViewMode() {
  const coverView = document.getElementById('player-cover-view');
  const transcriptView = document.getElementById('player-transcript-view');
  const modeBtn = document.getElementById('toggle-player-mode-btn');
  if (!modeBtn) return;

  if (coverView.classList.contains('active')) {
    coverView.classList.remove('active');
    transcriptView.classList.add('active');
    modeBtn.innerHTML = `<i data-lucide="image"></i>`;
  } else {
    transcriptView.classList.remove('active');
    coverView.classList.add('active');
    modeBtn.innerHTML = `<i data-lucide="file-text"></i>`;
  }
  lucide.createIcons();
}

// ----------------------------------------------------
// TRANSCRIPT LOADING, SEARCH & AUTO-SCROLL
// ----------------------------------------------------
async function loadTranscript(episode) {
  const container = document.getElementById('transcript-content');
  container.innerHTML = `<div class="transcript-empty-state"><i data-lucide="loader" class="spin"></i><p>Loading transcript...</p></div>`;
  lucide.createIcons();

  currentCues = [];

  if (!episode.transcriptUrl) {
    renderNoTranscriptState();
    return;
  }

  try {
    // Load file content (direct on native Android, proxy on desktop web)
    const isLocal = episode.transcriptUrl.startsWith('/') || episode.transcriptUrl.startsWith('http://localhost') || episode.transcriptUrl.startsWith('https://localhost');
    const fetchUrl = (isNativePlatform() && !isLocal)
      ? episode.transcriptUrl
      : (isLocal ? episode.transcriptUrl : `/api/proxy?url=${encodeURIComponent(episode.transcriptUrl)}`);
    
    const response = await fetch(fetchUrl);
    if (!response.ok) throw new Error('Failed to load transcript file');
    
    const content = await response.text();

    let durationSec = audio.duration || 0;
    if (!durationSec && episode.duration) {
      const parts = String(episode.duration).split(':').map(Number);
      if (parts.length === 3) durationSec = parts[0] * 3600 + parts[1] * 60 + parts[2];
      else if (parts.length === 2) durationSec = parts[0] * 60 + parts[1];
      else durationSec = Number(episode.duration) || 0;
    }

    currentCues = TranscriptParser.parse(content, episode.transcriptType || '', durationSec);

    if (currentCues.length === 0) {
      renderNoTranscriptState();
      return;
    }

    renderTranscriptCues();
  } catch (err) {
    console.error('Failed to parse transcript:', err);
    renderNoTranscriptState('Error Loading Transcript');
  }
}

function renderNoTranscriptState(title = 'No transcript available') {
  const container = document.getElementById('transcript-content');
  container.innerHTML = `
    <div class="transcript-empty-state">
      <i data-lucide="file-warning" class="empty-icon"></i>
      <p>${title}</p>
      <p class="subtitle">Podcast 2.0 transcript links were not found, or failed to parse.</p>
    </div>
  `;
  lucide.createIcons();
}

function renderTranscriptCues() {
  const container = document.getElementById('transcript-content');
  container.innerHTML = '';

  currentCues.forEach((cue, index) => {
    const cueDiv = document.createElement('div');
    cueDiv.className = 'transcript-cue';
    cueDiv.setAttribute('data-index', index);
    
    const timeStr = formatTime(cue.start);
    let speakerHtml = '';
    if (cue.speaker) {
      speakerHtml = `<span class="cue-speaker" style="color: var(--accent-orange, #EB5E17); font-weight: 600; margin-right: 6px;">${cue.speaker}:</span>`;
    }
    
    cueDiv.innerHTML = `
      <span class="cue-time">${timeStr}</span>
      <span class="cue-text">${speakerHtml}${cue.text}</span>
    `;

    // Click to seek
    cueDiv.addEventListener('click', () => {
      audio.currentTime = cue.start;
      if (audio.paused) {
        audio.play().catch(e => console.error(e));
      }
    });

    container.appendChild(cueDiv);
  });
}

function syncTranscriptHighlight(time) {
  if (currentCues.length === 0) return;

  // Find currently active cue
  let activeIndex = -1;
  for (let i = 0; i < currentCues.length; i++) {
    if (time >= currentCues[i].start && time <= currentCues[i].end) {
      activeIndex = i;
      break;
    }
  }

  // Fallback: if between cues, highlight the closest previous one
  if (activeIndex === -1) {
    for (let i = currentCues.length - 1; i >= 0; i--) {
      if (time >= currentCues[i].start) {
        activeIndex = i;
        break;
      }
    }
  }

  if (activeIndex !== -1) {
    const cuesElements = document.querySelectorAll('.transcript-cue');
    cuesElements.forEach((el, idx) => {
      if (idx === activeIndex) {
        if (!el.classList.contains('active')) {
          el.classList.add('active');
          
          // Auto-scroll
          const autoScroll = document.getElementById('transcript-auto-scroll').checked;
          if (autoScroll) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
          }
        }
      } else {
        el.classList.remove('active');
      }
    });
  }
}

function filterTranscript(query) {
  const cleanQuery = query.trim().toLowerCase();
  const cuesElements = document.querySelectorAll('.transcript-cue');

  cuesElements.forEach((el, idx) => {
    const cue = currentCues[idx];
    const textNode = el.querySelector('.cue-text');
    const originalText = cue.text;

    if (!cleanQuery) {
      el.classList.remove('filtered-out');
      textNode.innerHTML = originalText;
      return;
    }

    if (originalText.toLowerCase().includes(cleanQuery)) {
      el.classList.remove('filtered-out');
      
      // Highlight matching keyword
      const regex = new RegExp(`(${escapeRegExp(cleanQuery)})`, 'gi');
      textNode.innerHTML = originalText.replace(regex, '<mark>$1</mark>');
    } else {
      el.classList.add('filtered-out');
    }
  });
}

function escapeRegExp(string) {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// ----------------------------------------------------
// SYSTEM DIAGNOSTICS (TEST RUNNER)
// ----------------------------------------------------
function handleRunTests() {
  const container = document.getElementById('test-results-container');
  container.innerHTML = '<p>Running client tests...</p>';
  container.classList.remove('hidden');

  setTimeout(() => {
    const report = runTests();
    
    container.innerHTML = `
      <div class="test-summary ${report.passed === report.total ? 'test-pass' : 'test-fail'}">
        Tests Summary: Passed ${report.passed}/${report.total}
      </div>
      <div class="test-rows">
        ${report.results.map(r => `
          <div class="test-row">
            <span>${r.name}</span>
            <span class="${r.success ? 'test-pass' : 'test-fail'}">
              ${r.success ? 'PASS' : `FAIL (${r.message})`}
            </span>
          </div>
        `).join('')}
      </div>
    `;
  }, 100);
}

// ----------------------------------------------------
// MIGRATIONS (OPML & PODCAST ADDICT IMPORT)
// ----------------------------------------------------
function handleOpmlImport(e) {
  const file = e.target.files[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = async (event) => {
    try {
      const xmlText = event.target.result;
      const feeds = Importer.parseOPML(xmlText);
      
      if (feeds.length === 0) {
        showToast('No podcast subscriptions found in OPML file', 'warning');
        return;
      }

      showConfirmSheet({
        title: 'Import OPML Feeds',
        message: `Found ${feeds.length} podcast subscriptions in this OPML file. Would you like to subscribe to them now?`,
        confirmText: `Subscribe to ${feeds.length} Feeds`,
        icon: 'folder-input',
        onConfirm: async () => {
          let successCount = 0;
          for (const feed of feeds) {
            try {
              await subscribeToFeed(feed.url);
              successCount++;
            } catch (err) {
              console.error(`Failed to subscribe to import feed ${feed.title}:`, err);
            }
          }
          showToast(`Imported ${successCount}/${feeds.length} podcasts successfully`, 'success');
        }
      });
    } catch (err) {
      showToast(`OPML Import failed: ${err.message}`, 'error');
    }
  };
  
  reader.readAsText(file);
  e.target.value = ''; // Reset file input
}

function handleDbImport(e) {
  const file = e.target.files[0];
  if (!file) return;

  const fileLabel = e.target.closest('.file-upload-btn');
  const originalHtml = fileLabel.innerHTML;
  fileLabel.innerHTML = `<i data-lucide="loader" class="spin"></i> Parsing Database...`;
  lucide.createIcons();

  const reader = new FileReader();
  reader.onload = async (event) => {
    try {
      const arrayBuffer = event.target.result;
      
      // Parse DB using client-side WebAssembly SQLite (sql.js)
      const data = await Importer.parsePodcastAddictDb(arrayBuffer);
      
      if (data.podcasts.length === 0) {
        showToast('No subscribed podcasts found in the backup file database', 'warning');
        fileLabel.innerHTML = originalHtml;
        lucide.createIcons();
        return;
      }

      const confirmMsg = `Found ${data.podcasts.length} podcasts and ${data.progress.length} playback progress records. Import them now?`;
      showConfirmSheet({
        title: 'Import Podcast Addict Backup',
        message: confirmMsg,
        confirmText: 'Import Subscriptions & Progress',
        icon: 'database',
        onConfirm: async () => {
          let subCount = 0;
          for (const feed of data.podcasts) {
            try {
              if (!subscriptions.some(s => s.url === feed.url)) {
                const xml = await fetchRssFeed(feed.url);
                const podcast = parseRssXml(xml, feed.url);
                subscriptions.push(podcast);
                subCount++;
              }
            } catch (err) {
              console.error(`Failed to subscribe to import feed ${feed.name}:`, err);
            }
          }
          
          saveSubscriptions();
          renderSubscriptions();
          updateSubscribeButtonStates();

          let progressSynced = 0;
          data.progress.forEach(item => {
            const queueItem = qm.queue.find(q => q.audioUrl === item.audioUrl);
            if (queueItem) {
              qm.updateProgress(queueItem.id, item.progress, item.duration);
              progressSynced++;
            }
            localStorage.setItem(`dc_prog_${item.audioUrl}`, JSON.stringify(item));
          });

          showToast(`Imported ${subCount} podcasts & synced ${progressSynced} episode positions`, 'success');
          renderQueue();
        }
      });
    } catch (err) {
      showToast(`Database migration failed: ${err.message}`, 'error');
    } finally {
      fileLabel.innerHTML = originalHtml;
      lucide.createIcons();
    }
  };

  reader.readAsArrayBuffer(file);
  e.target.value = ''; // Reset file input
}

// ----------------------------------------------------
// GOOGLE DRIVE CLOUD SYNC CONTROLLER
// ----------------------------------------------------
function scheduleAutoSync(delay = 2500) {
  if (!driveSync.isAuthenticated()) return;
  if (autoSyncTimer) clearTimeout(autoSyncTimer);
  autoSyncTimer = setTimeout(() => {
    executeDriveSync(true); // background silent sync
  }, delay);
}

function setupDriveSync() {
  const loginBtn = document.getElementById('drive-login-btn');
  const logoutBtn = document.getElementById('drive-logout-btn');
  const syncNowBtn = document.getElementById('drive-sync-now-btn');
  const saveClientIdBtn = document.getElementById('save-drive-client-id-btn');
  const clientIdInput = document.getElementById('drive-client-id-input');

  if (clientIdInput) {
    clientIdInput.value = driveSync.clientId || '';
  }

  driveSync.onAuthSuccess = () => {
    updateDriveSyncUI();
    executeDriveSync(false);
  };

  driveSync.onAuthError = (err) => {
    updateDriveSyncUI('error', `Auth Error: ${err}`);
  };

  if (loginBtn) {
    loginBtn.addEventListener('click', () => {
      try {
        driveSync.requestLogin();
      } catch (err) {
        showToast(err.message, 'error');
      }
    });
  }

  if (logoutBtn) {
    logoutBtn.addEventListener('click', () => {
      showConfirmSheet({
        title: 'Disconnect Google Drive',
        message: 'Disconnect Google Drive cloud sync? Your local subscriptions and queue will remain intact.',
        confirmText: 'Disconnect',
        isDanger: true,
        icon: 'log-out',
        onConfirm: () => {
          driveSync.logout();
          updateDriveSyncUI();
          showToast('Google Drive disconnected', 'info');
        }
      });
    });
  }

  if (syncNowBtn) {
    syncNowBtn.addEventListener('click', () => {
      executeDriveSync(false);
    });
  }

  if (saveClientIdBtn && clientIdInput) {
    saveClientIdBtn.addEventListener('click', () => {
      const val = clientIdInput.value.trim();
      driveSync.setClientId(val);
      showToast('Google OAuth Client ID saved', 'success');
    });
  }

  updateDriveSyncUI();

  // If already authenticated on launch, run initial sync in background
  if (driveSync.isAuthenticated()) {
    setTimeout(() => {
      executeDriveSync(true);
    }, 1500);
  }
}

async function executeDriveSync(silent = false) {
  if (!driveSync.isAuthenticated()) return;

  const dot = document.getElementById('drive-status-dot');
  const text = document.getElementById('drive-status-text');
  const syncBtn = document.getElementById('drive-sync-now-btn');

  if (dot) dot.className = 'status-dot syncing';
  if (text) text.textContent = 'Syncing with Google Drive...';
  if (syncBtn) {
    syncBtn.setAttribute('disabled', 'true');
    syncBtn.innerHTML = `<i data-lucide="loader" class="spin" style="width: 14px; height: 14px;"></i> Syncing...`;
    lucide.createIcons();
  }

  try {
    const result = await driveSync.sync(subscriptions, qm);
    
    // If subscriptions were merged or updated from remote, re-sync in-memory state
    if (result.applyResult?.subscriptionsUpdated) {
      subscriptions = JSON.parse(localStorage.getItem('dc_subscriptions') || '[]');
      renderSubscriptions();
      updateSubscribeButtonStates();
    }
    if (result.applyResult?.queueUpdated) {
      renderQueue();
    }

    updateDriveSyncUI('connected', driveSync.userEmail || 'Connected');
    if (!silent) {
      showToast('Google Drive sync complete', 'success');
    }
  } catch (err) {
    console.error('Drive Sync failure:', err);
    updateDriveSyncUI('error', `Sync failed: ${err.message}`);
    if (!silent) {
      showToast(`Sync failed: ${err.message}`, 'error');
    }
  } finally {
    if (syncBtn) {
      syncBtn.removeAttribute('disabled');
      syncBtn.innerHTML = `<i data-lucide="refresh-cw" style="width: 14px; height: 14px;"></i> Sync Now`;
      lucide.createIcons();
    }
  }
}

function updateDriveSyncUI(statusOverride = null, textOverride = null) {
  const dot = document.getElementById('drive-status-dot');
  const text = document.getElementById('drive-status-text');
  const lastSyncLabel = document.getElementById('drive-last-synced-text');
  const unauthActions = document.getElementById('drive-unauthenticated-actions');
  const authActions = document.getElementById('drive-authenticated-actions');

  if (!dot || !text) return;

  const isAuth = driveSync.isAuthenticated();

  if (statusOverride === 'syncing') {
    dot.className = 'status-dot syncing';
    text.textContent = textOverride || 'Syncing...';
  } else if (statusOverride === 'error') {
    dot.className = 'status-dot error';
    text.textContent = textOverride || 'Sync Error';
  } else if (isAuth) {
    dot.className = 'status-dot connected';
    text.textContent = textOverride || driveSync.userEmail || 'Connected to Google Drive';
    if (unauthActions) unauthActions.classList.add('hidden');
    if (authActions) authActions.classList.remove('hidden');
  } else {
    dot.className = 'status-dot';
    text.textContent = 'Not Connected';
    if (unauthActions) unauthActions.classList.remove('hidden');
    if (authActions) authActions.classList.add('hidden');
  }

  if (lastSyncLabel) {
    if (driveSync.lastSyncedAt) {
      const date = new Date(driveSync.lastSyncedAt);
      lastSyncLabel.textContent = `Last synced: ${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    } else {
      lastSyncLabel.textContent = 'Never synced';
    }
  }

  lucide.createIcons();
}

