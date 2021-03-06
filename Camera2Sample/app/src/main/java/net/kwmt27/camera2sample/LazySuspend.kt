package net.kwmt27.camera2sample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * From https://stackoverflow.com/questions/51862715/how-would-i-wrap-this-not-quite-by-lazy-result-caching-function-call-in-idio/51877612#51877612
 * @param f function to suspend and call once
 *
 * <code>
 * private val cameraDevice = LazySuspend<CameraDevice> {
 *   val foo = otherSuspendingCall()
 *   suspendCoroutine { cont -> cont.resume(foo.use()) }
 * </code>
 */
class LazySuspend<out T : Any>(private val f: suspend () -> T) {
    private lateinit var cachedValue: T

    @Synchronized
    suspend operator fun invoke(): T = withContext(Dispatchers.Default) {
        if (!::cachedValue.isInitialized) {
            cachedValue = f()
        }
        cachedValue
    }
}
