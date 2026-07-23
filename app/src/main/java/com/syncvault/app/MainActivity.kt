// مسار: java/com/syncvault/app/MainActivity.kt

package com.syncvault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // متغير للتحكم في الشاشة: true = الرئيسية، false = فك التشفير
            var showMain by remember { mutableStateOf(true) }

            if (showMain) {
                MainScreen(
                    onNavigateToDecrypt = { showMain = false }
                )
            } else {
                DecryptScreen(
                    onNavigateBack = { showMain = true }
                )
            }
        }
    }
}
