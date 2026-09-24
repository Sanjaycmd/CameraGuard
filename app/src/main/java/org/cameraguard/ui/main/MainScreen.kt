package org.cameraguard.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import org.cameraguard.History
import org.cameraguard.data.db.CameraGuardDatabase
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.repository.DefaultCameraEventRepository
import org.cameraguard.monitoring.CameraMonitor
import org.cameraguard.monitoring.service.CameraMonitoringService
import org.cameraguard.monitoring.telemetry.ContextualInferenceEngine
import org.cameraguard.testing.SyntheticEventGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    onNavigate: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel {
        val context = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Context
        val db = CameraGuardDatabase.getInstance(context)
        val repo = DefaultCameraEventRepository(db.cameraEventDao())
        val monitor = CameraMonitor.getInstance(context)
        MainScreenViewModel(repo, monitor, context)
    }
) {
    val context = LocalContext.current
    val isMonitoring by viewModel.isMonitoring.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val unexpectedCount by viewModel.unexpectedCount.collectAsStateWithLifecycle()
    val expectedCount by viewModel.expectedCount.collectAsStateWithLifecycle()
    val latestEvent by viewModel.latestEvent.collectAsStateWithLifecycle()
    val selectedWindowMs by viewModel.selectedWindowMs.collectAsStateWithLifecycle()

    val inferenceEngine = remember { ContextualInferenceEngine(context) }
    var hasUsageAccess by remember { mutableStateOf(inferenceEngine.hasUsageAccessPermission()) }

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
    }

    // Refresh permissions on resume
    LaunchedEffect(Unit) {
        hasUsageAccess = inferenceEngine.hasUsageAccessPermission()
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title
        Text(
            text = "CameraGuard",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Context-Aware Camera Privacy Framework",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 1. Monitoring Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Background Monitoring",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isMonitoring) "Status: ACTIVE (Foreground Service)" else "Status: STOPPED",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMonitoring) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isMonitoring) {
                        Button(
                            onClick = { CameraMonitoringService.stopService(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Stop Service")
                        }
                    } else {
                        Button(
                            onClick = { CameraMonitoringService.startService(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Start Service")
                        }
                    }
                }
            }
        }

        // 2. Metrics Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Event Telemetry Overview",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricBox(title = "Total Events", value = totalCount.toString(), color = MaterialTheme.colorScheme.primary)
                    MetricBox(title = "Unexpected", value = unexpectedCount.toString(), color = Color(0xFFC62828))
                    MetricBox(title = "Expected", value = expectedCount.toString(), color = Color(0xFF2E7D32))
                }

                Button(
                    onClick = { onNavigate(History) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("View Detailed Event History →")
                }
            }
        }

        // 3. Latest Event Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Latest Observed Transition",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (latestEvent != null) {
                    val event = latestEvent!!
                    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    val timeStr = dateFormat.format(Date(event.timestamp))

                    Text(
                        text = "${event.rawEventType.name} at $timeStr",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Inferred App: ${event.inferredPackageName ?: "None"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Classification: ${event.classification.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (event.classification) {
                            AccessClassification.UNEXPECTED -> Color(0xFFC62828)
                            AccessClassification.EXPECTED -> Color(0xFF2E7D32)
                            AccessClassification.UNKNOWN -> Color(0xFFF57F17)
                        },
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "No transitions logged yet. Start the service or trigger test events below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 4. Required Permissions & Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Permissions & Privacy Context",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "• Notification Alerts: ${if (hasNotificationPermission) "GRANTED" else "NOT GRANTED"}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant Notification Permission")
                    }
                }

                Text(
                    text = "• Usage Statistics Access: ${if (hasUsageAccess) "GRANTED" else "NOT GRANTED"}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!hasUsageAccess) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable Usage Access in Settings")
                    }
                }
            }
        }

        // 5. Testability: Synthetic Event Pipeline
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Research & Test Pipeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Inject controlled synthetic events (isSynthetic = true) to verify the rule classifier, Room persistence, and notifications without spoofing real hardware:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = {
                        val event = SyntheticEventGenerator.createScreenOffUnexpectedEvent()
                        viewModel.injectSyntheticEvent(event)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simulate: Screen-Off Camera Access (Unexpected)")
                }

                OutlinedButton(
                    onClick = {
                        val event = SyntheticEventGenerator.createNormalExpectedEvent()
                        viewModel.injectSyntheticEvent(event)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simulate: Normal Camera App Usage (Expected)")
                }

                OutlinedButton(
                    onClick = {
                        val event = SyntheticEventGenerator.createUnknownContextEvent()
                        viewModel.injectSyntheticEvent(event)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Simulate: Inconclusive Telemetry (Unknown)")
                }

                Button(
                    onClick = { viewModel.clearSyntheticEvents() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Purge Only Synthetic Test Events")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MetricBox(
    title: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
