// Google Drive AppData Synchronization Module
// Uses Google Drive v3 REST API with restricted 'drive.appdata' scope to sync
// subscriptions, podcast priorities, playback progress, and queue rules across PC and Android.

export class DriveSync {
  constructor(options = {}) {
    // Configurable Google OAuth 2.0 Web Client ID
    this.clientId = options.clientId || (typeof localStorage !== 'undefined' ? localStorage.getItem('dc_drive_client_id') : '') || '';
    this.accessToken = typeof localStorage !== 'undefined' ? localStorage.getItem('dc_drive_access_token') : null;
    this.tokenExpiresAt = parseInt((typeof localStorage !== 'undefined' ? localStorage.getItem('dc_drive_token_expires') : '0') || '0', 10);
    this.userEmail = typeof localStorage !== 'undefined' ? localStorage.getItem('dc_drive_user_email') : null;
    this.lastSyncedAt = typeof localStorage !== 'undefined' ? localStorage.getItem('dc_drive_last_synced') : null;
    this.syncFileId = typeof localStorage !== 'undefined' ? localStorage.getItem('dc_drive_file_id') : null;
    this.isSyncing = false;
    this.syncFileName = 'doggcatcher_sync_state.json';
    this.tokenClient = null;
    
    this.initTokenClient();
  }

  // Initialize Google Identity Services OAuth2 token client
  initTokenClient() {
    if (typeof window !== 'undefined' && window.google && window.google.accounts && window.google.accounts.oauth2) {
      if (this.clientId) {
        try {
          this.tokenClient = window.google.accounts.oauth2.initTokenClient({
            client_id: this.clientId,
            scope: 'https://www.googleapis.com/auth/drive.appdata',
            callback: (resp) => this.handleTokenResponse(resp)
          });
        } catch (err) {
          console.warn('Failed to init Google OAuth2 token client:', err);
        }
      }
    }
  }

  setClientId(clientId) {
    this.clientId = (clientId || '').trim();
    if (typeof localStorage !== 'undefined') {
      if (this.clientId) {
        localStorage.setItem('dc_drive_client_id', this.clientId);
      } else {
        localStorage.removeItem('dc_drive_client_id');
      }
    }
    this.initTokenClient();
  }

  handleTokenResponse(response) {
    if (response.error) {
      console.error('Google OAuth error:', response);
      if (this.onAuthError) this.onAuthError(response.error);
      return;
    }
    
    this.accessToken = response.access_token;
    const expiresIn = parseInt(response.expires_in, 10) || 3600;
    this.tokenExpiresAt = Date.now() + (expiresIn * 1000);
    
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('dc_drive_access_token', this.accessToken);
      localStorage.setItem('dc_drive_token_expires', this.tokenExpiresAt.toString());
    }

