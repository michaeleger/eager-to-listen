import { QueueManager } from './queueManager.js';
import { TranscriptParser } from './transcriptParser.js';
import { ChapterParser } from './chapterParser.js';
import { DriveSync } from './driveSync.js';

export function runTests() {
  const results = [];
  let passed = 0;
  let total = 0;

  function assert(name, condition, message = 'Assertion failed') {
    total++;
    if (condition) {
      passed++;
      results.push({ name, success: true });
    } else {
      results.push({ name, success: false, message });
    }
  }

  // ----------------------------------------------------
  // 1. Transcript Parser Tests
  // ----------------------------------------------------
  try {
    // Test time parser
    assert(
      'Parser: parseTimestamp (MM:SS.mmm)',
      TranscriptParser.parseTimestamp('01:23.400') === 83.4,
      `Expected 83.4, got ${TranscriptParser.parseTimestamp('01:23.400')}`
    );
    assert(
      'Parser: parseTimestamp (HH:MM:SS,mmm with comma)',
      TranscriptParser.parseTimestamp('01:02:03,500') === 3723.5,
      `Expected 3723.5, got ${TranscriptParser.parseTimestamp('01:02:03,500')}`
    );
    assert(
      'Parser: parseTimestamp (seconds only)',
      TranscriptParser.parseTimestamp('45.2') === 45.2,
      `Expected 45.2, got ${TranscriptParser.parseTimestamp('45.2')}`
    );

    // Test WebVTT parser
    const vttContent = `WEBVTT

1
00:00:01.000 --> 00:00:04.500
Hello World!

2
00:00:04.500 --> 00:00:08.000
Welcome to the <v Speaker>podcast</v>.
`;
    const vttResult = TranscriptParser.parseWebVTT(vttContent);
    assert(
      'Parser: parseWebVTT count',
      vttResult.length === 2,
      `Expected 2 cues, got ${vttResult.length}`
    );
    assert(
      'Parser: parseWebVTT cue 1 text & times',
      vttResult[0].start === 1 && vttResult[0].end === 4.5 && vttResult[0].text === 'Hello World!',
      `Cue 1 parsed incorrectly: ${JSON.stringify(vttResult[0])}`
    );
    assert(
      'Parser: parseWebVTT strips tags',
      vttResult[1].text === 'Welcome to the podcast.',
      `Expected "Welcome to the podcast.", got "${vttResult[1].text}"`
    );

    // Test JSON parser
    const jsonContent = JSON.stringify({
      segments: [
        { start: 0.5, end: 2.1, text: "First line" },
        { start: 2.2, end: 4.0, text: "Second line", speaker: "Alice" }
      ]
    });
    const jsonResult = TranscriptParser.parseJSON(jsonContent);
    assert(
      'Parser: parseJSON count',
      jsonResult.length === 2,
      `Expected 2 cues, got ${jsonResult.length}`
    );
    assert(
      'Parser: parseJSON cue 2 speaker & text',
      jsonResult[1].start === 2.2 && jsonResult[1].speaker === 'Alice' && jsonResult[1].text === 'Second line',
      `Cue 2 parsed incorrectly: ${JSON.stringify(jsonResult[1])}`
    );

    // Test Riverside Plain Text parser
    const riversideText = `Speaker 1: Welcome to the episode today.
Speaker 2: Glad to be here and excited to share!`;
    const plainResult = TranscriptParser.parse(riversideText, 'text/plain', 60);
    assert(
      'Parser: Riverside plain text cues count',
      plainResult.length === 2,
      `Expected 2 cues for plain text, got ${plainResult.length}`
    );
    assert(
      'Parser: Riverside speaker turn detection',
      plainResult[0].speaker === 'Speaker 1' && plainResult[0].text === 'Welcome to the episode today.',
      `Expected speaker 1 extracted, got ${JSON.stringify(plainResult[0])}`
    );
    assert(
      'Parser: Riverside proportional timing',
      plainResult[1].start > 0 && plainResult[1].end <= 60,
      `Expected timing within duration 60, got start=${plainResult[1].start} end=${plainResult[1].end}`
    );

    // Test Chapters parsed from description
    const descWithChapters = `Episode notes:
00:00 Introduction
02:15 The story begins
10:45 Key insights and reflections
Check out our links below.`;
    const chaptersResult = ChapterParser.parseFromDescription(descWithChapters);
    assert(
      'Parser: ChapterParser.parseFromDescription count',
      chaptersResult.length === 3,
      `Expected 3 chapters, got ${chaptersResult.length}`
    );
    assert(
      'Parser: ChapterParser.parseFromDescription timestamps and titles',
      chaptersResult[0].startTime === 0 && chaptersResult[0].endTime === 135 && chaptersResult[0].title === 'Introduction' &&
      chaptersResult[1].startTime === 135 && chaptersResult[1].title === 'The story begins',
      `Chapter details incorrect: ${JSON.stringify(chaptersResult)}`
    );
  } catch (e) {
    assert('Parser tests crashed', false, e.message);
  }

  // ----------------------------------------------------
  // 2. Queue Manager Tests
  // ----------------------------------------------------
  try {
    // Clear localStorage values first to avoid side effects
    localStorage.removeItem('dc_queue');
    localStorage.removeItem('dc_settings');
    localStorage.removeItem('dc_podcast_settings');

    const qm = new QueueManager();
    qm.settings.sortOrder = 'priority-date-oldest';
    qm.settings.autoRemovePlayed = true;

    // Define mock podcasts settings
    // Podcast A (Priority 1 - High)
    // Podcast B (Priority 3 - Medium)
    qm.updatePodcastSettings('feedA', { priority: 1, maxEpisodes: 'unlimited', autoAdd: true });
    qm.updatePodcastSettings('feedB', { priority: 3, maxEpisodes: 2, autoAdd: true });

    // Mock episodes
    const ep1 = { id: 'ep1', podcastUrl: 'feedB', title: 'B1 (Older)', pubDate: '2026-06-15T12:00:00Z', audioUrl: 'b1.mp3' };
    const ep2 = { id: 'ep2', podcastUrl: 'feedA', title: 'A1 (Newer)', pubDate: '2026-06-20T12:00:00Z', audioUrl: 'a1.mp3' };
    const ep3 = { id: 'ep3', podcastUrl: 'feedB', title: 'B2 (Newer)', pubDate: '2026-06-18T12:00:00Z', audioUrl: 'b2.mp3' };
    const ep4 = { id: 'ep4', podcastUrl: 'feedB', title: 'B3 (Newest)', pubDate: '2026-06-25T12:00:00Z', audioUrl: 'b3.mp3' };

    // Test priority sorting (A should come first because priority 1 < 3)
    qm.addEpisode(ep1); // B1
    qm.addEpisode(ep2); // A1
    qm.addEpisode(ep3); // B2

    assert(
      'Queue: priority sort (high first)',
      qm.queue[0].id === 'ep2', // A1 (Priority 1) should be first
      `Expected ep2 at index 0, got ${qm.queue[0].id}`
    );
    assert(
      'Queue: date sorting within priority',
      qm.queue[1].id === 'ep1' && qm.queue[2].id === 'ep3', // B1 (June 15) then B2 (June 18)
      `Expected ep1 then ep3, got ${qm.queue[1].id} and ${qm.queue[2].id}`
    );

    // Test Max Episodes limit:
    // Feed B has maxEpisodes = 2. Currently, queue has ep1 (B1) and ep3 (B2).
    // Adding ep4 (B3) should remove the oldest unplayed episode of Feed B (which is ep1).
    qm.addEpisode(ep4);
    
    const remainingB = qm.queue.filter(item => item.podcastUrl === 'feedB');
    assert(
      'Queue: max episodes limit size',
      remainingB.length === 2,
      `Expected 2 episodes of feedB in queue, got ${remainingB.length}`
    );
    assert(
      'Queue: max episodes limit removes oldest',
      !qm.queue.some(item => item.id === 'ep1'), // ep1 should have been removed
      `ep1 should have been removed from the queue`
    );
    assert(
      'Queue: new episode added successfully',
      qm.queue.some(item => item.id === 'ep4'), // ep4 should exist
      `ep4 should be present in the queue`
    );

    // Test auto-cleanup:
    // Updating progress of ep2 to 99% should trigger removal if autoRemovePlayed is true
    const result = qm.updateProgress('ep2', 99, 100);
    assert(
      'Queue: autoRemovePlayed returns removed status',
      result.removed === true,
      `Expected autoRemovePlayed to trigger item removal`
    );
    assert(
      'Queue: autoRemovePlayed removes item',
      !qm.queue.some(item => item.id === 'ep2'),
      `ep2 should be removed from the queue`
    );

    // Test sorting change
    qm.setSortOrder('date-newest');
    assert(
      'Queue: sort order change (date-newest)',
      qm.queue[0].id === 'ep4', // June 25 (ep4) should be before June 18 (ep3)
      `Expected ep4 to be first on date-newest sort, got ${qm.queue[0].id}`
    );

    // Clean up tests
    localStorage.removeItem('dc_queue');
    localStorage.removeItem('dc_settings');
    localStorage.removeItem('dc_podcast_settings');
  } catch (e) {
    assert('Queue manager tests crashed', false, e.message);
  }

  // ----------------------------------------------------
  // 3. Google Drive Sync Merge Engine Tests
  // ----------------------------------------------------
  try {
    // Test A: Subscription Union
    const localA = {
      version: 1,
      lastUpdated: 1000,
      subscriptions: [
        { url: 'https://feed1.com', title: 'Feed 1', subscribedAt: 1000 }
      ],
      podcastSettings: { 'https://feed1.com': { priority: 3, updatedAt: 1000 } },
      playbackProgress: { 'https://ep1.mp3': { progress: 60, duration: 300, updatedAt: 1000 } },
      queueSettings: { sortOrder: 'priority-date-oldest', updatedAt: 1000 }
    };

    const remoteA = {
      version: 1,
      lastUpdated: 2000,
      subscriptions: [
        { url: 'https://feed2.com', title: 'Feed 2', subscribedAt: 2000 }
      ],
      podcastSettings: {
        'https://feed1.com': { priority: 1, updatedAt: 2500 }, // Remote updated priority to 1
        'https://feed2.com': { priority: 2, updatedAt: 2000 }
      },
      playbackProgress: {
        'https://ep1.mp3': { progress: 180, duration: 300, updatedAt: 2500 }, // Remote listened further
        'https://ep2.mp3': { progress: 50, duration: 400, updatedAt: 2000 }
      },
      queueSettings: { sortOrder: 'priority-date-newest', updatedAt: 2200 }
    };

    const { mergedState, hasChanges } = DriveSync.mergeStates(localA, remoteA);

    assert(
      'DriveSync: Subscription Set Union count',
      mergedState.subscriptions.length === 2,
      `Expected 2 merged subscriptions, got ${mergedState.subscriptions.length}`
    );
    assert(
      'DriveSync: Contains both feeds',
      mergedState.subscriptions.some(s => s.url === 'https://feed1.com') &&
      mergedState.subscriptions.some(s => s.url === 'https://feed2.com'),
      'Both feeds should be present in merged subscriptions'
    );
    assert(
      'DriveSync: Newer podcast priority wins',
      mergedState.podcastSettings['https://feed1.com']?.priority === 1,
      `Expected priority 1 for feed1, got ${mergedState.podcastSettings['https://feed1.com']?.priority}`
    );
    assert(
      'DriveSync: Higher progress timestamp wins',
      mergedState.playbackProgress['https://ep1.mp3']?.progress === 180,
      `Expected progress 180 for ep1, got ${mergedState.playbackProgress['https://ep1.mp3']?.progress}`
    );
    assert(
      'DriveSync: Remote new progress preserved',
      mergedState.playbackProgress['https://ep2.mp3']?.progress === 50,
      `Expected ep2 progress to be 50, got ${mergedState.playbackProgress['https://ep2.mp3']?.progress}`
    );
    assert(
      'DriveSync: Newer queue sort settings win',
      mergedState.queueSettings.sortOrder === 'priority-date-newest',
      `Expected priority-date-newest sortOrder, got ${mergedState.queueSettings.sortOrder}`
    );

    // Test B: Unsubscribe Tombstone test
    const localB = {
      subscriptions: [
        { url: 'https://feedX.com', title: 'Feed X', subscribedAt: 1000, updatedAt: 1000 }
      ]
    };
    const remoteB = {
      subscriptions: [
        { url: 'https://feedX.com', title: 'Feed X', unsubscribed: true, unsubscribedAt: 3000 }
      ]
    };
    const mergeB = DriveSync.mergeStates(localB, remoteB);
    assert(
      'DriveSync: Unsubscribe tombstone removes deleted subscription',
      mergeB.mergedState.subscriptions.length === 0,
      `Expected 0 subscriptions after tombstone merge, got ${mergeB.mergedState.subscriptions.length}`
    );
  } catch (e) {
    assert('DriveSync merge tests crashed', false, e.message);
  }

  return {
    passed,
    total,
    results
  };
}
