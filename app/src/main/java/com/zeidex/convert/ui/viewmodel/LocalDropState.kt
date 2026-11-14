package com.zeidex.convert.ui.viewmodel

import com.zeidex.convert.engine.LocalDropReceiver
import java.io.File

/** What the LocalDrop panel shows. */
sealed interface LocalDropState {
    data object Idle : LocalDropState
    data object Connecting : LocalDropState
    /** The photo held no readable pairing code. */
    data object NoCode : LocalDropState
    data class Offer(val sender: String, val files: List<LocalDropReceiver.Incoming>, val totalBytes: Long) : LocalDropState
    data class Receiving(val fraction: Float) : LocalDropState
    data class Done(val files: List<File>) : LocalDropState
    data class Failed(val message: String) : LocalDropState
}
