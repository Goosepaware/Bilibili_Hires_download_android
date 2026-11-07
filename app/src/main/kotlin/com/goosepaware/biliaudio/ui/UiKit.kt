package com.goosepaware.biliaudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.goosepaware.biliaudio.data.LinkExtractor
import java.util.Locale

fun fmtSize(bytes: Long): String {
  var v = bytes.toDouble()
  for (unit in listOf("B", "KiB", "MiB", "GiB")) {
    if (v < 1024 || unit == "GiB") {
      return if (unit == "B") "${v.toInt()} B" else String.format(Locale.US, "%.1f %s", v, unit)
    }
    v /= 1024
  }
  return String.format(Locale.US, "%.1f GiB", v)
}

fun fmtDuration(sec: Double): String {
  val s = sec.toInt().coerceAtLeast(0)
  val h = s / 3600
  val m = (s % 3600) / 60
  val ss = s % 60
  return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

/** 从 SAF Uri 读显示名。 */
fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
  context.contentResolver.query(uri, null, null, null, null)?.use { c ->
    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
  }
}.getOrNull()

@Composable
fun PasteField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String = "",
  modifier: Modifier = Modifier,
  minLines: Int = 1,
  maxLines: Int = 1,
  linkOnly: Boolean = false,
) {
  val clipboard = LocalClipboardManager.current
  val applyValue: (String) -> Unit = { text ->
    if (linkOnly) {
      onValueChange(LinkExtractor.extract(text) ?: text.trim())
    } else {
      onValueChange(text)
    }
  }
  Column(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedTextField(
        value = value,
        onValueChange = applyValue,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.weight(1f),
        minLines = minLines,
        maxLines = maxLines,
        singleLine = maxLines == 1,
      )
      TextButton(onClick = {
        val text = clipboard.getText()?.text
        if (!text.isNullOrBlank()) applyValue(text)
      }) { Text("粘贴") }
    }
  }
}

@Composable
fun <T> Dropdown(
  label: String,
  options: List<Pair<String, T>>,
  selected: T?,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  val current = options.firstOrNull { it.second == selected }?.first ?: options.firstOrNull()?.first ?: "-"
  Column(modifier) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = { expanded = true }) {
      Text(current + "  ▾", style = MaterialTheme.typography.bodyMedium)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { (text, value) ->
        DropdownMenuItem(
          text = { Text(text) },
          onClick = {
            onSelect(value)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  OutlinedCard(modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      content()
    }
  }
}

@Composable
fun DoneDialog(
  title: String,
  message: String,
  openLabel: String = "打开文件",
  onOpen: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(message) },
    confirmButton = { TextButton(onClick = { onOpen(); onDismiss() }) { Text(openLabel) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("确定") } },
  )
}
