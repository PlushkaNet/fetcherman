package plushkanet.fetcherman

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import plushkanet.fetcherman.ui.theme.FetchermanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configNight =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge()
        window.setBackgroundDrawable(
            ColorDrawable(if (configNight) Color.BLACK else Color.WHITE)
        )
        setContent {
            val systemDark = isSystemInDarkTheme()
            val prefs = remember { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
            var darkTheme by rememberSaveable {
                mutableStateOf(prefs.getBoolean(KEY_DARK_THEME, systemDark))
            }
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    prefs.edit().putBoolean(KEY_DARK_THEME, darkTheme).apply()
                    val window = (view.context as Activity).window
                    window.setBackgroundDrawable(
                        ColorDrawable(if (darkTheme) Color.BLACK else Color.WHITE)
                    )
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                        !darkTheme
                }
            }
            FetchermanTheme(darkTheme = darkTheme) {
                FetchermanScreen(
                    darkTheme = darkTheme,
                    onToggleTheme = { darkTheme = !darkTheme },
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "fetcherman_settings"
        private const val KEY_DARK_THEME = "dark_theme"
    }
}
