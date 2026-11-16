package dev.balaji.rem.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.ui.alarm.AlarmEditorScreen
import dev.balaji.rem.ui.barcodes.BarcodeScanScreen
import dev.balaji.rem.ui.barcodes.BarcodesScreen
import dev.balaji.rem.ui.home.HomeScreen
import dev.balaji.rem.ui.onboarding.OnboardingScreen
import dev.balaji.rem.ui.reliability.ReliabilityScreen
import dev.balaji.rem.ui.settings.SettingsScreen

/**
 * Navigation graph of [dev.balaji.rem.MainActivity]. The ringing screens are not here,
 * they belong to the ringing activity.
 *
 * @param linkedRoute a route from [dev.balaji.rem.ui.AppLinks.routeFor] to open on top of home, or null.
 */
@Composable
fun RemNavHost(
    modifier: Modifier = Modifier,
    linkedRoute: Any? = null,
    navController: NavHostController = rememberNavController(),
) {
    LaunchedEffect(linkedRoute) {
        if (linkedRoute != null) navController.navigate(linkedRoute)
    }

    NavHost(navController = navController, startDestination = HomeRoute, modifier = modifier) {
        composable<HomeRoute> {
            HomeScreen(
                onAddAlarm = { navController.navigate(AlarmEditorRoute(Alarm.NEW_ID)) },
                onEditAlarm = { alarmId -> navController.navigate(AlarmEditorRoute(alarmId)) },
                onOpenBarcodes = { navController.navigate(BarcodesRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenReliability = { navController.navigate(ReliabilityRoute) },
                onOpenOnboarding = { navController.navigate(OnboardingRoute) },
            )
        }
        composable<AlarmEditorRoute> { entry ->
            AlarmEditorScreen(
                alarmId = entry.toRoute<AlarmEditorRoute>().alarmId,
                onDone = { navController.popBackStack() },
            )
        }
        composable<BarcodesRoute> {
            BarcodesScreen(
                onScan = { testOnly -> navController.navigate(BarcodeScanRoute(testOnly)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<BarcodeScanRoute> { entry ->
            BarcodeScanScreen(
                testOnly = entry.toRoute<BarcodeScanRoute>().testOnly,
                onDone = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable<ReliabilityRoute> {
            ReliabilityScreen(
                onOpenBarcodes = { navController.navigate(BarcodesRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<OnboardingRoute> {
            OnboardingScreen(onDone = { navController.popBackStack() })
        }
    }
}
