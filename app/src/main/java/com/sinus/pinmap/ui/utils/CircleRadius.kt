package com.sinus.pinmap.ui.utils

/** 地图圈选可选半径（米） */
val CIRCLE_RADIUS_OPTIONS = listOf(200, 500, 1000, 3000)

/** 半径文案：1000 米及以上用 km */
fun circleRadiusLabel(radius: Int): String =
    if (radius >= 1000) "${radius / 1000}km" else "${radius}m"
