package com.tcgscanner.offline

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import com.tcgscanner.offline.ui.AppRoot
import com.tcgscanner.offline.ui.LocalContainer
import com.tcgscanner.offline.ui.theme.TcgTheme

/** AppCompatActivity (not plain ComponentActivity) so AppCompatDelegate.setApplicationLocales can switch language live. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TcgApp).container
        // Consent first; the banner and the Mobile Ads SDK only start once the consent state allows it.
        container.ads.gather(this)
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                TcgTheme { AppRoot() }
            }
        }
    }
}
