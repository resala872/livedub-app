package com.livedub

import kotlinx.coroutines.flow.MutableStateFlow

/** Tiny in-process state holder shared by the service and the activity. */
object Bus {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Idle")
    val original = MutableStateFlow("")
    val dubbed = MutableStateFlow("")
}
