// Chapter Parser Module
// Parses Podcasting 2.0 JSON chapters and extracts timestamped chapters from shownotes / descriptions

export class ChapterParser {
  static parseTimestamp(timeStr) {
    if (!timeStr) return 0;
    const parts = timeStr.trim().split(':').map(Number);
    if (parts.length === 3) {
      return (parts[0] * 3600) + (parts[1] * 60) + parts[2];
    } else if (parts.length === 2) {
      return (parts[0] * 60) + parts[1];
    }
    return parseFloat(timeStr) || 0;
  }

  // Parse Podcasting 2.0 JSON chapters format
  static parse(jsonString) {
    if (!jsonString) return [];
    try {
      const data = typeof jsonString === 'string' ? JSON.parse(jsonString) : jsonString;
      const rawChapters = data.chapters || [];
      return rawChapters.map(ch => ({
        startTime: ch.startTime || 0,
        endTime: ch.endTime !== undefined ? ch.endTime : null,
        title: ch.title || 'Untitled Chapter',
        img: ch.img || null,
        url: ch.url || null
      })).sort((a, b) => a.startTime - b.startTime);
    } catch (e) {
      console.error('Failed to parse chapters JSON', e);
      return [];
    }
  }

  /**
   * Fallback for feeds (like Riverside.fm, YouTube, Libsyn) that format chapters
   * directly into the episode description / shownotes rather than a Podcasting 2.0 JSON URL.
   */
  static parseFromDescription(description) {
    if (!description) return [];

    const cleanText = description
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/p>/gi, '\n')
      .replace(/<\/li>/gi, '\n')
      .replace(/<\/div>/gi, '\n')
      .replace(/<[^>]*>/g, ' ');

    const lines = cleanText.split(/\r?\n/);
    const rawChapters = [];
    const timeRegex = /(?:^|[\s•\-\*])[\[\(]?(\d{1,2}:\d{2}(?::\d{2})?)[\]\)]?[\s\-–—:]+(.+)/;

    for (const line of lines) {
      const trimmed = line.trim();
      const match = trimmed.match(timeRegex);
      if (!match) continue;
      const timeStr = match[1];
      const titleStr = match[2].trim();

      if (titleStr.length >= 2 && !/^\d+$/.test(titleStr)) {
        const seconds = this.parseTimestamp(timeStr);
        rawChapters.push({ startTime: seconds, title: titleStr });
      }
    }

    if (rawChapters.length < 2) return [];

    const seen = new Set();
    const sorted = [];
    rawChapters.sort((a, b) => a.startTime - b.startTime);
    for (const ch of rawChapters) {
      if (!seen.has(ch.startTime)) {
        seen.add(ch.startTime);
        sorted.push(ch);
      }
    }

    return sorted.map((ch, idx) => ({
      ...ch,
      endTime: idx < sorted.length - 1 ? sorted[idx + 1].startTime : null
    }));
  }
}
