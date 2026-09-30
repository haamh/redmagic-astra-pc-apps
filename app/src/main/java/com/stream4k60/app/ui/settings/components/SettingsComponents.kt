package com.stream4k60.app.ui.settings.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.ui.util.showImeOnFocus

// ===== Desktop-density Settings Components =====
// All components use compact sizing (12-13sp text, 28-32dp heights)
// designed for monitor projection with mouse/keyboard

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    
    Column(modifier = modifier.fillMaxWidth()) {
        // Section header - clickable to collapse/expand
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 24.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content
            )
        }
        
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

/**
 * Advanced section - collapsed by default, shows "▶ Advanced" header
 * Used to hide power-user options from beginners
 */
@Composable
fun AdvancedSection(
    title: String = "Advanced",
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSection(
        title = "▸ $title",
        modifier = modifier,
        initiallyExpanded = false, // Hidden by default for beginners
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    tooltip: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            if (description != null) {
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 14.sp
                )
            }
        }
        if (tooltip != null) {
            // Tooltip icon - shows tooltip on hover
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text(tooltip, fontSize = 11.sp) } },
                state = rememberTooltipState()
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Info",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun SettingsDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(0.4f)) {
            Text(label, fontSize = 12.sp)
            if (description != null) {
                Text(description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        
        Box(modifier = Modifier.weight(0.6f)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Text(
                    text = selected,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
            }
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { 
                            Text(
                                option, 
                                fontSize = 12.sp,
                                color = if (option == selected) MaterialTheme.colorScheme.primary 
                                       else MaterialTheme.colorScheme.onSurface
                            ) 
                        },
                        onClick = { onSelect(option); expanded = false },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    unit: String = "",
    displayValue: String? = null,
    description: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(0.3f)) {
            Text(label, fontSize = 12.sp)
            description?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier
                .weight(0.55f)
                .height(24.dp)
        )
        Text(
            text = displayValue ?: "${value.toInt()}$unit",
            fontSize = 11.sp,
            modifier = Modifier.weight(0.15f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    description: String? = null
) {
    var showPassword by remember { mutableStateOf(false) }
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(0.35f)) {
            Text(label, fontSize = 12.sp)
            description?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(0.65f)
                .height(32.dp)
                .showImeOnFocus(),
            enabled = enabled,
            singleLine = singleLine,
            placeholder = { Text(placeholder, fontSize = 11.sp) },
            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
            visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (isPassword) {
                {
                    IconButton(
                        onClick = { showPassword = !showPassword },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide" else "Show",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            } else null
        )
    }
}

@Composable
fun SettingsNumberInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
    unit: String = "",
    enabled: Boolean = true,
    description: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(0.4f)) {
            Text(label, fontSize = 12.sp)
            if (description != null) {
                Text(description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        Row(
            modifier = Modifier.weight(0.6f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (value - step >= min) onValueChange(value - step) },
                enabled = enabled && value - step >= min,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Remove, "Decrease", Modifier.size(14.dp))
            }
            
            OutlinedTextField(
                value = "$value",
                onValueChange = { text -> text.toIntOrNull()?.let { v -> if (v in min..max) onValueChange(v) } },
                modifier = Modifier.width(80.dp).height(28.dp).showImeOnFocus(),
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            
            if (unit.isNotEmpty()) {
                Text(unit, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
            }
            
            IconButton(
                onClick = { if (value + step <= max) onValueChange(value + step) },
                enabled = enabled && value + step <= max,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Default.Add, "Increase", Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun SettingsButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (description != null) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 12.sp)
                Text(description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .then(if (description == null) Modifier.fillMaxWidth() else Modifier)
                .height(28.dp)
                .pointerHoverIcon(PointerIcon.Hand),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = if (destructive) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ) else ButtonDefaults.buttonColors()
        ) {
            Text(if (description != null) "..." else label, fontSize = 12.sp)
        }
    }
}

@Composable
fun SettingsInfo(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(0.4f))
        Text(
            value, 
            fontSize = 11.sp, 
            modifier = Modifier.weight(0.6f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SettingsModeSwitch(
    isAdvanced: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Output Mode:", fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = !isAdvanced,
            onClick = { onModeChange(false) },
            label = { Text("Simple", fontSize = 11.sp) },
            modifier = Modifier.height(26.dp).pointerHoverIcon(PointerIcon.Hand)
        )
        Spacer(Modifier.width(4.dp))
        FilterChip(
            selected = isAdvanced,
            onClick = { onModeChange(true) },
            label = { Text("Advanced", fontSize = 11.sp) },
            modifier = Modifier.height(26.dp).pointerHoverIcon(PointerIcon.Hand)
        )
    }
}
