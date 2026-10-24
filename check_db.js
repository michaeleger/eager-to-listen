import { DatabaseSync } from 'node:sqlite';
const db = new DatabaseSync('device_db.sqlite');
const episodes = db.prepare("SELECT id, podcastTitle, title, substr(description, 1, 100) as desc_preview, transcriptUrl, transcriptType, chaptersUrl FROM episodes WHERE podcastTitle LIKE '%Eager%' LIMIT 3").all();
console.log('EPISODES:', JSON.stringify(episodes, null, 2));

const podcasts = db.prepare("SELECT id, title, substr(description, 1, 100) as desc_preview, feedUrl FROM podcasts WHERE title LIKE '%Eager%'").all();
console.log('PODCASTS:', JSON.stringify(podcasts, null, 2));
