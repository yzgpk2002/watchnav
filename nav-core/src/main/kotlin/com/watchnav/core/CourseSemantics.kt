package com.watchnav.core

import java.util.Locale

object CourseSemantics {
    fun role(kind: String, type: String?, symbol: String?): CoursePointRole {
        val normalizedType = normalize(type)
        val maneuver = maneuver(symbol) ?: maneuver(type)
        return when {
            normalizedType in VIA_TYPES -> CoursePointRole.VIA
            normalizedType in SHAPING_TYPES -> CoursePointRole.SHAPING
            maneuver != null -> CoursePointRole.TURN
            kind == "wpt" -> CoursePointRole.POI
            else -> CoursePointRole.NORMAL
        }
    }

    fun category(vararg values: String?): CoursePointCategory {
        val key = values.joinToString(" ") { normalize(it) }
        return when {
            containsAny(key, "cafe", "restaurant", "fast_food", "food", "pizza") -> CoursePointCategory.FOOD
            containsAny(key, "drinking_water", "water_source", "water", "spring") -> CoursePointCategory.WATER
            containsAny(key, "shopping_cart", "convenience_store", "department_store", "store", "shop") -> CoursePointCategory.STORE
            containsAny(key, "lodging", "campground", "campsite", "shelter", "hotel", "hostel") -> CoursePointCategory.SHELTER
            containsAny(key, "pharmacy", "hospital", "medical", "firstaid", "first_aid", "aed") -> CoursePointCategory.MEDICAL
            containsAny(key, "summit", "peak") -> CoursePointCategory.SUMMIT
            containsAny(key, "viewpoint", "scenic", "picnic", "overlook") -> CoursePointCategory.VIEWPOINT
            containsAny(key, "danger", "hazard", "warning") -> CoursePointCategory.DANGER
            containsAny(key, "fishing", "fish", "angling") -> CoursePointCategory.FISHING
            else -> CoursePointCategory.GENERIC
        }
    }

    fun maneuver(value: String?): ManeuverKind? {
        val key = normalize(value)
        if (key.isBlank()) return null
        return when {
            containsAny(key, "uturn", "u_turn", "u-turn", "reverse") -> ManeuverKind.U_TURN
            containsAny(key, "left_sharp", "sharp_left", "hard_left") -> ManeuverKind.SHARP_LEFT
            containsAny(key, "left_slight", "slight_left", "keep_left", "left_fork", "fork_left") -> ManeuverKind.SLIGHT_LEFT
            key == "left" || key.endsWith("_left") -> ManeuverKind.LEFT
            containsAny(key, "right_sharp", "sharp_right", "hard_right") -> ManeuverKind.SHARP_RIGHT
            containsAny(key, "right_slight", "slight_right", "keep_right", "right_fork", "fork_right") -> ManeuverKind.SLIGHT_RIGHT
            key == "right" || key.endsWith("_right") -> ManeuverKind.RIGHT
            containsAny(key, "finish", "end", "destination") -> ManeuverKind.FINISH
            containsAny(key, "straight", "continue", "start") -> ManeuverKind.START
            else -> null
        }
    }

    fun isVisibleMilestone(point: CoursePoint): Boolean =
        point.role == CoursePointRole.VIA && !point.name.isNullOrBlank()

    private fun normalize(value: String?): String = value.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .replace(' ', '_')
        .replace('-', '_')

    private fun containsAny(value: String, vararg candidates: String): Boolean =
        candidates.any { value.contains(it) }

    private val VIA_TYPES = setOf("via", "viapoint", "via_point", "stop", "course_point")
    private val SHAPING_TYPES = setOf("shaping", "shapingpoint", "shaping_point", "silent")
}
