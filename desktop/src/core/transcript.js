// Transcript parsing, port of android/.../parser/TranscriptParser.kt, plus the excerpt used for
// bookmarks. Riverside publishes plain text with no timestamps, so for those the cue times are an
// estimate (words spread across the episode's length) and the excerpt is flagged "estimated".

function parseTimestampMs(timeStr) {
  const normalized = String(timeStr).trim().replace(',', '.');
  const parts = normalized.split(':');
  const n = Number;
  if (parts.length === 3) return Math.trunc((n(parts[0]) * 3600 + n(parts[1]) * 60 + n(parts[2])) * 1000) || 0;
  if (parts.length === 2) return Math.trunc((n(parts[0]) * 60 + n(parts[1])) * 1000) || 0;
  return Math.trunc((n(normalized) || 0) * 1000);
}

const clean = (s) => String(s).replace(/<[^>]*>/g, '').trim();

function parseVtt(content) {
  const cues = [];
  const lines = content.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.includes('-->')) continue;
    const [a, b] = line.split('-->');
    const startMs = parseTimestampMs(a.trim().split(' ')[0]);
    const endMs = parseTimestampMs(b.trim().split(' ')[0]);
    const text = [];
    i++;
    while (i < lines.length && lines[i].trim()) text.push(lines[i++].trim());
    const t = clean(text.join(' '));
    if (t) cues.push({ startMs, endMs, text: t });
  }
  return cues;
}

function parseSrt(content) {
  const cues = [];
  for (const block of content.replace(/\r\n/g, '\n').split('\n\n')) {
    const lines = block.split('\n').map((l) => l.trim()).filter(Boolean);
    if (lines.length < 2) continue;
    const ti = lines[0].includes('-->') ? 0 : 1;
    if (ti >= lines.length || !lines[ti].includes('-->')) continue;
    const [a, b] = lines[ti].split('-->');
    const t = clean(lines.slice(ti + 1).join(' '));
    if (t) cues.push({ startMs: parseTimestampMs(a), endMs: parseTimestampMs(b), text: t });
  }
  return cues;
}

function parseJson(content) {
  try {
    const root = JSON.parse(content);
    const segs = root.segments || root.transcripts || [];
    return segs
      .map((s) => {
        const start = Number(s.startTime ?? s.start ?? 0);
        const end = Number(s.endTime ?? s.end ?? start + 2);
        return { startMs: Math.trunc(start * 1000), endMs: Math.trunc(end * 1000), text: String(s.body ?? s.text ?? '').trim() };
      })
      .filter((c) => c.text);
  } catch {
    return [];
  }
}

function parsePlain(content, durationSeconds) {
  const paras = content.replace(/\r\n/g, '\n').split(/\n\s*\n+/).map((p) => p.trim()).filter(Boolean);
  if (!paras.length) return { cues: [], estimated: false };
  const tsRe = /^[[(]?(\d{1,2}:\d{2}(?::\d{2})?)[\])]?[\s\-:]+([\s\S]+)/;
  const stamped = [];
  for (const p of paras) {
    const m = tsRe.exec(p);
    if (m) {
      const s = parseTimestampMs(m[1]);
      stamped.push({ startMs: s, endMs: s + 5000, text: clean(m[2].trim()) });
    }
  }
  if (stamped.length >= 3) {
    return { cues: stamped.map((c, i) => ({ ...c, endMs: i < stamped.length - 1 ? stamped[i + 1].startMs : c.startMs + 5000 })), estimated: false };
  }
  const words = paras.map((p) => Math.max(1, p.split(/\s+/).filter(Boolean).length));
  const total = words.reduce((a, b) => a + b, 0);
  const totalMs = durationSeconds > 0 ? durationSeconds * 1000 : (total / 2.5) * 1000;
  let acc = 0;
  const cues = paras.map((p, i) => {
    const startMs = Math.trunc((acc / total) * totalMs);
    acc += words[i];
    const endMs = Math.max(Math.trunc((acc / total) * totalMs), startMs + 1000);
    return { startMs, endMs, text: clean(p) };
  });
  return { cues, estimated: true };
}

/** @returns {{cues: {startMs,endMs,text}[], estimated: boolean}} */
export function parseTranscript(rawText, type = null, durationSeconds = 0) {
  const t = String(rawText ?? '').trim();
  if (!t) return { cues: [], estimated: false };
  const ty = (type || '').toLowerCase();
  if (ty.includes('json') || t.startsWith('{')) return { cues: parseJson(t), estimated: false };
  if (t.startsWith('WEBVTT') || ty.includes('vtt')) return { cues: parseVtt(t), estimated: false };
  if (t.includes('-->')) return { cues: parseSrt(t), estimated: false };
  return parsePlain(t, durationSeconds);
}

/**
 * The words around a moment. Exact transcripts: from 5s before to 60s after. Estimated timing
 * (plain text with no timestamps) is only roughly right, so the window is wider.
 */
export function excerptAt({ cues, estimated }, atSeconds, { maxChars = 1500 } = {}) {
  if (!cues.length) return null;
  const at = atSeconds * 1000;
  const from = at - (estimated ? 60_000 : 5_000);
  const to = at + (estimated ? 90_000 : 60_000);
  let picked = cues.filter((c) => c.endMs >= from && c.startMs <= to);
  if (!picked.length) {
    // Past the end of the estimate: take the closest cue
    picked = [cues.reduce((best, c) => (Math.abs(c.startMs - at) < Math.abs(best.startMs - at) ? c : best))];
  }
  let text = picked.map((c) => c.text).join(' ').replace(/\s+/g, ' ').trim();
  if (text.length > maxChars) text = `${text.slice(0, maxChars).replace(/\s+\S*$/, '')}…`;
  return { text, timing: estimated ? 'estimated' : 'exact' };
}
