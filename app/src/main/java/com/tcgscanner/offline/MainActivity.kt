package com.tcgscanner.offline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.tcgscanner.offline.ui.AppRoot
import com.tcgscanner.offline.ui.LocalContainer
import com.tcgscanner.offline.ui.theme.TcgTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TcgApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                TcgTheme { AppRoot() }
            }
        }
    }
}
