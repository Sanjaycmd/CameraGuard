package org.cameratestharness

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cameratestharness.experiment.ExperimentDataExporter
import org.cameratestharness.experiment.ExperimentDataValidator
import org.cameratestharness.experiment.ExperimentLogger
import org.cameratestharness.experiment.ExperimentScenario

private const val TAG = "CameraTestHarness"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExperimentLogger.init(applicationContext)
        Log.i(TAG, "[CameraTestHarness] Activity created")
        ExperimentLogger.recordEvent(
            lifecycleEvent = "ON_CREATE",
            activityState = "CREATED",
            appVisibility = "FOREGROUND",
            notes = "MainActivity created"
        )
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HarnessScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "[CameraTestHarness] Activity started")
        ExperimentLogger.recordEvent(
            lifecycleEvent = "ON_START",
            activityState = "STARTED",
            appVisibility = "FOREGROUND"
        )
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "[CameraTestHarness] Activity resumed")
        if (CameraTestService.isRunning.value) {
            Log.i(TAG, "[CameraTestHarness] Activity returned while camera session is active")
        }
        ExperimentLogger.recordEvent(
            lifecycleEvent = "ON_RESUME",
            userAction = "APP_RETURNED_FOREGROUND",
            activityState = "RESUMED",
            appVisibility = "FOREGROUND",
            foregroundServiceActive = CameraTestService.isRunning.value,
            notes = if (CameraTestService.isRunning.value) "Returned while camera active" else "Returned to idle app"
        )
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "[CameraTestHarness] Activity paused")
        ExperimentLogger.recordEvent(
            lifecycleEvent = "ON_PAUSE",
            activityState = "PAUSED",
            appVisibility = "FOREGROUND"
        )
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "[CameraTestHarness] Activity stopped")
        if (CameraTestService.isRunning.value) {
            Log.i(TAG, "[CameraTestHarness] Camera session continuing while Activity is not visible")
        }
        ExperimentLogger.recordEvent(
            lifecycleEvent = "ON_STOP",
            userAction = "APP_ENTERED_BACKGROUND",
            activityState = "STOPPED",
            appVisibility = "BACKGROUND",
            foregroundServiceActive = CameraTestService.isRunning.value,
            notes = if (CameraTestService.isRunning.value) "Session continuing in background" else "App backgrounded"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "[CameraTestHarness] Activity destroyed")
        ExperimentLogger.recordEvent(
            lifecycleEvent = "ON_DESTROY",
            activityState = "DESTROYED",
            appVisibility = "BACKGROUND"
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HarnessScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val isServiceRunning by CameraTestService.isRunning.collectAsStateWithLifecycle()
    val harnessState by CameraTestService.harnessState.collectAsStateWithLifecycle()
    val serviceStatusText by CameraTestService.serviceStatus.collectAsStateWithLifecycle()

    val currentScenario by ExperimentLogger.currentScenario.collectAsStateWithLifecycle()
    val currentSessionId by ExperimentLogger.currentSessionId.collectAsStateWithLifecycle()
    val repetition by ExperimentLogger.repetition.collectAsStateWithLifecycle()
    val recordCount by ExperimentLogger.recordCount.collectAsStateWithLifecycle()
    val recordedList by ExperimentLogger.records.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var localStatusMessage by remember {
        mutableStateOf(
            if (hasCameraPermission) "Camera permission is granted. Ready for scenario."
            else "Camera permission required. Please grant permission."
        )
    }

    var permissionDeniedEvaluated by remember { mutableStateOf(false) }
    var observationEvaluated by remember { mutableStateOf(false) }
    var selectedLensOption by remember { mutableStateOf(CameraLensOption.AUTO_DEFAULT) }

    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        hasCameraPermission = cameraGranted

        if (cameraGranted) {
            Log.i(TAG, "[CameraTestHarness] Permission granted")
            localStatusMessage = "Camera permission granted. Ready to start scenario."
            ExperimentLogger.recordEvent(
                userAction = "USER_GRANTED_PERMISSION",
                cameraPermission = "GRANTED",
                activityState = "RESUMED",
                appVisibility = "FOREGROUND"
            )
        } else {
            Log.w(TAG, "[CameraTestHarness] Permission denied")
            localStatusMessage = "Camera permission denied."
            ExperimentLogger.recordEvent(
                userAction = "USER_DENIED_PERMISSION",
                cameraPermission = "DENIED",
                activityState = "RESUMED",
                appVisibility = "FOREGROUND"
            )
        }
    }

    LaunchedEffect(Unit) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission && currentScenario == ExperimentScenario.PERMISSION_DENIED) {
            localStatusMessage = "Camera permission became GRANTED. Reconfiguring to Normal Foreground Camera."
            ExperimentLogger.recordEvent(
                userAction = "PERMISSION_STATE_CHANGED",
                cameraPermission = "GRANTED",
                activityState = "RESUMED",
                appVisibility = "FOREGROUND",
                notes = "Camera permission changed to GRANTED while PERMISSION_DENIED was selected; auto-reconfiguring scenario"
            )
            ExperimentLogger.setScenario(ExperimentScenario.NORMAL_FOREGROUND_CAMERA)
            permissionDeniedEvaluated = false
        }
    }

    val isCameraActive = harnessState in listOf(
        HarnessState.CAMERA_OPEN,
        HarnessState.CAPTURE_SESSION_ACTIVE,
        HarnessState.RUNNING,
        HarnessState.APP_BACKGROUND
    )

    val isTransitioning = harnessState in listOf(
        HarnessState.STARTING,
        HarnessState.CAMERA_OPENING,
        HarnessState.STOPPING,
        HarnessState.ARMED
    )

    val isError = harnessState == HarnessState.ERROR

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "Camera Guard Test Harness",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Phase 3.5: Controlled Experiment Data Collection",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Scenario Selection Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "1. Select Experiment Scenario",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = currentScenario.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ExperimentScenario.entries.forEach { scenario ->
                            FilterChip(
                                selected = (currentScenario == scenario),
                                onClick = {
                                    if (!isServiceRunning && !isCameraActive) {
                                        permissionDeniedEvaluated = false
                                        observationEvaluated = false
                                        ExperimentLogger.setScenario(scenario)
                                    }
                                },
                                enabled = !isServiceRunning && !isCameraActive,
                                label = { Text(scenario.title, fontSize = 12.sp) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Repetition: #$repetition",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (repetition > 1 && !isServiceRunning && !isCameraActive) {
                                        ExperimentLogger.setRepetition(repetition - 1)
                                    }
                                },
                                enabled = repetition > 1 && !isServiceRunning && !isCameraActive,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("-")
                            }
                            Button(
                                onClick = {
                                    if (!isServiceRunning && !isCameraActive) {
                                        ExperimentLogger.incrementRepetition()
                                    }
                                },
                                enabled = !isServiceRunning && !isCameraActive,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("+ Next Rep")
                            }
                        }
                    }
                }
            }

            // Camera Lens Selection Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "2. Select Camera Lens Orientation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Choose physical sensor facing (CameraCharacteristics.LENS_FACING).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CameraLensOption.entries.forEach { option ->
                            FilterChip(
                                selected = (selectedLensOption == option),
                                onClick = {
                                    if (!isServiceRunning && !isCameraActive) {
                                        selectedLensOption = option
                                    }
                                },
                                enabled = !isServiceRunning && !isCameraActive,
                                label = { Text(option.displayName, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // Experiment Status Telemetry Card
            val statusColor = when {
                isError -> MaterialTheme.colorScheme.errorContainer
                isCameraActive -> MaterialTheme.colorScheme.errorContainer
                isTransitioning -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }

            val indicatorColor = when {
                isError -> Color.Red
                isCameraActive -> Color.Red
                harnessState == HarnessState.ARMED -> Color(0xFFFF9800)
                isTransitioning -> Color(0xFFFFA000)
                else -> Color.Gray
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = statusColor)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(indicatorColor)
                        )
                        Text(
                            text = "Status: ${harnessState.name}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = "Scenario: ${currentScenario.id}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Camera Facing: ${selectedLensOption.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Sample/Session ID: ${currentSessionId ?: "None (will create on start)"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Repetition: #$repetition",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Recorded Events: $recordCount",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isServiceRunning || isError) serviceStatusText else localStatusMessage,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Permission Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasCameraPermission) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Camera Permission",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (hasCameraPermission) "CAMERA permission GRANTED" else "CAMERA permission NOT GRANTED",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!hasCameraPermission) {
                        Button(
                            onClick = {
                                Log.i(TAG, "[CameraTestHarness] Permission request")
                                permissionLauncher.launch(permissionsToRequest)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = "Grant", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Primary Simulation / Scenario Execution Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        // Handle special non-camera scenarios
                        if (currentScenario == ExperimentScenario.PERMISSION_DENIED) {
                            if (hasCameraPermission) {
                                localStatusMessage = "Camera permission is currently GRANTED. Cannot evaluate PERMISSION_DENIED."
                                Toast.makeText(context, "Permission is granted; cannot evaluate PERMISSION_DENIED", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (permissionDeniedEvaluated) {
                                localStatusMessage = "PERMISSION_DENIED already evaluated. Select another scenario."
                                return@Button
                            }
                            permissionDeniedEvaluated = true
                            ExperimentLogger.startNewSession(currentScenario, repetition)
                            ExperimentLogger.recordEvent(
                                userAction = "USER_EVALUATED_PERMISSION_DENIED",
                                cameraEvent = "NONE",
                                cameraId = "NONE",
                                cameraPermission = "DENIED",
                                activityState = "RESUMED",
                                appVisibility = "FOREGROUND",
                                sessionState = "IDLE",
                                notes = "rep=$repetition;Permission Denied scenario evaluated; camera acquisition withheld"
                            )
                            ExperimentLogger.stopSession()
                            localStatusMessage = "Scenario: Permission Denied evaluated and completed."
                            return@Button
                        }

                        if (currentScenario == ExperimentScenario.AMBIGUOUS_CONTEXT) {
                            if (observationEvaluated) {
                                localStatusMessage = "AMBIGUOUS_CONTEXT observation already evaluated. Select another scenario."
                                return@Button
                            }
                            observationEvaluated = true
                            ExperimentLogger.startNewSession(currentScenario, repetition)
                            ExperimentLogger.recordEvent(
                                userAction = "USER_EVALUATED_OBSERVATION",
                                cameraEvent = "NONE",
                                cameraId = "NONE",
                                cameraPermission = if (hasCameraPermission) "GRANTED" else "DENIED",
                                activityState = "RESUMED",
                                appVisibility = "FOREGROUND",
                                foregroundServiceActive = false,
                                sessionState = "IDLE",
                                notes = "rep=$repetition;Ambiguous Context observation evaluated (no camera session initiated)"
                            )
                            ExperimentLogger.stopSession()
                            localStatusMessage = "Scenario: Ambiguous Context observation recorded (no camera opened)."
                            return@Button
                        }

                        if (currentScenario == ExperimentScenario.PERMISSION_GRANTED_NO_CAMERA) {
                            if (!hasCameraPermission) {
                                localStatusMessage = "Permission is NOT granted. Grant permission before evaluating."
                                return@Button
                            }
                            ExperimentLogger.startNewSession(currentScenario, repetition)
                            ExperimentLogger.recordEvent(
                                userAction = "USER_EVALUATED_NO_CAMERA",
                                cameraEvent = "NONE",
                                cameraId = "NONE",
                                cameraPermission = "GRANTED",
                                activityState = "RESUMED",
                                appVisibility = "FOREGROUND",
                                foregroundServiceActive = false,
                                sessionState = "IDLE",
                                notes = "rep=$repetition;Permission Granted / No Camera evaluated; camera acquisition withheld"
                            )
                            ExperimentLogger.stopSession()
                            localStatusMessage = "Scenario: Permission held, camera access omitted."
                            return@Button
                        }

                        if (currentScenario == ExperimentScenario.AUTOMATED_BACKGROUND_TRIGGER) {
                            if (!hasCameraPermission) {
                                localStatusMessage = "Camera permission required before arming trigger."
                                permissionLauncher.launch(permissionsToRequest)
                                return@Button
                            }
                            if (isServiceRunning || isCameraActive) {
                                localStatusMessage = "Simulation already active."
                                return@Button
                            }
                            Log.i(TAG, "[CameraTestHarness] Arming automated trigger")
                            ExperimentLogger.startNewSession(currentScenario, repetition)
                            ExperimentLogger.recordEvent(
                                userAction = "USER_ARMED_TRIGGER",
                                cameraEvent = "NONE",
                                activityState = "RESUMED",
                                appVisibility = "FOREGROUND",
                                foregroundServiceActive = true,
                                foregroundServiceType = "camera",
                                sessionState = "ARMED",
                                notes = "rep=$repetition;trigger=countdown_timer;delay_sec=5;User armed automated trigger"
                            )
                            try {
                                val armIntent = Intent(context, CameraTestService::class.java).apply {
                                    action = CameraTestService.ACTION_ARM_AUTOMATED_TRIGGER
                                    putExtra(CameraTestService.EXTRA_TRIGGER_DELAY_MS, 5000L)
                                    putExtra(CameraTestService.EXTRA_CAMERA_LENS_FACING, selectedLensOption.id)
                                }
                                ContextCompat.startForegroundService(context, armIntent)
                                localStatusMessage = "Automated trigger armed (5s countdown). Press HOME now to test background activation!"
                                Toast.makeText(context, "Trigger armed! Switch to Home/Background now", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Log.e(TAG, "[CameraTestHarness] Failed to arm automated trigger", e)
                                localStatusMessage = "Failed to arm trigger: ${e.message}"
                            }
                            return@Button
                        }

                        // Standard camera acquisition flow
                        if (!hasCameraPermission) {
                            Log.i(TAG, "[CameraTestHarness] Permission request")
                            localStatusMessage = "Requesting CAMERA permission..."
                            permissionLauncher.launch(permissionsToRequest)
                            return@Button
                        }

                        if (isServiceRunning || isCameraActive) {
                            localStatusMessage = "Simulation already active."
                            return@Button
                        }

                        Log.i(TAG, "[CameraTestHarness] Simulation start requested")
                        ExperimentLogger.startNewSession(currentScenario, repetition)
                        ExperimentLogger.recordEvent(
                            userAction = "USER_PRESSED_START",
                            activityState = "RESUMED",
                            appVisibility = "FOREGROUND",
                            notes = "rep=$repetition;trigger=user_start"
                        )

                        try {
                            val startIntent = Intent(context, CameraTestService::class.java).apply {
                                action = CameraTestService.ACTION_START
                                putExtra(CameraTestService.EXTRA_CAMERA_LENS_FACING, selectedLensOption.id)
                            }
                            ContextCompat.startForegroundService(context, startIntent)
                            localStatusMessage = "Starting camera acquisition..."
                        } catch (e: Exception) {
                            Log.e(TAG, "[CameraTestHarness] Failed to start simulation", e)
                            localStatusMessage = "Failed to start simulation: ${e.message}"
                        }
                    },
                    enabled = !isServiceRunning && !isCameraActive && !isTransitioning,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(
                        if (currentScenario == ExperimentScenario.AUTOMATED_BACKGROUND_TRIGGER) "ARM TRIGGER (5s)" else "START EXPERIMENT",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        if (!isServiceRunning && !isCameraActive) {
                            localStatusMessage = "No active camera simulation to stop."
                            return@OutlinedButton
                        }

                        Log.i(TAG, "[CameraTestHarness] Stop requested")
                        ExperimentLogger.recordEvent(
                            userAction = "USER_PRESSED_STOP",
                            activityState = "RESUMED",
                            appVisibility = "FOREGROUND"
                        )
                        ExperimentLogger.stopSession()

                        try {
                            val stopIntent = Intent(context, CameraTestService::class.java).apply {
                                action = CameraTestService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                            localStatusMessage = "Stopping camera simulation..."
                        } catch (e: Exception) {
                            Log.e(TAG, "[CameraTestHarness] Failed to stop simulation", e)
                            localStatusMessage = "Failed to stop simulation: ${e.message}"
                        }
                    },
                    enabled = isServiceRunning || isCameraActive || isTransitioning,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("STOP EXPERIMENT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            // Dataset Export and Management Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (recordedList.isEmpty()) {
                            Toast.makeText(context, "No experiment records to export", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        try {
                            val validation = ExperimentDataValidator.validateRecords(recordedList)
                            val file = ExperimentDataExporter.exportToFile(context, recordedList)
                            val csvString = ExperimentDataExporter.exportToCsvString(recordedList)

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_SUBJECT, "CameraGuard Experiment Dataset (${file.name})")
                                putExtra(Intent.EXTRA_TEXT, csvString)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export Experiment CSV"))
                            localStatusMessage = "Exported ${recordedList.size} records to ${file.name}. Valid: ${validation.isValid}"
                        } catch (e: Exception) {
                            Log.e(TAG, "[CameraTestHarness] Export error", e)
                            localStatusMessage = "Export failed: ${e.message}"
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("EXPORT CSV ($recordCount)", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        ExperimentLogger.clearSession()
                        localStatusMessage = "Experiment dataset cleared."
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("CLEAR DATASET", fontSize = 12.sp)
                }
            }

            // Jury Note Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Phase 4.2 Controlled Dataset Expansion",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Generates structured ground-truth records across 8 controlled test scenarios with repetition tracking. Supports automated background triggers without user interaction while keeping CameraGuard production code untouched.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
