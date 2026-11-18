package com.kamilake.cbtoggle

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Phase {
        data object NoShizuku : Phase
        data object NeedPermission : Phase
        data object PermissionDenied : Phase
        data object Connecting : Phase
        data object Ready : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.NoShizuku)
        private set
    var users by mutableStateOf<List<UserState>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var service: ICellBroadcastService? = null

    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(app, CellBroadcastService::class.java))
        .daemon(false)
        .processNameSuffix("service")
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            service = ICellBroadcastService.Stub.asInterface(binder)
            phase = Phase.Ready
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            if (phase == Phase.Ready) phase = Phase.Connecting
        }
    }

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { onShizukuAvailable() }
    private val onBinderDead = Shizuku.OnBinderDeadListener {
        service = null
        phase = Phase.NoShizuku
    }
    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PERMISSION_GRANTED) bind() else phase = Phase.PermissionDenied
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
    }

    fun retry() {
        if (Shizuku.pingBinder()) onShizukuAvailable() else phase = Phase.NoShizuku
    }

    fun requestPermission() {
        Shizuku.requestPermission(0)
    }

    fun refresh() = launchOp { it.query() }

    fun setEnabled(enabled: Boolean) = launchOp {
        it.setEnabled(enabled)
        it.query()
    }

    private fun onShizukuAvailable() {
        phase = when {
            Shizuku.isPreV11() -> Phase.NoShizuku
            Shizuku.checkSelfPermission() == PERMISSION_GRANTED -> return bind()
            Shizuku.shouldShowRequestPermissionRationale() -> Phase.PermissionDenied
            else -> Phase.NeedPermission
        }
    }

    private fun bind() {
        phase = Phase.Connecting
        Shizuku.bindUserService(serviceArgs, connection)
    }

    private fun launchOp(block: (ICellBroadcastService) -> List<UserState>) {
        val s = service ?: return
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                users = withContext(Dispatchers.IO) { block(s) }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(onBinderReceived)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermissionResult)
        if (service != null && Shizuku.pingBinder()) {
            Shizuku.unbindUserService(serviceArgs, connection, true)
        }
    }
}
