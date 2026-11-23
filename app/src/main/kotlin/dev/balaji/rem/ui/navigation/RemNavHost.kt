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
 * they belong to the ringing activity. Navigation Compose runs the predictive back
 * animation between destinations.
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
                onAddAlarm = { navController.open(AlarmEditorRoute(Alarm.NEW_ID)) },
                onEditAlarm = { alarmId -> navController.open(AlarmEditorRoute(alarmId)) },
                onOpenBarcodes = { navController.open(BarcodesRoute) },
                onOpenSettings = { navController.open(SettingsRoute) },
                onOpenReliability = { navController.open(ReliabilityRoute) },
                onOpenOnboarding = { navController.open(OnboardingRoute) },
            )
        }
        composable<AlarmEditorRoute> { entry ->
            AlarmEditorScreen(
                alarmId = entry.toRoute<AlarmEditorRoute>().alarmId,
                onDone = navController::back,
            )
        }
        composable<BarcodesRoute> {
            BarcodesScreen(
                onScan = { testOnly -> navController.open(BarcodeScanRoute(testOnly)) },
                onBack = navController::back,
            )
        }
        composable<BarcodeScanRoute> { entry ->
            BarcodeScanScreen(
                testOnly = entry.toRoute<BarcodeScanRoute>().testOnly,
                onDone = navController::back,
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = navController::back)
        }
        composable<ReliabilityRoute> {
            ReliabilityScreen(
                onOpenBarcodes = { navController.open(BarcodesRoute) },
                onBack = navController::back,
            )
        }
        composable<OnboardingRoute> {
            OnboardingScreen(
                onDone = navController::back,
                onAddBarcode = {
                    navController.navigate(BarcodesRoute) {
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                },
            )
        }
    }
}

/** A second tap during the transition must not push the same screen twice. */
private fun NavHostController.open(route: Any) {
    navigate(route) { launchSingleTop = true }
}

/** Pops one screen and never the start destination, so a double tap on back cannot empty the stack. */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
}
