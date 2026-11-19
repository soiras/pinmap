package com.sinus.pinmap.data.entity

/**
 * 标记的三态：必吃、关注、无标记。三者互斥。
 */
enum class MarkKind {
    MUST_EAT,
    WATCHED,
    NONE
}
