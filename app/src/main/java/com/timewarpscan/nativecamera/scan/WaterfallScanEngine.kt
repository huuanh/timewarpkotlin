package com.timewarpscan.nativecamera.scan

import android.os.SystemClock

/**
 * Waterfall scan engine — computes scan line position based on elapsed time.
 *
 * Pure state machine with no allocations. Called from the GL thread only,
 * so no synchronization is needed.
 *
 * State flow: IDLE → SCANNING → COMPLETE
 */
class WaterfallScanEngine {

    enum class State { IDLE, SCANNING, COMPLETE }

    /**
     * Snapshot of current scan state, returned every frame.
     * @param progress 0.0 to 1.0 — fraction of scan completed
     * @param scanY current scan line position in pixels (top of screen = 0)
     * @param lastScanY previous frame's scan line position
     * @param state current engine state
     */
    data class ScanState(
        val progress: Float,
        val scanY: Int,
        val lastScanY: Int,
        val state: State
    )

    companion object {
        /** Default scan duration in milliseconds (matches RN config). */
        const val DEFAULT_DURATION_MS = 15_000L
    }

    /** Total scan duration. Can be changed before starting a scan. */
    var scanDurationMs: Long = DEFAULT_DURATION_MS

    /** Preview height in pixels, set by the renderer when surface size is known. */
    var previewHeight: Int = 0

    private var state: State = State.IDLE
    private var startTimeMs: Long = 0L
    private var lastScanY: Int = 0

    /**
     * Begin scanning. Records the start timestamp and resets scan position.
     * Must be called from the GL thread (or the activity thread before rendering starts).
     */
    fun startScan() {
        if (previewHeight <= 0) return
        state = State.SCANNING
        startTimeMs = SystemClock.elapsedRealtime()
        lastScanY = 0
    }

    /**
     * Early stop — the user tapped to finish the scan before the timer expired.
     * The composite stays as-is; the renderer should display the COMPLETE state.
     */
    fun stopScan() {
        if (state == State.SCANNING) {
            state = State.COMPLETE
        }
    }

    /**
     * Called every frame by the renderer to get the current scan position.
     *
     * Algorithm:
     *   progress = clamp((now - startTime) / duration, 0, 1)
     *   scanY    = (progress * previewHeight).toInt()
     *
     * When progress reaches 1.0 the state transitions to COMPLETE automatically.
     */
    fun update(): ScanState {
        if (state != State.SCANNING) {
            return ScanState(
                progress = if (state == State.COMPLETE) 1f else 0f,
                scanY = if (state == State.COMPLETE) previewHeight else 0,
                lastScanY = lastScanY,
                state = state
            )
        }

        val elapsed = SystemClock.elapsedRealtime() - startTimeMs
        val progress = (elapsed.toFloat() / scanDurationMs).coerceIn(0f, 1f)
        val scanY = (progress * previewHeight).toInt()

        val previousScanY = lastScanY
        lastScanY = scanY

        // Auto-complete when the scan line reaches the bottom
        if (progress >= 1f) {
            state = State.COMPLETE
        }

        return ScanState(
            progress = progress,
            scanY = scanY,
            lastScanY = previousScanY,
            state = state
        )
    }

    /** Reset to idle — clears all state so a new scan can begin. */
    fun reset() {
        state = State.IDLE
        startTimeMs = 0L
        lastScanY = 0
    }

    fun currentState(): State = state
}
