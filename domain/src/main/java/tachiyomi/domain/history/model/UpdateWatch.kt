package tachiyomi.domain.history.model

data class UpdateWatch(
    val mangaId: Long,
    val isPaused: Boolean,
    val backgroundRefreshEnabled: Boolean = false,
    val expectedIntervalDays: Int = 7,
    val refreshProfile: RefreshProfile = RefreshProfile.WEEKLY_STABLE,
    val lastBackgroundCheckAt: Long? = null,
    val lastWarnedMilestone: Int = 0,
) {
    enum class RefreshProfile {
        WEEKLY_STABLE,
        SLOW_PERIODIC,
        RAPID_IRREGULAR,
        ;

        companion object {
            fun fromInterval(days: Int): RefreshProfile {
                return when {
                    days <= 3 -> RAPID_IRREGULAR
                    days <= 14 -> WEEKLY_STABLE
                    else -> SLOW_PERIODIC
                }
            }
        }
    }
}
