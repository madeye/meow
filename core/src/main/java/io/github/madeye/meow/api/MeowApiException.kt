package io.github.madeye.meow.api

import java.io.IOException

/**
 * Failures talking to the embedded engine's controller API.
 *
 * Extends [IOException] so callers that only care about "the engine didn't
 * answer" can catch a single type; the subclasses let the UI distinguish
 * "engine isn't running" (expected whenever the VPN is off) from a genuine
 * protocol error worth surfacing.
 */
sealed class MeowApiException(message: String, cause: Throwable? = null) :
    IOException(message, cause) {

    /** The engine answered, but not with a success code. */
    class Http(val operation: String, val code: Int) :
        MeowApiException("$operation returned HTTP $code")

    /** Nothing is listening — usually just means the VPN is not connected. */
    class Unreachable(cause: Throwable) :
        MeowApiException("engine unreachable", cause)

    /** The engine answered with something we could not decode. */
    class Decode(val operation: String, cause: Throwable) :
        MeowApiException("$operation: malformed response", cause)
}
