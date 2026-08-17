package com.watchnav.session

import com.watchnav.core.Course
import com.watchnav.core.CoursePoint

internal object TransientRejoinPlan {
    fun build(
        sourceCourseName: String,
        rejoinPoints: List<CoursePoint>,
        originalPoints: List<CoursePoint>,
        targetIndex: Int,
    ): Course = Course(
        name = "Rejoin · ${sourceCourseName.removeRepeatedRejoinPrefix()}",
        points = rejoinPoints + originalPoints.drop(targetIndex + 1),
    )

    internal fun String.removeRepeatedRejoinPrefix(): String {
        var value = trim()
        while (value.startsWith(PREFIX, ignoreCase = true)) value = value.substring(PREFIX.length).trim()
        return value.ifBlank { "Offline course" }
    }

    private const val PREFIX = "Rejoin ·"
}
