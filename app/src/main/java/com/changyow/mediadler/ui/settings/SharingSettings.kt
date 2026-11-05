package com.changyow.mediadler.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.clickable
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
import com.changyow.mediadler.core.sharing.WebShareSession
import kotlinx.coroutines.delay
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
    var upnpPort by remember { mutableStateOf(state.upnpPort.toString()) }
    var savingUpnp by remember { mutableStateOf(false) }
    var upnpMessage by remember { mutableStateOf<String?>(null) }
    var chooseWebDuration by remember { mutableStateOf(false) }
    var webMinutes by remember { mutableIntStateOf(WebShareSession.DEFAULT_MINUTES) }
    Text("分享目前下載資料夾與子目錄，其他裝置可唯讀存取。請連上相同的 Wi-Fi 或有線區網。",
        style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("網頁分享")
            SelectionContainer { Text(state.webStatus, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(state.webEnabled, {
            if (it) {
                webMinutes = WebShareSession.DEFAULT_MINUTES
                chooseWebDuration = true
            } else controller.setWebEnabled(context, false)
        })
    }
    Text("其他裝置用瀏覽器即可瀏覽資料夾、下載檔案。免登入，HTTP 未加密；開啟期間，同區網可連線的裝置都能讀取。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.webSession?.let { WebShareRemainingTime(it.expiresAtElapsedMillis) }
    if (state.webEnabled && state.webStatus.startsWith("http://")) {
        OutlinedButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("media-dler 網頁分享", state.webStatus))
        }) { Text("複製網址") }
    }
    if (chooseWebDuration) {
        AlertDialog(onDismissRequest = { chooseWebDuration = false }, title = { Text("分享多久？") },
            text = {
                Column {
                    WebShareSession.DURATIONS_MINUTES.forEach { minutes ->
                        Row(Modifier.fillMaxWidth().clickable { webMinutes = minutes }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = webMinutes == minutes, onClick = { webMinutes = minutes })
                            Text(if (minutes == 60) "1 小時" else "$minutes 分鐘")
                        }
                    }
                    Text("到期會停止分享與傳輸，下載不會延長期限。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                chooseWebDuration = false
                controller.setWebEnabled(context, true, webMinutes)
            }) { Text("開啟分享") } },
            dismissButton = { TextButton(onClick = { chooseWebDuration = false }) { Text("取消") } })
    }
    DropdownSetting(
        label = "UPnP／SFTP 閒置自動關閉",
        options = SharingIdleTimeout.entries,
        selected = state.idleTimeout,
        optionLabel = { it.label },
        onSelect = { controller.setIdleTimeout(context, it) },
    )
    Text("UPnP 與 SFTP 共用閒置計時，與網頁分享的期限分開。連續沒有讀取檔案時，兩種分享會一起關閉；讀取會重新計時，搜尋裝置、瀏覽目錄與保持連線不會。變更此設定會重新計時。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("UPnP／DLNA")
            SelectionContainer { Text(state.upnpStatus, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(state.upnpEnabled, { controller.setEnabled(context, upnp = true, enabled = it) }, enabled = !savingUpnp)
    }
    Text("播放器可自動找到 media-dler。啟用期間，同區網裝置可瀏覽與播放分享的檔案。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(upnpPort, { upnpPort = it; upnpMessage = null }, label = { Text("UPnP 連接埠") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !savingUpnp, modifier = Modifier.fillMaxWidth())
    Text("預設 8192，可設定 1024～65535。儲存後會重新啟動執行中的 UPnP；變更連接埠後，播放器收藏需重新加入。保持手機 IP、連接埠與檔案路徑不變，才能沿用播放網址。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = {
        savingUpnp = true
        scope.launch {
            try {
                controller.saveUpnpPort(context, upnpPort.trim().toIntOrNull() ?: 0)
                upnpPort = controller.state.value.upnpPort.toString()
                upnpMessage = "UPnP 連接埠已儲存"
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { upnpMessage = failure.message ?: "無法儲存" }
            finally { savingUpnp = false }
        }
    }, enabled = !savingUpnp) { Text(if (savingUpnp) "儲存中…" else "儲存 UPnP 連接埠") }
    upnpMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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

@Composable
private fun WebShareRemainingTime(deadline: Long) {
    var seconds by remember(deadline) { mutableLongStateOf(((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000) }
    LaunchedEffect(deadline) {
        while (seconds > 0) {
            delay(1_000)
            seconds = ((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
        }
    }
    Text("剩餘 ${seconds / 60} 分 ${seconds % 60} 秒，到期自動關閉", style = MaterialTheme.typography.bodySmall)
}
