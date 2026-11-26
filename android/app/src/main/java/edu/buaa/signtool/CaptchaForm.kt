package edu.buaa.signtool

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import edu.buaa.signtool.data.LoginCaptcha

@Composable
internal fun CaptchaForm(
    captcha: LoginCaptcha,
    busy: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val image = remember(captcha.revision, captcha.imageBytes) {
        captcha.imageBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    var code by remember(captcha.revision) { mutableStateOf("") }
    val canSubmit = !busy && image != null && code.isNotBlank()
    val submit = { if (canSubmit) onSubmit(code.trim()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("完成图形验证", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
        Text("输入图片中的字符，继续登录。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.weight(1f).height(76.dp),
                shape = shape,
                color = if (image != null && !refreshing) Color.White else colors.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        refreshing -> Text("正在刷新…", style = MaterialTheme.typography.bodyMedium)
                        image == null -> Text("图片未加载", style = MaterialTheme.typography.bodyMedium)
                        else -> Image(
                            bitmap = image,
                            contentDescription = "图形验证码图片",
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), shape = shape) {
                Text("刷新验证码")
            }
        }
        val notice = captcha.message ?: if (image == null) "验证码图片加载失败，请刷新重试。" else null
        if (notice != null && !refreshing) Text(notice, color = colors.error, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.take(32) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && image != null,
            label = { Text("图形验证码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Button(
            onClick = submit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = shape,
        ) {
            if (busy && !refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("验证并登录")
        }
        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
            Text("返回账号登录")
        }
    }
}
