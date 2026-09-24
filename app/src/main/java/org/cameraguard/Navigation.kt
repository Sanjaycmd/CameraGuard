package org.cameraguard

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.cameraguard.data.db.CameraGuardDatabase
import org.cameraguard.data.repository.DefaultCameraEventRepository
import org.cameraguard.monitoring.CameraMonitor
import org.cameraguard.ui.history.HistoryScreen
import org.cameraguard.ui.main.MainScreen
import org.cameraguard.ui.main.MainScreenViewModel

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Main)
    val context = LocalContext.current

    val sharedViewModel: MainScreenViewModel = viewModel {
        val db = CameraGuardDatabase.getInstance(context)
        val repo = DefaultCameraEventRepository(db.cameraEventDao())
        val monitor = CameraMonitor.getInstance(context)
        MainScreenViewModel(repo, monitor, context)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                MainScreen(
                    onNavigate = { navKey -> backStack.add(navKey) },
                    viewModel = sharedViewModel,
                    modifier = Modifier.safeDrawingPadding().padding(horizontal = 4.dp)
                )
            }
            entry<History> {
                val events by sharedViewModel.recentEvents.collectAsStateWithLifecycle()
                HistoryScreen(
                    events = events,
                    onBackClick = { backStack.removeLastOrNull() },
                    onClearAll = { sharedViewModel.clearAllEvents() },
                    onClearSynthetic = { sharedViewModel.clearSyntheticEvents() },
                    modifier = Modifier.safeDrawingPadding().padding(horizontal = 4.dp)
                )
            }
        }
    )
}
