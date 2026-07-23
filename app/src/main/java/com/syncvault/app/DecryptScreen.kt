package com.syncvault.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private fun displayName(uriString: String?): String {
    if (uriString == null) return "غير محدد"
    return Uri.parse(uriString).lastPathSegment ?: uriString
}

@Composable
fun DecryptScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // حالة المجلدات
    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var destUri by remember { mutableStateOf<Uri?>(null) }

    // حالة المدخلات
    var password by remember { mutableStateOf("") }
    var salt by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // حالة التشغيل والسجل
    var isRunning by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    val logLines = remember { mutableStateListOf<String>() }

    // اختيار مجلد المصدر (الذي يحتوي على ملفات .enc)
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            sourceUri = it
        }
    }

    // اختيار مجلد الوجهة (حيث سيتم حفظ الملفات المفككة)
    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            destUri = it
        }
    }

    // واجهة المستخدم
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // عنوان ورجوع
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onNavigateBack) {
                Text("رجوع")
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("فك التشفير", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // حقل كلمة المرور
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("كلمة المرور :") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) "إخفاء" else "إظهار")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // حقل الملح (Salt)
        OutlinedTextField(
            value = salt,
            onValueChange = { salt = it },
            label = { Text("الملح (Salt) - بصيغة Base64") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth()
        )
        // ✅ التصحيح هنا: استخدمنا `text =` بدلاً من `Text=`، وأضفنا فاصلة قبل `style`
        Text(
            text = "أدخل قيمة الملح المستخرجة من ملف syncvault.salt",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        // اختيار مجلد المصدر
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مجلد المصدر (.enc): ", modifier = Modifier.weight(0.4f))
            Text(
                text = displayName(sourceUri?.toString()),
                modifier = Modifier.weight(0.4f),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { sourcePicker.launch(null) }, modifier = Modifier.weight(0.2f)) {
                Text("اختيار")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // اختيار مجلد الوجهة
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("مجلد الوجهة", modifier = Modifier.weight(0.4f))
            Text(
                text = displayName(destUri?.toString()),
                modifier = Modifier.weight(0.4f),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { destPicker.launch(null) }, modifier = Modifier.weight(0.2f)) {
                Text("اختيار")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // أزرار التشغيل
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canStart = !isRunning && sourceUri != null && destUri != null &&
                    password.isNotBlank() && salt.isNotBlank()

            Button(
                onClick = {
                    if (canStart) {
                        isRunning = true
                        logLines.clear()
                        progressText = "جاري المعالجة..."

                        scope.launch {
                            try {
                                val result = DecryptEngine.decrypt(
                                    context = context,
                                    sourceUri = sourceUri!!,
                                    destUri = destUri!!,
                                    password = password,
                                    saltBase64 = salt,
                                    onLog = { msg ->
                                        logLines.add(msg)
                                        if (logLines.size > 200) logLines.removeAt(0)
                                    },
                                    onProgress = { current, total ->
                                        progressText = "تمت معالجة $current من $total"
                                    }
                                )
                                progressText = "تم فك التشفير: نجاح ${result.success}، فشل ${result.failed}"
                            } catch (e: Exception) {
                                logLines.add("خطأ جسيم: ${e.message}")
                                progressText = "فشلت العملية"
                            } finally {
                                isRunning = false
                            }
                        }
                    }
                },
                enabled = canStart
            ) {
                Text(if (isRunning) "جاري..." else "🔓 فك التشفير")
            }

            if (isRunning) {
                Button(onClick = { /* يمكن إضافة إلغاء هنا لاحقاً */ }) {
                    Text("إلغاء")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // عرض التقدم
        if (progressText.isNotEmpty()) {
            Text(progressText, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
        }

        // السجل (Log)
        Text("سجل العمليات:", style = MaterialTheme.typography.titleSmall)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp, max = 250.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (logLines.isEmpty()) {
                Text("لا توجد عمليات مسجلة.", style = MaterialTheme.typography.bodySmall)
            } else {
                logLines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}