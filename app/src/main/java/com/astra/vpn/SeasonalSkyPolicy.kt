package com.astra.vpn

/** Calendar windows for lightweight seasonal sky effects. Month is 1..12. */
internal object SeasonalSkyPolicy {
    data class Theme(val newYearSnow: Boolean, val perseids: Boolean, val eclipse: Boolean)
    fun isSnowActive(connected: Boolean, month: Int, day: Int): Boolean =
        connected && resolve(month, day).newYearSnow

    /** Start positions stay within the left 68% so every track remains on-screen. */
    fun meteorStartFraction(index: Int, count: Int): Float {
        require(count > 0 && index in 0 until count)
        return if (count == 1) 0.08f else 0.08f + index * (0.60f / (count - 1))
    }

    fun resolve(month: Int, day: Int): Theme {
        require(month in 1..12 && day in 1..31)
        return Theme(
            newYearSnow = (month == 12 && day >= 30) || (month == 1 && day <= 2),
            perseids = month == 8 && day in 10..14,
            eclipse = month == 8 && day in 12..13
        )
    }
}
