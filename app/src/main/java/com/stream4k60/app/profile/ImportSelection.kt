package com.stream4k60.app.profile

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hands a just-imported scene collection to the studio, which switches to it so imported layouts
 * appear on the imported canvas right away.
 */
object ImportSelection {
    val requestedCollectionId = MutableStateFlow<String?>(null)
}
