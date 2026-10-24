const fs = require('fs');
const xml = fs.readFileSync('riverside_sample.xml', 'utf8');

// Match <item> blocks
const itemRegex = /<item>([\s\S]*?)<\/item>/g;
let itemMatch;
let count = 0;

while ((itemMatch = itemRegex.exec(xml)) !== null && count < 5) {
  count++;
  const itemContent = itemMatch[1];
  const title = (itemContent.match(/<title><!\[CDATA\[(.*?)\]\]><\/title>/) || itemContent.match(/<title>(.*?)<\/title>/) || [])[1];
  const desc = (itemContent.match(/<description><!\[CDATA\[([\s\S]*?)\]\]><\/description>/) || itemContent.match(/<description>([\s\S]*?)<\/description>/) || [])[1];
  
  console.log(`\n=== Episode ${count}: ${title} ===`);
  
  // Test extracting chapters from description
  const chapterSectionMatch = desc.match(/(?:<p>)?Chapters(?:<\/p>)?([\s\S]*?)(?:<p><br \/><\/p>|<p>Find|<p>Host|<p>This is|\n\n|$)/i);
  if (chapterSectionMatch) {
    console.log('Found chapter section:');
    console.log(chapterSectionMatch[1].trim());
  } else {
    console.log('No chapter section match via regex, checking lines with timestamps:');
  }
  
  // General timestamp chapter line matcher: (e.g. 00:00 Welcome or 01:23:45 Title)
  const lines = desc.replace(/<[^>]*>/g, '\n').split('\n').map(l => l.trim()).filter(Boolean);
  const detectedChapters = [];
  const timeRegex = /^(?:\[|\()?(\d{1,2}:\d{2}(?::\d{2})?)(?:\]|\))?\s*[-–—:]?\s*(.+)$/;
  
  for (const line of lines) {
    const m = line.match(timeRegex);
    if (m) {
      detectedChapters.push({ time: m[1], title: m[2].trim() });
    }
  }
  console.log(`Detected ${detectedChapters.length} chapter lines from description:`);
  console.log(detectedChapters.slice(0, 5));
}
