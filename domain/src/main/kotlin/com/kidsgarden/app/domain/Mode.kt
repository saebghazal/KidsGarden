package com.kidsgarden.app.domain

/**
 * Live phone authorization. Held in memory only; every process start begins at [DULL],
 * or at [DEACTIVATED] when the valid saved `active` setting is false (FR-031).
 */
enum class AuthorizationMode {
    DULL,
    PARENT_MODE,
    CHILD_PLAY_SESSION,
    SOFT_LANDING,

    /** The parent switched Kids Garden off. Holds no grant and dulls nothing (FR-031). */
    DEACTIVATED,
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
    DEACTIVATED,
}

/** Parent-selected dulling strength (FR-017). MEDIUM is the default. */
enum class DullIntensity {
    GENTLE,
    MEDIUM,
    STRONG,
}
