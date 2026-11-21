package com.astra.vpn

/** Calendar windows for lightweight seasonal sky effects. Month is 1..12. */
internal object SeasonalSkyPolicy {
    data class Theme(val newYearSnow: Boolean, val perseids: Boolean, val eclipse: Boolean)
    fun resolve(month: Int, day: Int): Theme {
        require(month in 1..12 && day in 1..31)
        return Theme(
            newYearSnow = (month == 12 && day >= 30) || (month == 1 && day <= 2),
            perseids = month == 8 && day in 10..14,
            eclipse = month == 8 && day in 12..13
        )
    }
}
