// Transcript Parser Module
// Parses WebVTT, SRT, JSON, and Plain Text (e.g. Riverside.fm) formats into a standardized Array<{ start: number, end: number, text: string, speaker?: string }>

export class TranscriptParser {
  // Convert timestamp string (HH:MM:SS.mmm, MM:SS.mmm, or HH:MM:SS,mmm) to seconds
  static parseTimestamp(timeStr) {
    if (!timeStr) return 0;
    
    timeStr = timeStr.trim().replace(',', '.'); // Replace commas (SRT) with periods
    const parts = timeStr.split(':');
    
    let hours = 0;
    let minutes = 0;
    let seconds = 0;
    
    if (parts.length === 3) {
      hours = parseFloat(parts[0]) || 0;
      minutes = parseFloat(parts[1]) || 0;
      seconds = parseFloat(parts[2]) || 0;
    } else if (parts.length === 2) {
      minutes = parseFloat(parts[0]) || 0;
      seconds = parseFloat(parts[1]) || 0;
    } else {
      seconds = parseFloat(parts[0]) || 0;
    }
    
    return (hours * 3600) + (minutes * 60) + seconds;
  }

  // Parse WebVTT content
  static parseWebVTT(text) {
    const cues = [];
    const blocks = text.trim().split(/\r?\n\r?\n/);
    
    const timeReg = /([\d:,.]+)\s+-->\s+([\d:,.]+)/;
    
    for (let i = 0; i < blocks.length; i++) {
      const block = blocks[i].trim();
      if (!block || block === 'WEBVTT') continue;
      
      const lines = block.split(/\r?\n/);
      let timeMatch = null;
      let textStartIndex = 0;
      
      for (let j = 0; j < lines.length; j++) {
        const match = lines[j].match(timeReg);
        if (match) {
          timeMatch = match;
          textStartIndex = j + 1;
          break;
        }
      }
      
      if (timeMatch) {
        const start = this.parseTimestamp(timeMatch[1]);
        const end = this.parseTimestamp(timeMatch[2]);
        const textLines = lines.slice(textStartIndex).map(line => line.replace(/<[^>]*>/g, '').trim());
        const cueText = textLines.join(' ');
        
        if (cueText) {
          cues.push({ start, end, text: cueText });
        }
      }
    }
    
    return cues;
  }

  // Parse SRT content
  static parseSRT(text) {
    return this.parseWebVTT(text);
  }

  // Parse JSON transcript format
  static parseJSON(text) {
    try {
      const data = typeof text === 'string' ? JSON.parse(text) : text;
      
      if (Array.isArray(data)) {
        return data.map(item => ({
          start: parseFloat(item.start ?? item.startTime ?? item.start_time ?? 0),
          end: parseFloat(item.end ?? item.endTime ?? item.end_time ?? 0),
          text: (item.text ?? item.body ?? item.content ?? '').trim(),
          speaker: item.speaker || null
        })).filter(cue => cue.text);
      }
      
      const segments = data.segments || data.transcripts || data.items || data.words;
      if (Array.isArray(segments)) {
        return segments.map(item => ({
          start: parseFloat(item.start ?? item.startTime ?? item.start_time ?? 0),
          end: parseFloat(item.end ?? item.endTime ?? item.end_time ?? 0),
          text: (item.text ?? item.body ?? item.content ?? '').trim(),
          speaker: item.speaker || null
        })).filter(cue => cue.text);
      }
      
      return [];
    } catch (e) {
      console.error('Failed to parse JSON transcript', e);
      return [];
    }
  }

  // Parse Plain Text transcript (e.g. Riverside.fm transcripts.txt)
  static parsePlainText(content, durationSec = 0) {
    const rawParagraphs = content.replace(/\r\n/g, '\n').split(/\n\s*\n+/)
      .map(p => p.trim())
      .filter(Boolean);

    if (rawParagraphs.length === 0) return [];

    // Check for explicit timestamps: e.g. "[01:23] speaker: ..." or "01:23:45 ..."
    const timeRegex = /^[\[\(]?(\d{1,2}:\d{2}(?::\d{2})?)[\]\)]?[\s\-:]+(.+)/s;
    const timestampedCues = [];

    for (const p of rawParagraphs) {
      const match = p.match(timeRegex);
      if (match) {
        const start = this.parseTimestamp(match[1]);
        const text = match[2].trim();
        timestampedCues.push({ start, end: start + 5, text });
      }
    }

    if (timestampedCues.length >= 3) {
      return timestampedCues.map((cue, idx) => {
        const nextStart = idx < timestampedCues.length - 1 ? timestampedCues[idx + 1].start : cue.start + 5;
        return { ...cue, end: nextStart };
      });
    }

    // Riverside-style speaker turns: proportional distribution along episode duration
    const paragraphWords = rawParagraphs.map(p => {
      const w = p.split(/\s+/).filter(Boolean).length;
      return Math.max(1, w);
    });
    const totalWords = paragraphWords.reduce((a, b) => a + b, 0);
    const totalDuration = durationSec > 0 ? durationSec : (totalWords / 2.5);

    const cues = [];
    let accumulatedWords = 0;

    for (let i = 0; i < rawParagraphs.length; i++) {
      const p = rawParagraphs[i];
      const wordsInP = paragraphWords[i];
      const start = (accumulatedWords / totalWords) * totalDuration;
      accumulatedWords += wordsInP;
      const end = Math.max(start + 1, (accumulatedWords / totalWords) * totalDuration);

      let speaker = null;
      let text = p;
      const colonIdx = p.indexOf(':');
      if (colonIdx > 0 && colonIdx < 80 && !p.slice(0, colonIdx).includes('\n') && !p.startsWith('http')) {
        speaker = p.substring(0, colonIdx).trim();
        text = p.substring(colonIdx + 1).trim();
      }

      cues.push({
        start: Math.round(start * 10) / 10,
        end: Math.round(end * 10) / 10,
        text,
        speaker
      });
    }

    return cues;
  }

  // Detect format and parse accordingly
  static parse(content, type = '', durationSec = 0) {
    if (!content) return [];
    
    const mime = type.toLowerCase();
    const cleanContent = content.trim();
    
    // 1. Parse JSON if type indicates JSON or content looks like JSON
    if (mime.includes('json') || cleanContent.startsWith('{') || cleanContent.startsWith('[')) {
      return this.parseJSON(cleanContent);
    }
    
    // 2. Parse WebVTT if type indicates VTT or content has WEBVTT header
    if (mime.includes('vtt') || cleanContent.startsWith('WEBVTT')) {
      return this.parseWebVTT(cleanContent);
    }
    
    // 3. Parse as SRT (contains "-->")
    if (cleanContent.includes('-->')) {
      return this.parseSRT(cleanContent);
    }
    
    // 4. Plain text parser (Riverside.fm and text/plain)
    return this.parsePlainText(cleanContent, durationSec);
  }
}