    // Fetch user profile info if possible
    this.fetchUserProfile().finally(() => {
      if (this.onAuthSuccess) this.onAuthSuccess();
    });
  }

  async fetchUserProfile() {
    if (!this.accessToken) return;
    try {
      const res = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
        headers: { Authorization: `Bearer ${this.accessToken}` }
      });
      if (res.ok) {
        const info = await res.json();
        this.userEmail = info.email || 'Connected';
        if (typeof localStorage !== 'undefined') {
          localStorage.setItem('dc_drive_user_email', this.userEmail);
        }
      }
    } catch (e) {
      this.userEmail = 'Connected Account';
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem('dc_drive_user_email', this.userEmail);
      }
    }
  }

  isAuthenticated() {
    return !!(this.accessToken && Date.now() < this.tokenExpiresAt);
  }

  requestLogin() {
    if (!this.clientId) {
      throw new Error('Please configure a Google OAuth Client ID in Settings first.');
    }
    if (!this.tokenClient) {
      this.initTokenClient();
    }
    if (!this.tokenClient) {
      throw new Error('Google Identity Services script not ready. Check internet connection.');
    }
    this.tokenClient.requestAccessToken({ prompt: '' });
  }

  logout() {
    if (this.accessToken && typeof window !== 'undefined' && window.google?.accounts?.oauth2) {
      try {
        window.google.accounts.oauth2.revoke(this.accessToken, () => {});
      } catch (e) {}
    }
    this.accessToken = null;
    this.tokenExpiresAt = 0;
    this.userEmail = null;
    this.syncFileId = null;
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem('dc_drive_access_token');
      localStorage.removeItem('dc_drive_token_expires');
      localStorage.removeItem('dc_drive_user_email');
      localStorage.removeItem('dc_drive_file_id');
    }
  }

  // ----------------------------------------------------
  // GOOGLE DRIVE REST API OPERATIONS
  // ----------------------------------------------------

  async getValidToken() {
    if (this.isAuthenticated()) {
      return this.accessToken;
    }
    // Token expired or missing
    return new Promise((resolve, reject) => {
      if (!this.clientId || !this.tokenClient) {
        return reject(new Error('Not signed in to Google Drive.'));
      }
      this.tokenClient.callback = (resp) => {
        if (resp.error) return reject(new Error(resp.error));
        this.handleTokenResponse(resp);
        resolve(resp.access_token);
      };
      this.tokenClient.requestAccessToken({ prompt: '' });
    });
  }

  async findOrCreateSyncFile(token) {
    if (this.syncFileId) return this.syncFileId;

    // Search inside appDataFolder space
    const query = encodeURIComponent(`name = '${this.syncFileName}' and 'appDataFolder' in parents and trashed = false`);
    const searchUrl = `https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=${query}&fields=files(id,name,modifiedTime)`;

    const res = await fetch(searchUrl, {
      headers: { Authorization: `Bearer ${token}` }
    });

    if (!res.ok) {
      throw new Error(`Drive search failed: ${res.statusText}`);
    }

    const data = await res.json();
    if (data.files && data.files.length > 0) {
      this.syncFileId = data.files[0].id;
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem('dc_drive_file_id', this.syncFileId);
      }
      return this.syncFileId;
    }

    // Create empty state file inside appDataFolder
    const createUrl = 'https://www.googleapis.com/drive/v3/files';
    const createRes = await fetch(createUrl, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name: this.syncFileName,
        parents: ['appDataFolder']
      })
    });

    if (!createRes.ok) {
      throw new Error(`Failed to create sync file in Drive: ${createRes.statusText}`);
    }

    const newFile = await createRes.json();
    this.syncFileId = newFile.id;
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('dc_drive_file_id', this.syncFileId);
    }
    return this.syncFileId;
  }

  async downloadRemoteState(token, fileId) {
    const downloadUrl = `https://www.googleapis.com/drive/v3/files/${fileId}?alt=media`;
    const res = await fetch(downloadUrl, {
      headers: { Authorization: `Bearer ${token}` }
    });

    if (!res.ok) {
      if (res.status === 404 || res.status === 204) return null;
      throw new Error(`Failed to download remote state: ${res.statusText}`);
    }

    const text = await res.text();
    if (!text || text.trim() === '') return null;
    
    try {
      return JSON.parse(text);
    } catch (e) {
      console.warn('Failed to parse remote sync state JSON, starting fresh:', e);
      return null;
    }
  }

  async uploadRemoteState(token, fileId, stateObj) {
    const uploadUrl = `https://www.googleapis.com/upload/drive/v3/files/${fileId}?uploadType=media`;
    const res = await fetch(uploadUrl, {
      method: 'PATCH',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(stateObj, null, 2)
    });

    if (!res.ok) {
      throw new Error(`Failed to upload sync state to Drive: ${res.statusText}`);
    }

    this.lastSyncedAt = new Date().toISOString();
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem('dc_drive_last_synced', this.lastSyncedAt);
    }
    return true;
  }

  // ----------------------------------------------------
  // DETERMINISTIC 3-WAY STATE MERGE ENGINE
  // ----------------------------------------------------

  static mergeStates(localState, remoteState) {
    if (!remoteState) {
      return { mergedState: localState, hasChanges: false };
    }
    if (!localState) {
      return { mergedState: remoteState, hasChanges: true };
    }

    const merged = {
      version: 1,
      lastUpdated: Math.max(localState.lastUpdated || 0, remoteState.lastUpdated || 0, Date.now()),
      subscriptions: [],
      podcastSettings: {},
      playbackProgress: {},
      queueSettings: { ...localState.queueSettings }
    };

    let hasChanges = false;

    // 1. Merge Subscriptions (Set Union + Tombstones)
    const localSubsMap = new Map((localState.subscriptions || []).map(s => [s.url, s]));
    const remoteSubsMap = new Map((remoteState.subscriptions || []).map(s => [s.url, s]));
    const allUrls = new Set([...localSubsMap.keys(), ...remoteSubsMap.keys()]);

    allUrls.forEach(url => {
      const localSub = localSubsMap.get(url);
      const remoteSub = remoteSubsMap.get(url);

      if (localSub && remoteSub) {
        const localTombstone = localSub.unsubscribedAt || 0;
        const remoteTombstone = remoteSub.unsubscribedAt || 0;
        const localActive = localSub.updatedAt || localSub.subscribedAt || 0;
        const remoteActive = remoteSub.updatedAt || remoteSub.subscribedAt || 0;

        const isUnsubscribed = (localTombstone > localActive && localTombstone > remoteActive) ||
                              (remoteTombstone > localActive && remoteTombstone > remoteActive);

        if (!isUnsubscribed) {
          const winner = localActive >= remoteActive ? localSub : remoteSub;
          merged.subscriptions.push({
            ...winner,
            unsubscribed: false,
            unsubscribedAt: null
          });
        } else {
          hasChanges = true;
        }
      } else if (localSub && !localSub.unsubscribed) {
        merged.subscriptions.push(localSub);
      } else if (remoteSub && !remoteSub.unsubscribed) {
        merged.subscriptions.push(remoteSub);
        hasChanges = true;
      }
    });

    // 2. Merge Podcast Settings (Priorities 1-5, Max Episodes)
    const localPSet = localState.podcastSettings || {};
    const remotePSet = remoteState.podcastSettings || {};
    const allPUrls = new Set([...Object.keys(localPSet), ...Object.keys(remotePSet)]);

    allPUrls.forEach(url => {
      const l = localPSet[url];
      const r = remotePSet[url];
      if (l && r) {
        const lTime = l.updatedAt || 0;
        const rTime = r.updatedAt || 0;
        if (rTime > lTime) {
          merged.podcastSettings[url] = { ...r };
          hasChanges = true;
        } else {
          merged.podcastSettings[url] = { ...l };
        }
      } else if (r) {
        merged.podcastSettings[url] = { ...r };
        hasChanges = true;
      } else if (l) {
        merged.podcastSettings[url] = { ...l };
      }
    });

    // 3. Merge Playback Progress (Per Episode / Audio URL)
    const localProg = localState.playbackProgress || {};
    const remoteProg = remoteState.playbackProgress || {};
    const allEpKeys = new Set([...Object.keys(localProg), ...Object.keys(remoteProg)]);

    allEpKeys.forEach(key => {
      const lp = localProg[key];
      const rp = remoteProg[key];
      if (lp && rp) {
        const lTime = lp.updatedAt || 0;
        const rTime = rp.updatedAt || 0;
        if (rTime > lTime) {
          merged.playbackProgress[key] = { ...rp };
          hasChanges = true;
        } else {
          merged.playbackProgress[key] = { ...lp };
        }
      } else if (rp) {
        merged.playbackProgress[key] = { ...rp };
        hasChanges = true;
      } else if (lp) {
        merged.playbackProgress[key] = { ...lp };
      }
    });

    // 4. Merge Queue Settings
    const localQSet = localState.queueSettings || {};
    const remoteQSet = remoteState.queueSettings || {};
    if ((remoteQSet.updatedAt || 0) > (localQSet.updatedAt || 0)) {
      merged.queueSettings = { ...remoteQSet };
      hasChanges = true;
    } else {
      merged.queueSettings = { ...localQSet };
    }

    return { mergedState: merged, hasChanges };
  }

  // ----------------------------------------------------
  // LOCAL SNAPSHOT BUILD & RESTORE
  // ----------------------------------------------------

  static buildLocalSnapshot(subscriptions, queueManager) {
    const playbackProgress = {};
    if (typeof localStorage !== 'undefined') {
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (key && key.startsWith('dc_prog_')) {
          const audioUrl = key.replace('dc_prog_', '');
          try {
            const item = JSON.parse(localStorage.getItem(key));
            playbackProgress[audioUrl] = {
              progress: item.progress || 0,
              duration: item.duration || 0,
              played: item.played || false,
              updatedAt: item.updatedAt || Date.now()
            };
          } catch (e) {}
        }
      }
    }

    const podcastSettings = {};
    for (const [url, set] of Object.entries(queueManager.podcastSettings || {})) {
      podcastSettings[url] = {
        ...set,
        updatedAt: set.updatedAt || Date.now()
      };
    }

    return {
      version: 1,
      lastUpdated: Date.now(),
      subscriptions: (subscriptions || []).map(s => ({
        url: s.url,
        title: s.title,
        image: s.image,
        author: s.author,
        description: s.description,
        subscribedAt: s.subscribedAt || Date.now(),
        updatedAt: s.updatedAt || Date.now(),
        unsubscribed: false,
        unsubscribedAt: null,
        episodes: (s.episodes || []).slice(0, 50)
      })),
      podcastSettings,
      playbackProgress,
      queueSettings: {
        sortOrder: queueManager.settings.sortOrder,
        autoPlayNext: queueManager.settings.autoPlayNext,
        autoRemovePlayed: queueManager.settings.autoRemovePlayed,
        updatedAt: queueManager.settings.updatedAt || Date.now()
      }
    };
  }

  static applySnapshotToLocal(snapshot, queueManager) {
    if (!snapshot) return { subscriptionsUpdated: false, queueUpdated: false };

    let subscriptionsUpdated = false;

    // 1. Restore / Merge Subscriptions
    if (snapshot.subscriptions && Array.isArray(snapshot.subscriptions)) {
      const existing = typeof localStorage !== 'undefined' ? JSON.parse(localStorage.getItem('dc_subscriptions') || '[]') : [];
      const existingUrls = new Set(existing.map(s => s.url));
      
      let subsChanged = false;
      const mergedSubs = [...existing];

      snapshot.subscriptions.forEach(sub => {
        if (!existingUrls.has(sub.url)) {
          mergedSubs.push(sub);
          existingUrls.add(sub.url);
          subsChanged = true;
        }
      });

      if (subsChanged && typeof localStorage !== 'undefined') {
        localStorage.setItem('dc_subscriptions', JSON.stringify(mergedSubs));
        subscriptionsUpdated = true;
      }
    }

    // 2. Restore Podcast Settings (Priorities 1-5, Queue limits)
    if (snapshot.podcastSettings) {
      for (const [url, set] of Object.entries(snapshot.podcastSettings)) {
        queueManager.podcastSettings[url] = {
          ...queueManager.getPodcastSettings(url),
          ...set
        };
      }
      queueManager.saveToStorage();
    }

    // 3. Restore Playback Progress
    if (snapshot.playbackProgress) {
      for (const [audioUrl, prog] of Object.entries(snapshot.playbackProgress)) {
        if (typeof localStorage !== 'undefined') {
          localStorage.setItem(`dc_prog_${audioUrl}`, JSON.stringify(prog));
        }
        const qItem = queueManager.queue.find(q => q.audioUrl === audioUrl);
        if (qItem) {
          queueManager.updateProgress(qItem.id, prog.progress, prog.duration);
        }
      }
    }

    // 4. Restore Queue Settings
    if (snapshot.queueSettings) {
      queueManager.settings = {
        ...queueManager.settings,
        ...snapshot.queueSettings
      };
      queueManager.sortQueue();
      queueManager.saveToStorage();
    }

    return { subscriptionsUpdated, queueUpdated: true };
  }

  async sync(subscriptions, queueManager) {
    if (this.isSyncing) return { status: 'already_syncing' };

    this.isSyncing = true;
    try {
      const token = await this.getValidToken();
      const fileId = await this.findOrCreateSyncFile(token);

      const remoteState = await this.downloadRemoteState(token, fileId);
      const localState = DriveSync.buildLocalSnapshot(subscriptions, queueManager);
      const { mergedState, hasChanges } = DriveSync.mergeStates(localState, remoteState);

      await this.uploadRemoteState(token, fileId, mergedState);
      const applyResult = DriveSync.applySnapshotToLocal(mergedState, queueManager);

      return {
        status: 'success',
        lastSyncedAt: this.lastSyncedAt,
        hasChanges,
        applyResult,
        mergedState
      };
    } catch (err) {
      console.error('Drive Sync error:', err);
      throw err;
    } finally {
      this.isSyncing = false;
    }
  }
}
