package edu.buaa.signtool

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.buaa.signtool.data.NetworkMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val palette = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFFF5F5F5),
                onPrimary = Color(0xFF111111),
                background = Color(0xFF111111),
                surface = Color(0xFF171717),
                onSurface = Color(0xFFF5F5F5),
                surfaceVariant = Color(0xFF292929),
                onSurfaceVariant = Color(0xFFBDBDBD),
                outline = Color(0xFF888888),
                outlineVariant = Color(0xFF3A3A3A),
                secondary = Color(0xFF63D99B),
                onSecondary = Color(0xFF10251A),
                error = Color(0xFFFFB4AB),
            ) else lightColorScheme(
                primary = Color(0xFF111111),
                onPrimary = Color.White,
                background = Color.White,
                surface = Color.White,
                onSurface = Color(0xFF111111),
                surfaceVariant = Color(0xFFF4F4F4),
                onSurfaceVariant = Color(0xFF646464),
                outline = Color(0xFF747474),
                outlineVariant = Color(0xFFE7E7E7),
                secondary = Color(0xFF16834A),
                onSecondary = Color.White,
                error = Color(0xFFB3261E),
            )
            MaterialTheme(colorScheme = palette) {
                val scheduleViewModel: ScheduleViewModel = viewModel()
                val state by scheduleViewModel.state.collectAsState()
                if (state.loggedIn) {
                    ScheduleScreen(state, scheduleViewModel)
                } else {
                    LoginScreen(state, scheduleViewModel)
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(state: ScheduleUiState, model: ScheduleViewModel) {
    val colors = MaterialTheme.colorScheme
    val Ink = colors.onSurface
    val Muted = colors.onSurfaceVariant
    val Paper = colors.surface
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    val captcha = state.captcha
    BackHandler(enabled = captcha != null && !state.loading, onBack = model::cancelCaptcha)
    Scaffold(containerColor = Paper) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.Center) {
          Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
          ) {
            Text("BUAA", fontSize = 13.sp, color = Muted, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            Text("课程签到", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text("使用统一身份认证登录 iClass", color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(28.dp))
            if (captcha != null) {
                CaptchaForm(
                    captcha = captcha,
                    busy = state.loading,
                    refreshing = state.refreshingCaptcha,
                    onRefresh = model::refreshCaptcha,
                    onSubmit = model::submitCaptcha,
                    onCancel = model::cancelCaptcha,
                )
                return@Column
            }
            Text("连接方式", fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChoice("校园网", state.networkMode == NetworkMode.CAMPUS) { model.setNetworkMode(NetworkMode.CAMPUS) }
                ModeChoice("WebVPN", state.networkMode == NetworkMode.WEB_VPN) { model.setNetworkMode(NetworkMode.WEB_VPN) }
            }
            if (state.savedLogin != null) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceVariant,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("已记住登录账号", fontSize = 13.sp, color = Muted)
                        Text(state.savedLogin.username, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(top = 4.dp))
                        Text("密码使用系统密钥加密保存在本机", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                        Button(
                            onClick = model::loginSaved,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            if (state.loading) CircularProgressIndicator(Modifier.size(19.dp), color = Paper, strokeWidth = 2.dp)
                            else Text("一键登录")
                        }
                        TextButton(onClick = model::clearSavedLogin, modifier = Modifier.align(Alignment.End)) {
                            Text("清除记忆", color = Muted)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("使用其他账号登录", fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(18.dp))
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("统一认证账号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (state.message != null) {
                Spacer(Modifier.height(12.dp))
                Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { model.login(username, password) },
                enabled = !state.loading && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Paper, strokeWidth = 2.dp)
                else Text("登录 iClass")
            }
            Spacer(Modifier.height(18.dp))
            Text("登录成功后会记住此账号；密码仅以系统密钥加密保存在本机。", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = {
                DatePickerDialog(
                    context,
                    { _, year, month, day -> model.setSemesterBaseline(LocalDate.of(year, month + 1, day)) },
                    state.semesterBaseline.year,
                    state.semesterBaseline.monthValue - 1,
                    state.semesterBaseline.dayOfMonth,
                ).show()
            }) {
                androidx.compose.material3.Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("学期基准日：${state.semesterBaseline.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))}")
            }
          }
        }
    }
}

@Composable
private fun ModeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(7.dp),
        color = if (selected) colors.primary else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Text(label, color = if (selected) colors.onPrimary else colors.onSurface, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp), fontSize = 14.sp)
    }
}
