package com.manas.tvremote

import android.os.Bundle
import android.view.KeyEvent
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.manas.tvremote.ui.App

class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { App(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    /** The phone's own volume buttons control the TV while the remote is open. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (vm.paired) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { vm.key(Keys.VOL_UP); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { vm.key(Keys.VOL_DOWN); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (vm.paired && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
