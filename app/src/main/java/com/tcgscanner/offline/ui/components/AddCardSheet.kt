@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.tcgscanner.offline.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.CardCondition
import com.tcgscanner.offline.core.CardVariant
import com.tcgscanner.offline.core.GameDef
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.data.db.CardWithPrices
import com.tcgscanner.offline.data.db.CollectionItemEntity
import com.tcgscanner.offline.data.repo.AddSpec
import com.tcgscanner.offline.data.repo.Valuation
import com.tcgscanner.offline.ui.gradeText
import com.tcgscanner.offline.ui.moneyText

/**
 * The "which version is it?" bottom sheet: the user resolves variant (Normal / Holo / Reverse / 1st Edition...),
 * condition or grading slab in one tap each. This is what removes the #1 complaint about camera scanners.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCardSheet(
    game: GameDef,
    card: CardWithPrices,
    otherPrints: List<CardWithPrices>,
    confidenceText: String? = null,
    onPickPrint: (CardWithPrices) -> Unit,
    onAdd: (AddSpec) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = card.card

    // Variants priced for this exact print come first, then the rest of the game's variants.
    val priced = card.prices.map { CardVariant.fromCode(it.variant) }
    val variants = (priced + game.variants).distinct()

    var variant by rememberSaveable(c.id) { mutableStateOf(variants.first().code) }
    var graded by rememberSaveable(c.id) { mutableStateOf(false) }
    var condition by rememberSaveable(c.id) { mutableStateOf(CardCondition.NM.code) }
    var company by rememberSaveable(c.id) { mutableStateOf(GradingCompany.PSA.code) }
    var gradeX10 by rememberSaveable(c.id) { mutableIntStateOf(100) }
    var quantity by rememberSaveable(c.id) { mutableIntStateOf(1) }
    var manual by rememberSaveable(c.id) { mutableStateOf("") }

    val comp = GradingCompany.fromCode(company)
    LaunchedEffect(company) {
        if (gradeX10 !in comp.grades) gradeX10 = comp.grades.lastOrNull() ?: 100
    }

    val manualValue = manual.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
    val preview = Valuation.unitValue(
        CollectionItemEntity(
            gameId = c.gameId, cardId = c.id, variant = variant, condition = condition,
            gradeCompany = if (graded) company else GradingCompany.NONE.code, gradeX10 = if (graded) gradeX10 else 0,
            quantity = 1, tradeQuantity = 0, manualPriceUsd = manualValue, notes = null, addedAt = 0, updatedAt = 0
        ),
        card.prices, card.graded
    )

    fun spec(toBinder: Boolean) = AddSpec(
        cardId = c.id,
        variant = CardVariant.fromCode(variant),
        condition = CardCondition.fromCode(condition),
        company = if (graded) comp else GradingCompany.NONE,
        gradeX10 = if (graded) gradeX10 else 0,
        quantity = quantity,
        toBinder = toBinder,
        manualPriceUsd = manualValue
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CardImage(c.imageUrl, c.name, Modifier.size(width = 84.dp, height = 117.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${c.setName} · #${c.number}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    c.rarity?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    if (confidenceText != null) {
                        Text(confidenceText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (otherPrints.isNotEmpty()) {
                Text(stringResource(R.string.other_prints), style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(otherPrints, key = { it.card.id }) { p ->
                        Column(
                            Modifier
                                .width(72.dp)
                                .clickable { onPickPrint(p) }
                                .semantics { contentDescription = "${p.card.name}, ${p.card.setName}" },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CardImage(p.card.imageUrl, p.card.name, Modifier.size(width = 72.dp, height = 100.dp))
                            Text(p.card.setCode, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Text(stringResource(R.string.which_version), style = MaterialTheme.typography.titleMedium)
            RowOfChips {
                variants.forEach { v ->
                    val price = card.prices.firstOrNull { it.variant == v.code }?.usd
                    FilterChip(
                        selected = variant == v.code,
                        onClick = { variant = v.code },
                        label = { Text(stringResource(v.labelRes) + (price?.let { " · " + moneyText(it) } ?: "")) }
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !graded, onClick = { graded = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.raw))
                }
                SegmentedButton(selected = graded, onClick = { graded = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.graded_slab))
                }
            }

            if (!graded) {
                RowOfChips {
                    CardCondition.entries.forEach { cd ->
                        FilterChip(selected = condition == cd.code, onClick = { condition = cd.code }, label = { Text(stringResource(cd.labelRes)) })
                    }
                }
            } else {
                RowOfChips {
                    GradingCompany.entries.filter { it.isGraded }.forEach { g ->
                        FilterChip(selected = company == g.code, onClick = { company = g.code }, label = { Text(g.label) })
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(comp.grades.reversed()) { g ->
                        FilterChip(selected = gradeX10 == g, onClick = { gradeX10 = g }, label = { Text(gradeText(g)) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.quantity), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { quantity = (quantity - 1).coerceAtLeast(1) }) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.decrease))
                }
                Text(quantity.toString(), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { quantity = (quantity + 1).coerceAtMost(999) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.increase))
                }
            }

            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it.take(10) },
                label = { Text(stringResource(R.string.manual_price_optional)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.value_each), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (preview.hasPrice) moneyText(preview.usd, preview.estimated) else stringResource(R.string.no_price),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            if (preview.estimated) {
                Text(stringResource(R.string.estimate_explained), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onAdd(spec(false)) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.add_to_collection))
                }
                FilledTonalButton(onClick = { onAdd(spec(true)) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.add_to_binder))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RowOfChips(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) { content() }
}
