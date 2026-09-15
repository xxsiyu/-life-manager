package com.example.medicinekeeper

import android.Manifest
import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.medicinekeeper.data.AppDatabase
import com.example.medicinekeeper.data.Medicine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "expiry_check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
        )
        setContent { MedicineKeeperApp() }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MedicineKeeperApp() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).medicineDao() }
    var isBookMode by remember { mutableStateOf(false) }
    val itemType = if (isBookMode) "book" else "medicine"
    val medicines by dao.observeByType(itemType).collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var showEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Medicine?>(null) }
    var deleteTarget by remember { mutableStateOf<Medicine?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val displayedMedicines by remember(medicines, searchQuery) {
        derivedStateOf {
            val keyword = searchQuery.trim()
            if (keyword.isEmpty()) medicines
            else medicines.filter { it.name.contains(keyword, ignoreCase = true) }
                .sortedWith(compareBy<Medicine>({ if (it.name.startsWith(keyword, ignoreCase = true)) 0 else 1 }, { it.name.indexOf(keyword, ignoreCase = true) }, { it.expiryDate }))
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) { }
    LaunchedEffect(Unit) { if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (isBookMode) "生活管家 · 书籍" else "生活管家 · 药品", fontWeight = FontWeight.Bold, modifier = Modifier.clickable { isBookMode = !isBookMode; searchQuery = "" }) }, actions = {
                IconButton(onClick = { showEditor = true }) { Icon(Icons.Default.Add, if (isBookMode) "添加书籍" else "添加药品") }
            }) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, "搜索") },
                    label = { Text(if (isBookMode) "搜索书籍名称" else "搜索药品名称") }, placeholder = { Text("输入任意文字搜索") }
                )
                if (medicines.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (isBookMode) "还没有书籍\n点击右上角 + 添加" else "还没有药品\n点击右上角 + 添加", style = MaterialTheme.typography.titleMedium)
                } else if (displayedMedicines.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到包含“${searchQuery.trim()}”的${if (isBookMode) "书籍" else "药品"}")
                } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Spacer(Modifier.height(4.dp)) }
                items(displayedMedicines, key = { it.id }) { medicine -> MedicineRow(medicine, isBookMode, onLongClick = { editTarget = medicine }, onDelete = { deleteTarget = medicine })
                }
                }
            }
        }
        if (showEditor) MedicineEditor(isBookMode = isBookMode, onDismiss = { showEditor = false }, onSave = { medicine ->
            scope.launch { dao.insert(medicine); showEditor = false }
        })
        editTarget?.let { medicine -> MedicineEditor(initial = medicine, isBookMode = medicine.itemType == "book", onDismiss = { editTarget = null }, onSave = { updated ->
            scope.launch { dao.insert(updated); editTarget = null }
        }) }
        deleteTarget?.let { medicine -> AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除药品？") }, text = { Text("确定删除“${medicine.name}”吗？") },
            confirmButton = { TextButton(onClick = { scope.launch { dao.delete(medicine.id); deleteTarget = null } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        ) }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MedicineRow(medicine: Medicine, isBookMode: Boolean, onLongClick: () -> Unit, onDelete: () -> Unit) {
    val formatter = remember { SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA) }
    val days = ((medicine.expiryDate - System.currentTimeMillis()) / TimeUnit.DAYS.toMillis(1)).toInt()
    val status = when { days < 0 -> "已过期"; days <= 7 -> "${days} 天后到期"; else -> "有效期至 ${formatter.format(Date(medicine.expiryDate))}" }
    val color = if (days <= 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onLongClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            medicine.photo?.let { bytes ->
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(), contentDescription = "药品照片",
                        modifier = Modifier.size(72.dp).padding(end = 12.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) { Text(medicine.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("数量：${medicine.quantity}"); if (!isBookMode) Text(status, color = color) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除药品", tint = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun MedicineEditor(initial: Medicine? = null, isBookMode: Boolean = false, onDismiss: () -> Unit, onSave: (Medicine) -> Unit) {
    val context = LocalContext.current
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }; var quantity by remember(initial?.id) { mutableStateOf(initial?.quantity?.toString().orEmpty()) }
    var production by remember(initial?.id) { mutableStateOf(initial?.productionDate ?: System.currentTimeMillis()) }
    var shelfLife by remember(initial?.id) { mutableStateOf((initial?.shelfLifeValue?.takeIf { it > 0 } ?: initial?.shelfLifeDays ?: 1).toString()) }
    var shelfLifeUnit by remember(initial?.id) { mutableStateOf(initial?.shelfLifeUnit ?: "年") }; var photo by remember(initial?.id) { mutableStateOf(initial?.photo) }
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA) }
    fun calculateExpiry(): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = production }
        when (shelfLifeUnit) {
            "年" -> calendar.add(Calendar.YEAR, shelfLife.toIntOrNull() ?: 0)
            "月" -> calendar.add(Calendar.MONTH, shelfLife.toIntOrNull() ?: 0)
            else -> calendar.add(Calendar.DAY_OF_YEAR, shelfLife.toIntOrNull() ?: 0)
        }
        return calendar.timeInMillis
    }
    val expiry = calculateExpiry()
    fun pickDate(initial: Long, update: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(context, { _, y, m, d ->
            val selected = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            update(selected.timeInMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun usePhoto(bitmap: Bitmap) {
        photo = bitmap.toBytes()
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { text ->
            val lines = text.text.lines().filter { it.isNotBlank() }; if (lines.isNotEmpty()) name = lines.first()
            Regex("\\d+").find(text.text)?.value?.let { quantity = it }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> bitmap?.let(::usePhoto) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { selectedUri ->
            context.contentResolver.openInputStream(selectedUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let(::usePhoto)
            }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initial == null) "添加${if (isBookMode) "书籍" else "药品"}" else "修改${if (isBookMode) "书籍" else "药品"}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { camera.launch(null) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("拍照") }
                OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text("从相册选择") }
            }
            photo?.let { bytes ->
                val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                if (bitmap != null) {
                    Text("照片预览")
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(), contentDescription = "药品照片预览",
                        modifier = Modifier.fillMaxWidth().height(160.dp)
                    )
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text(if (isBookMode) "书籍名称" else "药品名称") }, singleLine = true)
            OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("数量") }, singleLine = true)
            if (!isBookMode) {
                OutlinedButton(onClick = { pickDate(production) { production = it } }, Modifier.fillMaxWidth()) { Text("生产日期：${formatter.format(Date(production))}") }
                OutlinedTextField(shelfLife, { shelfLife = it.filter(Char::isDigit) }, label = { Text("保质期") }, singleLine = true)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("天", "月", "年").forEachIndexed { index, unit ->
                        SegmentedButton(selected = shelfLifeUnit == unit, onClick = { shelfLifeUnit = unit }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(unit) }
                    }
                }
                Text("自动计算到期日：${formatter.format(Date(expiry))}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && quantity.isNotBlank() && (isBookMode || (shelfLife.toIntOrNull() ?: 0) > 0), onClick = { onSave(Medicine(id = initial?.id ?: 0, name = name.trim(), quantity = quantity.toInt(), productionDate = if (isBookMode) 0 else production, shelfLifeDays = if (isBookMode) 0 else ((expiry - production) / TimeUnit.DAYS.toMillis(1)).toInt(), shelfLifeValue = if (isBookMode) 0 else shelfLife.toInt(), shelfLifeUnit = if (isBookMode) "" else shelfLifeUnit, expiryDate = if (isBookMode) Long.MAX_VALUE else expiry, photo = photo, itemType = if (isBookMode) "book" else "medicine", reminderSent = initial?.reminderSent ?: false)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun Bitmap.toBytes(): ByteArray = ByteArrayOutputStream().use { output ->
    compress(Bitmap.CompressFormat.JPEG, 85, output)
    output.toByteArray()
}
