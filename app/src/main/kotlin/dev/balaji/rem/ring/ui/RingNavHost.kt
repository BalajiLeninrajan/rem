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
                onOpenScanner = { navController.navigate(RingScannerRoute) },
                onOpenTypedFallback = { navController.navigate(TypedFallbackRoute) },
            )
        }
        composable<RingScannerRoute> {
            RingScannerScreen(
                onOpenTypedFallback = { navController.navigate(TypedFallbackRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<TypedFallbackRoute> {
            TypedFallbackScreen(onBack = { navController.popBackStack() })
        }
    }
}
