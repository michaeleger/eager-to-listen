// Migration Importer Module
// Handles client-side OPML XML parsing and Podcast Addict SQLite DB parsing using sql.js.

export class Importer {
  // Parse standard OPML XML content
  static parseOPML(xmlText) {
    const parser = new DOMParser();
    const xmlDoc = parser.parseFromString(xmlText, 'text/xml');
    
    // Check for XML parsing errors
    const parserError = xmlDoc.querySelector('parsererror');
    if (parserError) {
      throw new Error('Invalid XML file. Failed to parse OPML.');
    }

    const outlines = xmlDoc.querySelectorAll('outline[type="rss"], outline[xmlUrl]');
    const feeds = [];

    outlines.forEach(outline => {
      const url = outline.getAttribute('xmlUrl') || outline.getAttribute('url');
      const title = outline.getAttribute('text') || outline.getAttribute('title') || 'Untitled Podcast';
      
      if (url) {
        feeds.push({
          title: title.trim(),
          url: url.trim()
        });
      }
    });

    return feeds;
  }

  // Load and parse Podcast Addict sqlite database
  // Requires sql.js to be loaded on the window object (window.initSqlJs)
  static async parsePodcastAddictDb(fileBuffer) {
    if (!window.initSqlJs) {
      throw new Error('SQLite engine (sql.js) is not loaded. Please wait a moment and try again.');
    }

    try {
      // Initialize SQL.js WebAssembly engine
      const SQL = await window.initSqlJs({
        locateFile: file => `https://cdnjs.cloudflare.com/ajax/libs/sql.js/1.6.2/${file}`
      });

      // Load database from file buffer
      const db = new SQL.Database(new Uint8Array(fileBuffer));

      // 1. Fetch Subscribed Podcasts
      // Scheme: Table 'podcasts' usually contains 'url' (RSS url), 'name' (title), '_id' (primary key)
      let podcasts = [];
      try {
        const resPodcasts = db.exec("SELECT _id, name, url FROM podcasts");
        if (resPodcasts && resPodcasts.length > 0) {
          const cols = resPodcasts[0].columns;
          const vals = resPodcasts[0].values;
          
          const idIdx = cols.indexOf('_id');
          const nameIdx = cols.indexOf('name');
          const urlIdx = cols.indexOf('url');

          podcasts = vals.map(row => ({
            id: row[idIdx],
            name: row[nameIdx] || 'Imported Podcast',
            url: row[urlIdx]
          })).filter(p => p.url);
        }
      } catch (err) {
        console.warn('Failed to query podcasts table (schema might differ):', err);
      }

      // 2. Fetch Episodes Playback Progress & Status
      // Scheme: Table 'episodes' usually contains 'podcast_id', 'name' (title), 'url' or 'download_url' (audio URL),
      // 'position_to_resume' (ms), 'duration_ms', 'seen_status' (1 = seen/played, 0 = unplayed)
      let progress = [];
      try {
        const resEpisodes = db.exec(
          "SELECT podcast_id, name, url, position_to_resume, duration_ms, seen_status FROM episodes WHERE position_to_resume > 0 OR seen_status = 1"
        );
        if (resEpisodes && resEpisodes.length > 0) {
          const cols = resEpisodes[0].columns;
          const vals = resEpisodes[0].values;

          const podcastIdIdx = cols.indexOf('podcast_id');
          const nameIdx = cols.indexOf('name');
          const urlIdx = cols.indexOf('url');
          const resumeIdx = cols.indexOf('position_to_resume');
          const durationIdx = cols.indexOf('duration_ms');
          const statusIdx = cols.indexOf('seen_status');

          progress = vals.map(row => {
            const durationSec = (row[durationIdx] || 0) / 1000;
            const resumeSec = (row[resumeIdx] || 0) / 1000;
            
            // Podcast Addict seen_status values: 1 is fully played, 0 is unplayed, 3 is partially played
            const isPlayed = row[statusIdx] === 1 || (durationSec > 0 && resumeSec >= durationSec - 5);

            return {
              podcastId: row[podcastIdIdx],
              title: row[nameIdx] || 'Untitled Episode',
              audioUrl: row[urlIdx],
              progress: resumeSec,
              duration: durationSec,
              played: isPlayed
            };
          }).filter(e => e.audioUrl);
        }
      } catch (err) {
        console.warn('Failed to query episodes table (schema might differ):', err);
      }

      // Close the database to free memory
      db.close();

      return {
        podcasts,
        progress
      };
    } catch (error) {
      console.error('Error parsing SQLite database:', error);
      throw new Error('Failed to parse the SQLite database file. Ensure it is a valid decrypted sqlite database.');
    }
  }
}
