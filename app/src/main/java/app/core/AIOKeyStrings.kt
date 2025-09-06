package app.core

import lib.process.LogHelperUtils

/**
 * [AIOKeyStrings] holds constant keys and action strings used across the application.
 *
 * These keys are primarily used for inter-component communication, such as:
 * - Passing data via [Intent] extras or `Bundle`s.
 * - Handling activity results.
 * - Controlling URL parsing behavior.
 * - Managing download-related events like retries.
 *
 * Each key or action is documented to describe its purpose and how it should be used.
 *
 * Logging is included for better traceability when keys are accessed,
 * helping debug potential mismatches or incorrect usage.
 */
object AIOKeyStrings {

    private val logger = LogHelperUtils.from(javaClass)

    /**
     * Key used to indicate that a URL should no longer be parsed.
     * This flag can be passed in intents or bundles to prevent further parsing attempts.
     */
    const val DONT_PARSE_URL_ANYMORE = "DONT_PARSE_URL_ANYMORE"

    /**
     * Key used for passing or retrieving results between activities.
     * Typically used in conjunction with [Intent.putExtra] and [Intent.getStringExtra].
     */
    const val ACTIVITY_RESULT_KEY = "ACTIVITY_RESULT_KEY"
}