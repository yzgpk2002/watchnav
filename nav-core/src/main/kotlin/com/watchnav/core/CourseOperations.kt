package com.watchnav.core

fun Course.reversed(newName: String = "$name (reverse)"): Course = Course(
    name = newName,
    points = points.asReversed(),
    breakBeforePointIndices = breakBeforePointIndices.mapTo(LinkedHashSet()) { points.size - it },
)
