@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.SearchType
import com.courieriot.stellargallery.ui.theme.Neon
import java.time.LocalDate
import java.time.ZoneId

private const val DAY = 86_400_000L

@Composable
fun SearchScreen(vm: MainViewModel, nav: NavController) {
    val q by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    var dateLabel by rememberSaveable { mutableStateOf("Any time") }
    var pickRange by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val sorterState by vm.sorterState.collectAsStateWithLifecycle()
    val sorterOn by vm.sorterEnabled.collectAsStateWithLifecycle()
    val smartOn = sorterOn && sorterState.search == com.courieriot.stellargallery.sorter.SearchModelState.Ready
    val selected = remember(results, selection) { results.filter { it.id in selection } }

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    LaunchedEffect(Unit) { vm.clearSelection(); runCatching { focus.requestFocus() } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionTopBar(selected.size, vm::clearSelection, { vm.selectAll(results) }) {
                    MediaSelectionActions(vm, nav, selected)
                }
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    title = {
                        TextField(
                            value = q.text,
                            onValueChange = { vm.updateSearch(q.copy(text = it)) },
                            placeholder = { Text("Name, album, type…") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Neon,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    },
                    actions = {
                        if (!q.isEmpty) {
                            IconButton(onClick = {
                                vm.updateSearch(com.courieriot.stellargallery.SearchQuery()); dateLabel = "Any time"
                            }) { Icon(Icons.Filled.Close, "Clear") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                items(SearchType.entries) { t ->
                    Chip(t.label, q.type == t) { vm.updateSearch(q.copy(type = t)) }
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                val now = System.currentTimeMillis()
                val yearStart = LocalDate.now().withDayOfYear(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val options = listOf<Triple<String, Long?, Long?>>(
                    Triple("Any time", null, null),
                    Triple("Last 7 days", now - 7 * DAY, null),
                    Triple("Last 30 days", now - 30 * DAY, null),
                    Triple("This year", yearStart, null),
                )
                items(options, key = { it.first }) { (label, from, to) ->
                    Chip(label, dateLabel == label) { dateLabel = label; vm.updateSearch(q.copy(fromMillis = from, toMillis = to)) }
                }
                item(key = "pick") {
                    Chip(if (dateLabel == "Custom") "Custom dates ✓" else "Pick dates…", dateLabel == "Custom") { pickRange = true }
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (q.isEmpty) {
                    EmptyState(
                        if (smartOn) "✦ Search & sort is on — describe what's in the photo (\"orange cat\", \"sunset\"),\nor search by file name, album or folder."
                        else "Search by file name, album or folder.\nCombine with type and date filters.\n\nTurn on Search & sort in Settings to search by what's in a photo.",
                    )
                } else {
                    MediaGrid(
                        items = results,
                        columns = columns,
                        groupByDate = false,
                        selection = selection,
                        emptyText = "Nothing matches.",
                        onOpen = { idx -> nav.navigate("viewer/search/$idx") },
                        onToggle = vm::toggleSelection,
                        onSelectionChange = vm::setSelection,
                        onColumnsChange = vm::setGridColumns,
                        header = {
                            Text(
                                "${results.size} results",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        },
                    )
                }
            }
        }
    }

    if (pickRange) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton(onClick = {
                    val start = state.selectedStartDateMillis
                    val end = state.selectedEndDateMillis ?: start
                    if (start != null) {
                        dateLabel = "Custom"
                        vm.updateSearch(q.copy(fromMillis = start, toMillis = (end ?: start) + DAY - 1))
                    }
                    pickRange = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { pickRange = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Neon.copy(alpha = 0.18f),
            selectedLabelColor = Neon,
        ),
    )
}
