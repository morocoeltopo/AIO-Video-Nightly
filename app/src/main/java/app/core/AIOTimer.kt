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

/**
 * [AIOTimer] is a recurring countdown timer that notifies registered listeners on each tick.
 *
 * This timer automatically restarts itself upon finishing, effectively behaving like
 * an infinite timer until explicitly stopped with [stop]. Listeners are stored as
 * [WeakReference]s to avoid memory leaks, ensuring that objects can be garbage
 * collected even if still registered.
 *
 * Listener callbacks are executed in parallel on a background thread and then switched
 * to the main/UI thread for safe UI updates. The number of concurrent background executions
 * is limited to prevent thread pool exhaustion.
 *
 * @param millisInFuture The initial duration in milliseconds before the timer finishes.
 * @param countDownInterval The interval in milliseconds between [onTick] callbacks.
 * @param maxConcurrentListeners Maximum number of listeners allowed to execute in parallel.
 */
open class AIOTimer(
	millisInFuture: Long,
	countDownInterval: Long,
	maxConcurrentListeners: Int = 4
) : CountDownTimer(millisInFuture, countDownInterval) {

	private val logger = LogHelperUtils.from(javaClass)

	/** Weak references to registered listeners */
	private val timerListeners = ArrayList<WeakReference<AIOTimerListener>>()

	/** Tracks the number of times the timer has ticked */
	private var loopCount = 0.0

	/** Coroutine scope for dispatching listener callbacks in background threads */
	private val listenerScope = CoroutineScope(
		SupervisorJob() + Dispatchers.Default.limitedParallelism(maxConcurrentListeners)
	)

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
						// Run background work here if needed
						// Then switch to Main thread for safe UI updates
						withContext(Dispatchers.Main) {
							logger.d("Notifying listener on Main thread: $listener at loopCount=$loopCount")
							listener.onAIOTimerTick(loopCount)
						}
					} catch (error: Exception) {
						logger.e("Error in listener callback: $listener", error)
					}
				}
			}
		}
	}

	override fun onFinish() {
		logger.d("AIOTimer finished. Restarting...")
		this.start()
	}

	fun register(listener: AIOTimerListener) {
		if (timerListeners.none { it.get() == listener }) {
			timerListeners.add(WeakReference(listener))
			logger.d("Listener registered: $listener")
		} else {
			logger.d("Listener already registered: $listener")
		}
	}

	fun unregister(listener: AIOTimerListener) {
		timerListeners.removeAll { it.get() == listener }
		logger.d("Listener unregistered: $listener")
	}

	/** Stops the timer and cancels all listener coroutines */
	fun stop() {
		this.cancel()
		listenerScope.cancel(message = "AIOTimer stopped")
		timerListeners.clear()
		logger.d("AIOTimer stopped, listener coroutines cancelled, and all listeners cleared.")
	}

	interface AIOTimerListener {
		/** Called on every timer tick with the current loop count (on Main/UI thread) */
		fun onAIOTimerTick(loopCount: Double)
	}
}