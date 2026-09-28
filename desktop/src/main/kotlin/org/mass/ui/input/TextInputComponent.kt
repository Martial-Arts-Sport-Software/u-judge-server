package org.mass.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Renders text input component
 * @param labelText - label text above input
 * @param inputValue - current value of text input
 * @param onChange - callback, that is called on component's value change
 * @param modifier - width and placement of the whole component
 * @param placeholder - hint shown while the input is empty
 * @param validator - returns the error for a value or null; it runs when the input loses focus and while an error is shown
 * @param labelColor - label color, for inputs on dark panels
 */
@Composable
fun TextInputComponent(
    labelText: String? = null,
    inputValue: String = "",
    onChange: (inputValue: String) -> Unit,
    modifier: Modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth(0.8f),
    placeholder: String? = null,
    validator: ((String) -> String?)? = null,
    labelColor: Color = Color.Unspecified,
) {
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        if (labelText != null) {
            Text(
                labelText,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
            )
            Spacer(Modifier.height(5.dp))
        }
        Box(
            Modifier
                .background(Color.White, shape = RoundedCornerShape(5.dp))
                .border(2.dp, if (error != null) ERROR_COLOR else Color(0xFF7C45E2), RoundedCornerShape(5.dp))
        ) {
            if (inputValue.isEmpty() && placeholder != null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.Gray,
                    modifier = Modifier.padding(10.dp, 11.dp),
                )
            }
            BasicTextField(
                value = inputValue,
                onValueChange = { value: String ->
                    onChange(value)
                    // An error already shown follows the typing, so it disappears as soon as the value is fixed.
                    if (error != null) error = validator?.invoke(value)
                },
                singleLine = true,
                modifier = Modifier
                    .onFocusChanged { state ->
                        if (focused && !state.isFocused) error = validator?.invoke(inputValue)
                        focused = state.isFocused
                    }
                    .semantics { if (labelText != null) contentDescription = labelText }
                    .padding(10.dp, 11.dp)
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.labelLarge
            )
        }
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = ERROR_COLOR, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val ERROR_COLOR = Color(0xFFB3261E)
