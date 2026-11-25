package com.animeshahilya.geminitts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.animeshahilya.geminitts.ui.main.MainScreen
import com.animeshahilya.geminitts.ui.theme.GeminiTtsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GeminiTtsTheme { MainScreen() } }
    }
}
