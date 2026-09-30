package com.stream4k60.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Ask Android's current IME to show when an editable Compose field gains focus. */
@Composable
fun Modifier.showImeOnFocus(): Modifier {
    val keyboard = LocalSoftwareKeyboardController.current
    return onFocusChanged { state ->
        if (state.isFocused) keyboard?.show()
    }
}
