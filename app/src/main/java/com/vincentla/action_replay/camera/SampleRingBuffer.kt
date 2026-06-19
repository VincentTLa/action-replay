package com.vincentla.action_replay.camera

import android.media.MediaCodec
import java.util.ArrayDeque

enum class Track { VIDEO, AUDIO }

/**
 * One encoded frame's bytes plus muxer-relevant metadata.
 * `data` is a heap copy — never a direct reference into a MediaCodec output buffer.
 */
data class Sample(
    val track: Track,
    val data: ByteArray,
    val ptsUs: Long,
    val flags: Int,
) {
    val isKeyframe: Boolean get() = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
}

/**
 * Rolling buffer of encoded samples. Holds at most `capacityUs` of timeline,
 * measured against the newest video PTS (audio gets trimmed in lockstep).
 *
 * ponytail: single lock for the whole deque; replace with a lock-free ring if
 * profiling shows contention between encoder + clip-extract threads.
 */
class SampleRingBuffer(private val capacityUs: Long) {

    private val samples = ArrayDeque<Sample>()
    private var newestVideoPts = Long.MIN_VALUE
    private var oldestVideoPts = Long.MAX_VALUE

    @Synchronized
    fun add(sample: Sample) {
        samples.addLast(sample)
        if (sample.track == Track.VIDEO) {
            newestVideoPts = sample.ptsUs
            if (oldestVideoPts == Long.MAX_VALUE) oldestVideoPts = sample.ptsUs
            trim()
        }
    }

    private fun trim() {
        val cutoff = newestVideoPts - capacityUs
        while (samples.isNotEmpty() && samples.peekFirst().ptsUs < cutoff) {
            val dropped = samples.pollFirst()
            if (dropped.track == Track.VIDEO) {
                oldestVideoPts = samples.firstOrNull { it.track == Track.VIDEO }?.ptsUs ?: Long.MAX_VALUE
            }
        }
    }

    /** Microseconds of video currently held. */
    @Synchronized
    fun bufferedUs(): Long {
        if (oldestVideoPts == Long.MAX_VALUE || newestVideoPts == Long.MIN_VALUE) return 0
        return newestVideoPts - oldestVideoPts
    }

    /**
     * Snapshot of all samples in chronological order, starting at the latest
     * video keyframe whose PTS is ≤ (newestVideoPts - rewindUs). Audio samples
     * with pts ≥ that anchor are included.
     *
     * Returns null if no eligible keyframe exists yet.
     */
    @Synchronized
    fun clear() {
        samples.clear()
        newestVideoPts = Long.MIN_VALUE
        oldestVideoPts = Long.MAX_VALUE
    }

    @Synchronized
    fun snapshotLast(rewindUs: Long): List<Sample>? {
        if (newestVideoPts == Long.MIN_VALUE) return null
        val target = newestVideoPts - rewindUs
        val all = samples.toList()
        val anchorIdx = all.indexOfLast { it.track == Track.VIDEO && it.isKeyframe && it.ptsUs <= target }
        if (anchorIdx < 0) return null
        val anchorPts = all[anchorIdx].ptsUs
        return all.filter { it.ptsUs >= anchorPts }
    }
}
