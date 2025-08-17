package app.core

import android.os.CountDownTimer
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
 * @param millisInFuture The initial duration in milliseconds before the timer finishes.
 * @param countDownInterval The interval in milliseconds between [onTick] callbacks.
 */
open class AIOTimer(millisInFuture: Long, countDownInterval: Long) :
	CountDownTimer(millisInFuture, countDownInterval) {

	private val logger = LogHelperUtils.from(javaClass)

	/** Holds weak references to registered [AIOTimerListener]s. */
	private val timerListeners = ArrayList<WeakReference<AIOTimerListener>>()

	/** Tracks the number of times the timer has ticked since start. */
	private var loopCount = 0.0

	/**
	 * Called at every [countDownInterval].
	 * Increments [loopCount] and notifies all active listeners.
	 *
	 * @param millisUntilFinished The time left until the timer finishes. Ignored since this timer restarts itself.
	 */
	override fun onTick(millisUntilFinished: Long) {
		loopCount++
		logger.d("AIOTimer tick: loopCount=$loopCount, millisUntilFinished=$millisUntilFinished")

		// Remove listeners that have been garbage collected
		timerListeners.removeAll { it.get() == null }

		// Notify all active listeners
		timerListeners.forEach { listenerRef ->
			listenerRef.get()?.let {
				logger.d("Notifying listener: $it at loopCount=$loopCount")
				it.onAIOTimerTick(loopCount)
			}
		}
	}

	/**
	 * Called when the countdown finishes.
	 * Restarts the timer automatically to simulate infinite behavior.
	 */
	override fun onFinish() {
		logger.d("AIOTimer finished. Restarting...")
		this.start()
	}

	/**
	 * Registers a listener to receive tick events.
	 *
	 * @param listener The [AIOTimerListener] to register.
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
	 * Unregisters a previously registered listener.
	 *
	 * @param listener The [AIOTimerListener] to unregister.
	 */
	fun unregister(listener: AIOTimerListener) {
		timerListeners.removeAll { it.get() == listener }
		logger.d("Listener unregistered: $listener")
	}

	/**
	 * Stops the timer and clears all registered listeners.
	 */
	fun stop() {
		this.cancel()
		timerListeners.clear()
		logger.d("AIOTimer stopped and all listeners cleared.")
	}

	/**
	 * Listener interface for receiving [AIOTimer] tick callbacks.
	 */
	interface AIOTimerListener {
		/**
		 * Invoked on every timer tick.
		 *
		 * @param loopCount The number of ticks since the timer started.
		 */
		fun onAIOTimerTick(loopCount: Double)
	}
}