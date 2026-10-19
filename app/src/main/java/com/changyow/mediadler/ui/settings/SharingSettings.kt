package com.changyow.mediadler.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.changyow.mediadler.sharing.SharingController
import com.changyow.mediadler.core.sharing.SharingIdleTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun SharingSettings(controller: SharingController) {
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(state.username) }
    var port by remember { mutableStateOf(state.port.toString()) }
    var password by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("分享目前下載資料夾與子目錄，其他裝置可唯讀存取。請連上相同的 Wi-Fi 或有線區網。",
        style = MaterialTheme.typography.bodySmall)
    DropdownSetting(
        label = "閒置自動關閉",
        options = SharingIdleTimeout.entries,
        selected = state.idleTimeout,
        optionLabel = { it.label },
        onSelect = { controller.setIdleTimeout(context, it) },
    )
    Text("UPnP 與 SFTP 共用計時。連續沒有讀取檔案時，兩種分享會一起關閉；讀取會重新計時，搜尋裝置、瀏覽目錄與保持連線不會。變更此設定會重新計時。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("UPnP／DLNA")
            SelectionContainer { Text(state.upnpStatus, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(state.upnpEnabled, { controller.setEnabled(context, upnp = true, enabled = it) })
    }
    Text("播放器可自動找到 media-dler。啟用期間，同區網裝置可瀏覽與播放分享的檔案。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("SFTP")
            SelectionContainer { Text(state.sftpStatus, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(state.sftpEnabled, { controller.setEnabled(context, upnp = false, enabled = it) }, enabled = state.hasPassword && !saving)
    }
    OutlinedTextField(username, { username = it }, label = { Text("SFTP 帳號") }, singleLine = true,
        enabled = !saving, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, { password = it }, label = { Text(if (state.hasPassword) "新密碼（留空保留原密碼）" else "SFTP 密碼（至少 8 字元）") },
        singleLine = true, enabled = !saving, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(port, { port = it }, label = { Text("SFTP 連接埠") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !saving, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = {
        saving = true
        scope.launch {
            try {
                controller.saveLogin(context, username.trim(), password, port.toIntOrNull() ?: 0)
                password = ""
                message = "登入設定已儲存"
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { message = failure.message ?: "無法儲存" }
            finally { saving = false }
        }
    }, enabled = !saving) { Text(if (saving) "儲存中…" else "儲存 SFTP 登入設定") }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text("開啟後可離開此畫面，通知列可停止分享。分享期間會持續使用網路與電力；系統停止 app 後需重新開啟分享。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
