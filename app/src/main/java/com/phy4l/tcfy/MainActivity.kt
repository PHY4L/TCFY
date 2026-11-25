package com.phy4l.tcfy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.phy4l.tcfy.ui.TcfyApp
import com.phy4l.tcfy.ui.theme.TcfyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TcfyTheme {
                TcfyApp()
            }
        }
    }
}
