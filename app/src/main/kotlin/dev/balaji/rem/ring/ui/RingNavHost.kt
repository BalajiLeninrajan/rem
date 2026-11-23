package dev.balaji.rem.ring.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/** Navigation graph of the ringing activity. */
@Composable
fun RingNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = RingingRoute, modifier = modifier) {
        composable<RingingRoute> {
            RingingScreen(
                onOpenScanner = { navController.navigate(RingScannerRoute) { launchSingleTop = true } },
                onOpenTypedFallback = { navController.navigate(TypedFallbackRoute) { launchSingleTop = true } },
            )
        }
        composable<RingScannerRoute> {
            RingScannerScreen(
                // Back from the typed fallback returns to the ringing screen, not to a camera that does not work.
                onOpenTypedFallback = {
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
