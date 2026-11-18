package com.example.sol.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sol.memory.LocationEntity
import com.example.sol.memory.MemoryFactEntity
import com.example.sol.memory.PersonEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    memories: List<MemoryFactEntity>,
    persons: List<PersonEntity>,
    locations: List<LocationEntity>,
    confirmedCurrentLocation: String,
    onAddOrUpdateAlias: (String, String) -> Unit,
    onAddFact: (String) -> Unit,
    onUpdateMemory: (MemoryFactEntity, String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onSavePerson: (name: String, phone: String, relation: String, note: String, existingId: Long) -> Unit,
    onDeletePerson: (Long) -> Unit,
    onSaveLocation: (alias: String, fullName: String, note: String, existingId: Long) -> Unit,
    onDeleteLocation: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("قوانین و نکات", "اشخاص", "مکان‌ها")

    var showAddDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<MemoryFactEntity?>(null) }
    var editingPerson by remember { mutableStateOf<PersonEntity?>(null) }
    var editingLocation by remember { mutableStateOf<LocationEntity?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("add_memory_fab"),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "افزودن به حافظه")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "حافظهٔ شخصی Sol",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "تمام اطلاعات محلی هستند و هر زمان قابل مشاهده، ویرایش و حذف‌اند.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(label, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    if (memories.isEmpty()) {
                        EmptyMemoryState(
                            icon = Icons.Default.Psychology,
                            title = "هنوز قانونی یا نکته‌ای ذخیره نشده",
                            subtitle = "دکمه + را بزنید یا بگویید: «هر وقت می‌گم دفتر منظورم شرکت ... است»",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(memories, key = { it.id }) { item ->
                                val isAlias = item.kind == "alias"
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (isAlias) {
                                                    "مترادف: «${item.keyText.removePrefix("alias:")}»"
                                                } else {
                                                    "نکتهٔ شخصی"
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (isAlias) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = item.valueText,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                        IconButton(onClick = { editingMemory = item }) {
                                            Icon(Icons.Default.Edit, contentDescription = "ویرایش")
                                        }
                                        IconButton(onClick = { onDeleteMemory(item.id) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "حذف",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (persons.isEmpty()) {
                        EmptyMemoryState(
                            icon = Icons.Default.Person,
                            title = "شخصی در حافظهٔ Sol ثبت نشده",
                            subtitle = "می‌توانید اشخاص، شماره و نحوهٔ معرفی Sol را اینجا اضافه کنید.",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(persons, key = { it.id }) { person ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = person.name + if (person.relation.isNotBlank()) " (${person.relation})" else "",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            if (person.phone.isNotBlank()) {
                                                Text(
                                                    text = "شماره: ${person.phone}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            if (person.callScriptNote.isNotBlank()) {
                                                Text(
                                                    text = "دستور ویژه تماس: ${person.callScriptNote}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        IconButton(onClick = { editingPerson = person }) {
                                            Icon(Icons.Default.Edit, contentDescription = "ویرایش شخص")
                                        }
                                        IconButton(onClick = { onDeletePerson(person.id) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "حذف شخص",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "موقعیت فعلی تأییدشده (جدا از مکان‌های ذخیره‌شده):",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Text(
                                            text = confirmedCurrentLocation.ifBlank { "هنوز ثبت نشده است" },
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                }
                            }
                        }

                        if (locations.isEmpty()) {
                            item {
                                EmptyMemoryState(
                                    icon = Icons.Default.LocationOn,
                                    title = "مکانی ذخیره نشده است",
                                    subtitle = "دکمه + را بزنید تا مکان‌هایی مثل شرکت یا خانه را ثبت کنید.",
                                )
                            }
                        } else {
                            items(locations, key = { it.id }) { loc ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${loc.alias} ⟵ ${loc.fullName}",
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            if (loc.addressNote.isNotBlank()) {
                                                Text(
                                                    text = loc.addressNote,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        IconButton(onClick = { editingLocation = loc }) {
                                            Icon(Icons.Default.Edit, contentDescription = "ویرایش مکان")
                                        }
                                        IconButton(onClick = { onDeleteLocation(loc.id) }) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "حذف مکان",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add dialogs according to active tab
    if (showAddDialog) {
        when (selectedTab) {
            0 -> AddRuleOrFactDialog(
                onSaveAlias = { k, v -> onAddOrUpdateAlias(k, v) },
                onSaveFact = { f -> onAddFact(f) },
                onDismiss = { showAddDialog = false },
            )
            1 -> EditPersonDialog(
                existing = null,
                onSave = { n, p, r, note -> onSavePerson(n, p, r, note, 0) },
                onDismiss = { showAddDialog = false },
            )
            2 -> EditLocationDialog(
                existing = null,
                onSave = { a, f, note -> onSaveLocation(a, f, note, 0) },
                onDismiss = { showAddDialog = false },
            )
        }
    }

    editingMemory?.let { item ->
        var text by remember(item.id) { mutableStateOf(item.valueText) }
        AlertDialog(
            onDismissRequest = { editingMemory = null },
            title = { Text("ویرایش حافظه") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("مقدار ذخیره‌شده") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateMemory(item, text)
                    editingMemory = null
                }) { Text("ذخیره") }
            },
            dismissButton = {
                TextButton(onClick = { editingMemory = null }) { Text("لغو") }
            },
        )
    }

    editingPerson?.let { person ->
        EditPersonDialog(
            existing = person,
            onSave = { n, p, r, note -> onSavePerson(n, p, r, note, person.id) },
            onDismiss = { editingPerson = null },
        )
    }

    editingLocation?.let { loc ->
        EditLocationDialog(
            existing = loc,
            onSave = { a, f, note -> onSaveLocation(a, f, note, loc.id) },
            onDismiss = { editingLocation = null },
        )
    }
}

@Composable
private fun EmptyMemoryState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddRuleOrFactDialog(
    onSaveAlias: (String, String) -> Unit,
    onSaveFact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var aliasKey by remember { mutableStateOf("") }
    var aliasMeaning by remember { mutableStateOf("") }
    var factText by remember { mutableStateOf("") }
    var isAliasMode by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("آموزش جدید به Sol") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { isAliasMode = true }) {
                        Text(
                            "قانون / مترادف",
                            fontWeight = if (isAliasMode) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    TextButton(onClick = { isAliasMode = false }) {
                        Text(
                            "نکتهٔ شخصی",
                            fontWeight = if (!isAliasMode) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
                if (isAliasMode) {
                    OutlinedTextField(
                        value = aliasKey,
                        onValueChange = { aliasKey = it },
                        label = { Text("وقتی می‌گویم… (مثلاً دفتر)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = aliasMeaning,
                        onValueChange = { aliasMeaning = it },
                        label = { Text("منظورم… (مثلاً شرکت کارنو صنعت ماکیان)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = factText,
                        onValueChange = { factText = it },
                        label = { Text("نکته یا ترجیح شخصی") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (isAliasMode) onSaveAlias(aliasKey, aliasMeaning) else onSaveFact(factText)
                onDismiss()
            }) { Text("ذخیره") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("لغو") }
        },
    )
}

@Composable
private fun EditPersonDialog(
    existing: PersonEntity?,
    onSave: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var phone by remember(existing) { mutableStateOf(existing?.phone ?: "") }
    var relation by remember(existing) { mutableStateOf(existing?.relation ?: "") }
    var note by remember(existing) { mutableStateOf(existing?.callScriptNote ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "افزودن شخص" else "ویرایش شخص") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("نام") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("شماره تماس") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = relation,
                    onValueChange = { relation = it },
                    label = { Text("نسبت (مثلاً همکار، خانواده)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("دستور اختصاصی هنگام ارتباط") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(name, phone, relation, note)
                onDismiss()
            }) { Text("ذخیره") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("لغو") }
        },
    )
}

@Composable
private fun EditLocationDialog(
    existing: LocationEntity?,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var alias by remember(existing) { mutableStateOf(existing?.alias ?: "") }
    var fullName by remember(existing) { mutableStateOf(existing?.fullName ?: "") }
    var note by remember(existing) { mutableStateOf(existing?.addressNote ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "افزودن مکان" else "ویرایش مکان") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("نام کوتاه (مثلاً دفتر، شرکت)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = { Text("نام کامل مکان") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("توضیح یا آدرس") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(alias, fullName, note)
                onDismiss()
            }) { Text("ذخیره") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("لغو") }
        },
    )
}
