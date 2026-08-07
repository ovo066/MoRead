package com.mozhi.reader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.backup.BackupSettingsStore
import com.mozhi.reader.core.backup.WebDavBackupScheduler
import com.mozhi.reader.ui.MoReadApp
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.MoReadTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: ReaderSettingsRepository

    @Inject
    lateinit var backupSettingsStore: BackupSettingsStore

    private val incomingBookUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptBookIntent(intent)
        // 导航栏一律真透明：默认的 auto 样式会给三键/手势条垫半透明对比底，
        // 在浅色页面上就是 dock 胶囊底下那条白色横条。
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        // 兜底直写：绕开 SystemBarStyle.auto 在 API 29+ 的「交给系统决定」语义，
        // 部分 ROM（MIUI 等）只认这个字段。API 35 起标记废弃但仍生效。
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        // vivo（OriginOS）对未适配名单里的应用会无视透明色、强制画白色导航条，
        // 但尊重老的 translucent 标记——只对 vivo 系下这剂猛药。
        if (Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        }
        // 首帧用默认外观，DataStore 读到值后再重组一次——避免为等 IO 而闪白屏。
        val appearance = settingsRepository.appearance.stateIn(
            scope = lifecycleScope,
            started = SharingStarted.Eagerly,
            initialValue = AppearanceSettings()
        )
        lifecycleScope.launch {
            WebDavBackupScheduler.update(
                this@MainActivity,
                backupSettingsStore.current().autoBackup
            )
        }
        setContent {
            val current by appearance.collectAsStateWithLifecycle()
            val incoming by incomingBookUri.collectAsStateWithLifecycle()
            MoReadTheme(appearance = current) {
                MoReadApp(
                    incomingBookUri = incoming,
                    onIncomingBookConsumed = {
                        incomingBookUri.value = null
                        intent = Intent(this, MainActivity::class.java)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptBookIntent(intent)
    }

    private fun acceptBookIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java
            )
            else -> null
        } ?: return
        // 外部授权通常只保证本次 Activity 生命周期；导入协调器收到后会立即复制到私有目录。
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                (intent?.flags ?: 0) and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            )
        }
        incomingBookUri.value = uri
    }
}
