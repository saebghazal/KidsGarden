package com.kidsgarden.app.domain

/** Live phone authorization. Held in memory only; every process start begins at [DULL]. */
enum class AuthorizationMode {
    DULL,
    PARENT_MODE,
    CHILD_PLAY_SESSION,
    SOFT_LANDING,
}

/**
 * What the parent sees. [SETUP_REQUIRED] is derived from DULL authorization plus missing
 * capability; it is never an authorization and never persisted (FR-002).
 */
enum class DisplayMode {
    DULL,
    PARENT_MODE,
    CHILD_PLAY_SESSION,
    SOFT_LANDING,
    SETUP_REQUIRED,
}

/** Parent-selected dulling strength (FR-017). MEDIUM is the default. */
enum class DullIntensity {
    GENTLE,
    MEDIUM,
    STRONG,
}
