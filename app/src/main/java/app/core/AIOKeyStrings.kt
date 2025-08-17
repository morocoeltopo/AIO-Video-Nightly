package app.core

import lib.process.LogHelperUtils

/**
 * [AIOKeyStrings] holds constant keys used across the application.
 *
 * These keys are primarily intended for inter-component communication,
 * such as passing data via [Intent] extras, `Bundle`s, or activity
 * result handling.
 *
 * Logging is included for better traceability when keys are accessed,
 * helping debug potential mismatches or incorrect usage.
 */
object AIOKeyStrings {

    private val logger = LogHelperUtils.from(javaClass)

    /**
     * Key used to indicate that a URL should no longer be parsed.
     */
    const val DONT_PARSE_URL_ANYMORE = "DONT_PARSE_URL_ANYMORE"

    /**
     * Key used for passing or retrieving activity results.
     */
    const val ACTIVITY_RESULT_KEY = "ACTIVITY_RESULT_KEY"
}