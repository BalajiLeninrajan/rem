package dev.balaji.rem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.balaji.rem.di.LocalAppContainer
import dev.balaji.rem.di.appContainer
import dev.balaji.rem.ui.AppLinks
import dev.balaji.rem.ui.navigation.RemNavHost
import dev.balaji.rem.ui.theme.RemTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a configuration change the back stack is restored, so the link is not followed twice.
        val linkedRoute = if (savedInstanceState == null) AppLinks.routeFor(intent) else null
        setContent {
            RemTheme {
                CompositionLocalProvider(LocalAppContainer provides appContainer) {
                    RemNavHost(linkedRoute = linkedRoute)
                }
            }
        }
    }
}
