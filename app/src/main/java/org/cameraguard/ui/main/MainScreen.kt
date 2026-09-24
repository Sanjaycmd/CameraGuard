package org.cameraguard.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import org.cameraguard.data.DefaultDataRepository
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import org.cameraguard.monitoring.CameraMonitor

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel {
        MainScreenViewModel(DefaultDataRepository())
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CameraGuardDashboard(modifier = modifier)
}

@Composable
private fun CameraGuardDashboard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
   
    val cameraMonitor = remember { CameraMonitor() }
    val events by cameraMonitor.events.collectAsState()
    val isMonitoring by cameraMonitor.isMonitoring.collectAsState()

    DisposableEffect(Unit) {
        cameraMonitor.startMonitoring()

        onDispose {
            cameraMonitor.stopMonitoring()
    }
}

    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraPermissionGranted = granted
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "CameraGuard",
            style = MaterialTheme.typography.headlineLarge
        )

        Text(
            text = "Camera Privacy Monitor",
            style = MaterialTheme.typography.titleMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = "Camera Access Status",
                    style = MaterialTheme.typography.titleLarge
                )

                Text(
                    text = if (cameraPermissionGranted) {
                        "Camera permission: GRANTED"
                    } else {
                        "Camera permission: NOT GRANTED"
                    }
                )

                Button(
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (cameraPermissionGranted)
                            "Camera Permission Granted"
                        else
                            "Request Camera Permission"
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                Text(
                    text = "Monitoring Status",
                    style = MaterialTheme.typography.titleLarge
                )


                Text(
		    text = if (isMonitoring) {
		        "CameraGuard monitoring: ACTIVE"
		    } else {
		        "CameraGuard monitoring: STOPPED"
		    }
		)

		Text(
		   text = "Events detected: ${events.size}"
		)

                Text(
                    text = "Phase 1: Camera permission and monitoring foundation"
                )
            }
        }
    }
}
