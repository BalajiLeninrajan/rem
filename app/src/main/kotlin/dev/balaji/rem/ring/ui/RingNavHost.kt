package dev.balaji.rem.ring.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.balaji.rem.di.LocalAppContainer

/**
 * Navigation graph of the ringing activity.
 *
 * Each tap that opens the scanner or the typed fallback starts a quiet period. The tap does
 * it, not the opened screen, so a scanner that comes back from saved state after a process
 * death leaves the resumed ring loud.
 */
@Composable
fun RingNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val controller = LocalAppContainer.current.ringController
    NavHost(navController = navController, startDestination = RingingRoute, modifier = modifier) {
        composable<RingingRoute> {
            RingingScreen(
                onOpenScanner = {
                    controller.startQuietPeriod()
                    navController.navigate(RingScannerRoute) { launchSingleTop = true }
                },
                onOpenTypedFallback = {
                    controller.startQuietPeriod()
                    navController.navigate(TypedFallbackRoute) { launchSingleTop = true }
                },
            )
        }
        composable<RingScannerRoute> {
            RingScannerScreen(
                // Back from the typed fallback returns to the ringing screen, not to a camera that does not work.
                onOpenTypedFallback = {
                    controller.startQuietPeriod()
                    navController.navigate(TypedFallbackRoute) {
                        popUpTo(RingingRoute)
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<TypedFallbackRoute> {
            TypedFallbackScreen(onBack = { navController.popBackStack() })
        }
    }
}
