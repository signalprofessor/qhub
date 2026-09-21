package com.signalprofessor.qhub.eastwing

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "EastWing Qhub\nPlatform foundation"
                textSize = 22f
                gravity = Gravity.CENTER
            },
        )
    }
}
