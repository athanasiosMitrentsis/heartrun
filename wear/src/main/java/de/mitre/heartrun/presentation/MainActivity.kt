package de.mitre.heartrun.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import de.mitre.heartrun.ble.FoundDevice
import de.mitre.heartrun.ble.HeartRateBleClient
import de.mitre.heartrun.ble.HeartRateSample
import de.mitre.heartrun.ble.SensorState
import de.mitre.heartrun.presentation.theme.MyApplicationTheme
import kotlinx.coroutines.delay

private val BLE_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

class MainActivity : ComponentActivity() {

    private lateinit var client: HeartRateBleClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = HeartRateBleClient(this)
        setContent {
            val state by client.state.collectAsState()
            val sample by client.sample.collectAsState()
            val devices by client.devices.collectAsState()
            var granted by remember {
                mutableStateOf(BLE_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                granted = it.values.all { ok -> ok }
            }
            SpikeScreen(
                granted = granted,
                state = state,
                sample = sample,
                devices = devices,
                onRequestPermissions = { launcher.launch(BLE_PERMISSIONS) },
                onScan = client::startScan,
                onSelect = { client.connect(it.address) },
            )
        }
    }

    override fun onDestroy() {
        client.close()
        super.onDestroy()
    }
}

@Composable
private fun SpikeScreen(
    granted: Boolean,
    state: SensorState,
    sample: HeartRateSample?,
    devices: List<FoundDevice>,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onSelect: (FoundDevice) -> Unit,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtimeNanos()
            delay(500)
        }
    }

    MyApplicationTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(
                scrollState = listState,
                edgeButton = {
                    if (!granted) {
                        EdgeButton(onClick = onRequestPermissions) { Text("Erlauben") }
                    } else {
                        EdgeButton(onClick = onScan) { Text("Suchen") }
                    }
                },
            ) { contentPadding ->
                TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                    item { ListHeader { Text(state.name) } }
                    item {
                        Text(
                            text = sample?.bpm?.toString() ?: "--",
                            style = MaterialTheme.typography.displayLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (sample != null) {
                        item {
                            val ageS = (now - sample.elapsedRealtimeNanos) / 1_000_000_000
                            Text(
                                text = "RR ${sample.rrMs.size} · vor ${ageS}s",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(devices, key = { it.address }) { device ->
                        Button(onClick = { onSelect(device) }, modifier = Modifier.fillMaxWidth()) {
                            Text(device.name ?: device.address)
                        }
                    }
                }
            }
        }
    }
}
