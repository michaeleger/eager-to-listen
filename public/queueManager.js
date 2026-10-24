// DoggCatcher Queue Manager Module
// Handles highly programmable playlist queuing, priority sorting, limits, and auto-cleanup.

export class QueueManager {
  constructor() {
    this.queue = []; // Array of queued episodes
    this.settings = {
      sortOrder: 'priority-date-oldest', // 'priority-date-oldest' | 'priority-date-newest' | 'date-oldest' | 'date-newest' | 'manual'
      autoPlayNext: true,
      autoRemovePlayed: true
    };
    this.podcastSettings = {}; // podcastUrl -> { priority: 3, maxEpisodes: 'unlimited', autoAdd: true }
    
    this.loadFromStorage();
  }

  // Load state from localStorage
  loadFromStorage() {
    try {
      const savedQueue = localStorage.getItem('dc_queue');
      const savedSettings = localStorage.getItem('dc_settings');
      const savedPodcastSettings = localStorage.getItem('dc_podcast_settings');

      if (savedQueue) this.queue = JSON.parse(savedQueue).filter(item => !item.isLive);
      if (savedSettings) this.settings = { ...this.settings, ...JSON.parse(savedSettings) };
      if (savedPodcastSettings) this.podcastSettings = JSON.parse(savedPodcastSettings);
    } catch (e) {
      console.error('Failed to load queue state from localStorage', e);
    }
  }

  // Save state to localStorage
  saveToStorage() {
    try {
      localStorage.setItem('dc_queue', JSON.stringify(this.queue));
      localStorage.setItem('dc_settings', JSON.stringify(this.settings));
      localStorage.setItem('dc_podcast_settings', JSON.stringify(this.podcastSettings));
    } catch (e) {
      console.error('Failed to save queue state to localStorage', e);
    }
  }

  // Get settings for a specific podcast feed, creating default settings if they don't exist
  getPodcastSettings(podcastUrl) {
    if (!this.podcastSettings[podcastUrl]) {
      this.podcastSettings[podcastUrl] = {
        priority: 3, // Default priority (1 is highest, 5 is lowest)
        maxEpisodes: 'unlimited', // 'unlimited' or a number (1, 2, 3, 5)
        autoAdd: true, // Auto add new episodes on feed refresh
        customDonationUrl: ''
      };
      this.saveToStorage();
    }
    return this.podcastSettings[podcastUrl];
  }

  // Update settings for a specific podcast feed
  updatePodcastSettings(podcastUrl, newSettings) {
    const current = this.getPodcastSettings(podcastUrl);
    this.podcastSettings[podcastUrl] = { ...current, ...newSettings };
    
    // If priority changed, we may need to re-sort the queue
    this.syncQueueMetadata();
    this.sortQueue();
    this.saveToStorage();
  }

  // Ensure queue items have the latest podcast priorities
  syncQueueMetadata() {
    this.queue = this.queue.map(item => {
      const settings = this.getPodcastSettings(item.podcastUrl);
      return {
        ...item,
        podcastPriority: settings.priority
      };
    });
  }

  // Add an episode to the queue
  addEpisode(episode) {
    // Live streams must NEVER enter the persistent playlist queue
    if (!episode || episode.isLive) {
      return false;
    }

    // Check if already in queue
    if (this.queue.some(item => item.id === episode.id)) {
      return false;
    }

    const settings = this.getPodcastSettings(episode.podcastUrl);
    
    // Check Max Episodes limit for this podcast in the queue
    if (settings.maxEpisodes !== 'unlimited') {
      const limit = parseInt(settings.maxEpisodes, 10);
      const existingOfPodcast = this.queue.filter(item => item.podcastUrl === episode.podcastUrl);
      
      if (existingOfPodcast.length >= limit) {
        // Find the oldest unplayed episode of this podcast to remove
        const sortedExisting = [...existingOfPodcast].sort((a, b) => new Date(a.pubDate) - new Date(b.pubDate));
        const toRemove = sortedExisting[0]; // Oldest pub date
        
        if (toRemove) {
          this.removeEpisode(toRemove.id);
        }
      }
    }

    // Add new episode
    const newItem = {
      ...episode,
      podcastPriority: settings.priority,
      addedAt: Date.now(),
      progress: episode.progress || 0,
      played: episode.played || false
    };

    this.queue.push(newItem);
    this.sortQueue();
    this.saveToStorage();
    return true;
  }

  // Remove an episode from the queue
  removeEpisode(episodeId) {
    const index = this.queue.findIndex(item => item.id === episodeId);
    if (index !== -1) {
      const removed = this.queue.splice(index, 1);
      this.saveToStorage();
      return removed[0];
    }
    return null;
  }

  // Set the play queue to a specific manual order (drag-and-drop)
  setQueueOrder(newQueue) {
    this.queue = newQueue;
    this.settings.sortOrder = 'manual';
    this.saveToStorage();
  }

  // Update progress of currently playing episode
  updateProgress(episodeId, progress, duration) {
    const item = this.queue.find(item => item.id === episodeId);
    if (!item) return;

    item.progress = progress;
    if (duration) item.duration = duration;

    // Check if played (99% done or completed)
    const isCompleted = duration && (progress / duration >= 0.99 || progress >= duration - 2);
    if (isCompleted) {
      item.played = true;
      if (this.settings.autoRemovePlayed) {
        this.removeEpisode(episodeId);
        this.saveToStorage();
        return { removed: true, item };
      }
    }

    this.saveToStorage();
    return { removed: false, item };
  }

  // Sort queue based on current rules
  sortQueue() {
    if (this.settings.sortOrder === 'manual') {
      return; // Do not touch manual order
    }

    this.queue.sort((a, b) => {
      const dateA = new Date(a.pubDate);
      const dateB = new Date(b.pubDate);

      switch (this.settings.sortOrder) {
        case 'priority-date-oldest':
          // 1. Sort by Priority ascending (1 is highest priority, 5 is lowest)
          if (a.podcastPriority !== b.podcastPriority) {
            return a.podcastPriority - b.podcastPriority;
          }
          // 2. Sort by Pub Date ascending (oldest first)
          return dateA - dateB;

        case 'priority-date-newest':
          // 1. Sort by Priority ascending
          if (a.podcastPriority !== b.podcastPriority) {
            return a.podcastPriority - b.podcastPriority;
          }
          // 2. Sort by Pub Date descending (newest first)
          return dateB - dateA;

        case 'date-oldest':
          return dateA - dateB;

        case 'date-newest':
          return dateB - dateA;

        default:
          return a.addedAt - b.addedAt;
      }
    });
  }

  // Change the global sorting order and trigger re-sorting
  setSortOrder(order) {
    this.settings.sortOrder = order;
    this.syncQueueMetadata();
    this.sortQueue();
    this.saveToStorage();
  }

  // Toggle autoPlayNext setting
  toggleAutoPlayNext() {
    this.settings.autoPlayNext = !this.settings.autoPlayNext;
    this.saveToStorage();
  }

  // Toggle autoRemovePlayed setting
  toggleAutoRemovePlayed() {
    this.settings.autoRemovePlayed = !this.settings.autoRemovePlayed;
    this.saveToStorage();
  }

  // Clear entire queue
  clearQueue() {
    this.queue = [];
    this.saveToStorage();
  }
}
