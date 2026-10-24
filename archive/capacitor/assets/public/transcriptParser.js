// Transcript Parser Module
// Parses WebVTT, SRT, and JSON formats into a standardized Array<{ start: number, end: number, text: string }>

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
    // Split by double newline or single newline if double isn't consistent
    const blocks = text.trim().split(/\r?\n\r?\n/);
    
    // WebVTT signature check
    if (blocks.length > 0 && !blocks[0].includes('WEBVTT')) {
      console.warn('File does not start with WEBVTT header, but attempting to parse anyway.');
    }
    
    // Regular expression for VTT/SRT timestamp line: e.g. 00:00:00.000 --> 00:00:04.000
    const timeReg = /([\d:,.]+)\s+-->\s+([\d:,.]+)/;
    
    for (let i = 0; i < blocks.length; i++) {
      const block = blocks[i].trim();
      if (!block || block === 'WEBVTT') continue;
      
      const lines = block.split(/\r?\n/);
      let timeMatch = null;
      let textStartIndex = 0;
      
      // Look for the line containing "-->"
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
        const textLines = lines.slice(textStartIndex).map(line => line.replace(/<[^>]*>/g, '').trim()); // Strip HTML tags
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
    // SRT parsing is very similar to WebVTT but doesn't require a WEBVTT header
    return this.parseWebVTT(text);
  }

  // Parse JSON transcript format
  static parseJSON(text) {
    try {
      const data = typeof text === 'string' ? JSON.parse(text) : text;
      
      // Look for common JSON schemas
      // 1. Array of segment objects
      if (Array.isArray(data)) {
        return data.map(item => ({
          start: parseFloat(item.start ?? item.startTime ?? item.start_time ?? 0),
          end: parseFloat(item.end ?? item.endTime ?? item.end_time ?? 0),
          text: (item.text ?? item.body ?? item.content ?? '').trim(),
          speaker: item.speaker || null
        })).filter(cue => cue.text);
      }
      
      // 2. Object with segments property (Podcast Index spec recommendation)
      const segments = data.segments || data.transcripts || data.items || data.words;
      if (Array.isArray(segments)) {
        return segments.map(item => ({
          start: parseFloat(item.start ?? item.startTime ?? item.start_time ?? 0),
          end: parseFloat(item.end ?? item.endTime ?? item.end_time ?? 0),
          text: (item.text ?? item.body ?? item.content ?? '').trim(),
          speaker: item.speaker || null
        })).filter(cue => cue.text);
      }
      
      // 3. Simple text fallback or failed structure
      console.warn('Unknown JSON transcript schema', data);
      return [];
    } catch (e) {
      console.error('Failed to parse JSON transcript', e);
      return [];
    }
  }

  // Detect format and parse accordingly
  static parse(content, type = '') {
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
    
    // 3. Fallback: Parse as SRT/WebVTT (robust line checking)
    if (cleanContent.includes('-->')) {
      return this.parseSRT(cleanContent);
    }
    
    // 4. plain text parser (no timestamps - return as single block at 0s)
    return [{ start: 0, end: 99999, text: cleanContent }];
  }
}
