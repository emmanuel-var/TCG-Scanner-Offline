package com.tcgscanner.offline.ui

import android.text.format.DateUtils
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tcgscanner.offline.R
import com.tcgscanner.offline.core.GradingCompany
import com.tcgscanner.offline.core.Money
import com.tcgscanner.offline.data.repo.UnitValue
import java.text.DateFormat
import java.util.Date

@Composable
fun gradeLabel(company: GradingCompany, gradeX10: Int): String =
    if (!company.isGraded) stringResource(R.string.raw) else "${company.label} ${gradeText(gradeX10)}"

fun gradeText(gradeX10: Int): String = if (gradeX10 % 10 == 0) (gradeX10 / 10).toString() else (gradeX10 / 10.0).toString()

fun moneyText(usd: Double, estimated: Boolean = false) = (if (estimated) "≈ " else "") + Money.format(usd)

@Composable
fun unitText(v: UnitValue): String = if (!v.hasPrice) stringResource(R.string.no_price) else moneyText(v.usd, v.estimated)

fun relativeTime(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun dateTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

@Composable
fun pluralText(@PluralsRes id: Int, count: Int): String =
    LocalContext.current.resources.getQuantityString(id, count, count)
