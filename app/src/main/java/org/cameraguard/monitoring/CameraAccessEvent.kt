package org.cameraguard.monitoring

data class CameraAccessEvent(
    val timestamp: Long,
    val source: String,
    val isUnexpected: Boolean
)
