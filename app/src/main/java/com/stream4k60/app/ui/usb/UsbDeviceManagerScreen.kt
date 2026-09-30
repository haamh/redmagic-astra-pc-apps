package com.stream4k60.app.ui.usb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stream4k60.app.engine.NativeUsbManager
import com.stream4k60.app.engine.UsbDeviceInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.Alignment

@Composable
fun UsbDeviceManagerScreen(
    usbManager: NativeUsbManager
) {
    val devices by usbManager.connectedDevices.collectAsState()
    val budget by usbManager.bandwidthBudget.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "USB Devices", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        // Bandwidth Meter
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "USB Bus Bandwidth", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = if (budget.totalBandwidthMbps > 0) budget.usedBandwidthMbps.toFloat() / budget.totalBandwidthMbps else 0f,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Used: ${budget.usedBandwidthMbps} Mbps")
                    Text(text = "Available: ${budget.availableBandwidthMbps} Mbps")
                    Text(text = "Total: ${budget.totalBandwidthMbps} Mbps")
                }
                if (budget.availableBandwidthMbps < 100) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error)
                        Text(text = "Bandwidth limit approached. Video quality may downgrade.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { usbManager.initialize() }) {
                Text("Scan for Devices")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(devices) { device ->
                UsbDeviceItem(device, usbManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDeviceItem(device: UsbDeviceInfo, usbManager: NativeUsbManager) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Info, contentDescription = "Device Info")
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = device.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(text = "Type: ${device.deviceType.name} | Status: ${if (device.isCapturing) "Capturing" else "Idle"}")
                }
                Button(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide Details" else "Details")
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Vendor ID: 0x${device.vendorId.toString(16)}")
                Text(text = "Product ID: 0x${device.productId.toString(16)}")
                Text(text = "Bandwidth Est: ${device.estimatedBandwidthMbps} Mbps")
                if (device.supportedFormats.isNotEmpty()) {
                    Text(text = "Formats:")
                    device.supportedFormats.forEach { format ->
                        Text(text = "- $format", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
