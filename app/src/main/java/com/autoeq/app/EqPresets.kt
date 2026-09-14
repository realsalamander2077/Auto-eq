package com.autoeq.app

/**
 * Standard 10-band graphic-EQ center frequencies (Hz), the same layout
 * used by most consumer EQs (Winamp/foobar2000/etc).
 */
val BAND_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/**
 * A named EQ preset: a fixed dB offset per band, added on top of
 * whatever Auto mode computes. "Flat" is all zeros, i.e. trust the
 * equal-loudness auto curve alone with no extra coloring.
 *
 * These are deliberately simple designed curves (shelf/bump shapes),
 * not derived from any dataset - they're a reasonable starting point,
 * not a scientifically "correct" answer for any genre.
 */
enum class EqPreset(val label: String, val offsetsDb: DoubleArray) {
    FLAT(
        "Flat",
        doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    ),
    BASS_BOOST(
        "Bass Boost",
        //   31    62    125   250   500   1k    2k    4k    8k    16k
        doubleArrayOf(6.0, 5.0, 3.5, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    ),
    VOCAL_CLARITY(
        "Vocal Clarity",
        doubleArrayOf(-2.0, -1.5, -1.0, 0.5, 2.0, 3.0, 2.5, 1.0, 0.0, -1.0)
    ),
    PODCAST_SPEECH(
        "Podcast / Speech",
        // Cuts sub-bass rumble and sibilant highs, lifts speech-intelligibility range
        doubleArrayOf(-4.0, -3.0, -1.0, 1.0, 3.0, 3.5, 2.5, 0.5, -1.0, -2.0)
    ),
    WARM_LOFI(
        "Warm / Lo-Fi",
        doubleArrayOf(2.5, 2.0, 1.5, 1.0, 0.5, -0.5, -1.5, -2.5, -3.0, -3.5)
    ),
    BRIGHT_AIRY(
        "Bright / Airy",
        doubleArrayOf(-1.0, -0.5, 0.0, 0.0, 0.5, 1.0, 2.0, 3.0, 3.5, 3.0)
    );

    companion object {
        fun fromLabel(label: String): EqPreset =
            values().firstOrNull { it.label == label } ?: FLAT
    }
}

/**
 * Simplified equal-loudness compensation curve, approximating the
 * shape of ISO 226 (equal-loudness contours) at moderate listening
 * volume: human hearing is measurably less sensitive to deep bass and
 * very high treble than to the 1-4kHz range at the same physical
 * amplitude, so a spectrum that is physically "flat" sounds thin/bass
 * -light. These offsets (dB, added to the raw flattening gain) roughly
 * counteract that so the *perceived* result is closer to flat.
 *
 * This is a practical approximation for a mobile EQ, not a certified
 * ISO 226 implementation - true equal-loudness contours also shift
 * with absolute playback volume, which this simplified version does
 * not track.
 */
val EQUAL_LOUDNESS_OFFSET_DB = doubleArrayOf(
    5.5,  // 31 Hz   - ear is much less sensitive here, boost to compensate
    3.5,  // 62 Hz
    1.5,  // 125 Hz
    0.5,  // 250 Hz
    0.0,  // 500 Hz  - reference-ish region
    -0.5, // 1 kHz   - peak sensitivity region
    0.0,  // 2 kHz
    1.0,  // 4 kHz
    2.5,  // 8 kHz   - sensitivity drops again
    4.5   // 16 kHz  - sensitivity drops sharply
)
