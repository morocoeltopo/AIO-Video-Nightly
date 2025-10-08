package app.core

import android.os.CountDownTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.process.LogHelperUtils
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [AIOTimer] is a recurring countdown timer that notifies registered listeners on each tick.
 *
 * This timer:
 * - Automatically restarts itself when finished, simulating an "infinite" timer until stopped.
 * - Maintains WeakReferences for listeners to avoid memory leaks.
 * - Executes listener callbacks in parallel on a background thread and then switches
 *   to the Main/UI thread for safe UI updates.
 * - Limits concurrency of background executions to avoid overwhelming system resources.
 *
 * @param millisInFuture Initial duration in milliseconds before the timer finishes.
 * @param countDownInterval Interval in milliseconds between [onTick] callbacks.
 * @param maxConcurrentListeners Maximum number of listeners allowed to execute in parallel.
 */
open class AIOTimer(
	millisInFuture: Long,
	countDownInterval: Long,
	maxConcurrentListeners: Int = 10
) : CountDownTimer(millisInFuture, countDownInterval) {

	/**
	 * Logger instance for debugging and error reporting within AIOTimer.
	 * Uses class reference for contextual logging.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Holds weak references to registered [AIOTimerListener]s.
	 * Weak references prevent memory leaks by allowing listeners to be garbage collected
	 * if no strong references exist.
	 */
	private val timerListeners = CopyOnWriteArrayList<WeakReference<AIOTimerListener>>()

	/** Tracks the number of timer ticks that have occurred since the timer started. */
	private var loopCount = 0.0

	/**
	 * Coroutine scope for dispatching listener callbacks in parallel background threads.
	 * Uses [SupervisorJob] to isolate failures in individual listener coroutines.
	 * [Dispatchers.Default.limitedParallelism] restricts the number of concurrent listener executions
	 * to avoid overwhelming system resources.
	 */
	private val listenerScope = CoroutineScope(
		SupervisorJob() + Dispatchers.Default.limitedParallelism(maxConcurrentListeners)
	)

	/**
	 * Called on every timer tick at the specified [countDownInterval].
	 *
	 * Responsibilities:
	 * - Increment the [loopCount].
	 * - Remove listeners that have been garbage collected.
	 * - Launch parallel coroutines for each active listener.
	 * - Switch to the Main/UI thread to safely invoke [AIOTimerListener.onAIOTimerTick].
	 * - Log each step for debugging and error tracking.
	 *
	 * @param millisUntilFinished Time left until the timer finishes. Ignored because
	 *                            AIOTimer automatically restarts.
	 *
	 * Logging:
	 * - Logs the tick event with loop count and remaining time.
	 * - Logs when notifying each listener on the Main thread.
	 * - Logs errors if a listener callback throws an exception.
	 */
	override fun onTick(millisUntilFinished: Long) {
		loopCount++
		logger.d("AIOTimer tick: loopCount=$loopCount, millisUntilFinished=$millisUntilFinished")

		// Remove listeners that have been garbage collected
		timerListeners.removeAll { it.get() == null }

		// Notify all active listeners in parallel
		timerListeners.forEach { listenerRef ->
			listenerRef.get()?.let { listener ->
				listenerScope.launch {
					try {
						// Switch to Main thread for UI-safe callback
						withContext(Dispatchers.Main) {
							logger.d("Notifying listener on Main thread: $listener at loopCount=$loopCount")
							listener.onAIOTimerTick(loopCount)
						}
					} catch (error: Exception) {
						// Log any exceptions thrown by listener callback without crashing other coroutines
						logger.e("Error in listener callback: $listener", error)
					}
				}
			}
		}
	}

	/**
	 * Called when the countdown finishes.
	 *
	 * This method is invoked by the underlying [CountDownTimer] once the timer reaches zero.
	 * In [AIOTimer], the timer is automatically restarted to simulate an "infinite" recurring timer.
	 *
	 * Logging:
	 * - Logs a debug message indicating that the timer finished and is restarting.
	 *
	 * Usage:
	 * ```
	 * // AIOTimer automatically restarts on finish; no action required to continue.
	 * ```
	 */
	override fun onFinish() {
		logger.d("AIOTimer finished. Restarting...")
		this.start()
	}

	/**
	 * Registers a listener to receive timer tick callbacks.
	 *
	 * - Prevents duplicate registrations of the same listener.
	 * - Listeners are stored as [WeakReference]s, allowing them to be garbage collected
	 *   if no strong references exist.
	 * - Registered listeners will be notified on each tick, executed in parallel background
	 *   coroutines and switched to the Main/UI thread for safe UI updates.
	 *
	 * Logging:
	 * - Logs whether a listener was newly registered or was already registered.
	 *
	 * @param listener The [AIOTimerListener] instance to register.
	 *
	 * Usage:
	 * ```
	 * val myListener = object : AIOTimer.AIOTimerListener {
	 *     override fun onAIOTimerTick(loopCount: Double) {
	 *         // Update UI safely here
	 *     }
	 * }
	 * timer.register(myListener)
	 * ```
	 */
	fun register(listener: AIOTimerListener) {
		if (timerListeners.none { it.get() == listener }) {
			timerListeners.add(WeakReference(listener))
			logger.d("Listener registered: $listener")
		} else {
			logger.d("Listener already registered: $listener")
		}
	}

	/**
	 * Unregisters a previously registered listener from receiving timer tick callbacks.
	 *
	 * - The listener will no longer receive any tick notifications.
	 * - If the listener was already garbage collected, this method safely ignores it.
	 *
	 * @param listener The [AIOTimerListener] instance to remove.
	 *
	 * Usage:
	 * ```
	 * timer.unregister(myListener)
	 * ```
	 */
	fun unregister(listener: AIOTimerListener) {
		timerListeners.removeAll { it.get() == listener }
		logger.d("Listener unregistered: $listener")
	}

	/**
	 * Stops the timer entirely and cleans up all resources.
	 *
	 * This method performs the following actions:
	 * - Cancels the underlying [CountDownTimer].
	 * - Cancels all ongoing listener coroutines launched for parallel tick notifications.
	 * - Clears all registered listeners to prevent memory leaks.
	 *
	 * After calling this method, the timer cannot be restarted unless a new instance is created.
	 *
	 * Usage:
	 * ```
	 * timer.stop()
	 * ```
	 */
	fun stop() {
		this.cancel()
		listenerScope.cancel(message = "AIOTimer stopped")
		timerListeners.clear()
		logger.d("AIOTimer stopped, listener coroutines cancelled, and all listeners cleared.")
	}

	/**
	 * Interface for receiving tick updates from an [AIOTimer].
	 *
	 * Implementers of this interface will be notified on every tick of the timer.
	 * All callbacks are executed on the **Main/UI thread**, ensuring it is safe
	 * to perform UI operations such as updating views, progress bars, or labels.
	 *
	 * Usage:
	 * ```
	 * timer.register(object : AIOTimer.AIOTimerListener {
	 *     override fun onAIOTimerTick(loopCount: Double) {
	 *         // Update UI safely here
	 *         progressBar.progress = loopCount.toInt()
	 *     }
	 * })
	 * ```
	 *
	 * Note:
	 * - Listeners are stored as [WeakReference]s in [AIOTimer], so they may be
	 *   garbage collected if no strong references exist.
	 * - Listener callbacks are invoked in parallel for each tick, but each
	 *   callback is guaranteed to run on the Main thread.
	 */
	interface AIOTimerListener {

		/**
		 * Invoked on every timer tick.
		 *
		 * @param loopCount The total number of ticks that have occurred since
		 *                  the timer started. Can be used to track progress
		 *                  or elapsed intervals.
		 */
		fun onAIOTimerTick(loopCount: Double)
	}
}