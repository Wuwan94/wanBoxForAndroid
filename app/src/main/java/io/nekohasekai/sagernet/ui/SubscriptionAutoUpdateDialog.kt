// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * B2/O5: subscription auto-update interval picker, adapted from OwnBox 8.6
 * (upstream commit c025f41). Presets + custom unit dialog + humanized summary.
 * The stored value stays a plain minute count (`autoUpdateDelay`, default 360);
 * only the interaction and the summary are new. Pure helpers ([toMinutes],
 * [initialValueAndUnit], [describeInterval]) carry the testable logic so the
 * JVM unit tests do not need Android resources.
 */
object SubscriptionAutoUpdateDialog {

    val PRESET_MINUTES = listOf(15, 30, 60, 180, 360, 720, 1440)

    /** WorkManager periodic minimum: no interval below 15 minutes is accepted. */
    const val MIN_MINUTES = 15

    enum class TimeUnit { MINUTES, HOURS, DAYS }

    /** Pure description of an interval, for tests and for [formatInterval]. */
    sealed interface IntervalDescription {
        data object Disabled : IntervalDescription
        data class Minutes(val value: Int) : IntervalDescription
        data class Hours(val value: Int) : IntervalDescription
        data class Days(val value: Int) : IntervalDescription
    }

    fun describeInterval(minutes: Int): IntervalDescription {
        return when {
            minutes <= 0 -> IntervalDescription.Disabled
            minutes % 1440 == 0 -> IntervalDescription.Days(minutes / 1440)
            minutes % 60 == 0 -> IntervalDescription.Hours(minutes / 60)
            else -> IntervalDescription.Minutes(minutes)
        }
    }

    fun formatSummary(context: Context, minutes: Int, enabled: Boolean): String {
        if (!enabled) {
            return context.getString(R.string.subscription_auto_update_disabled)
        }
        return formatInterval(context, minutes)
    }

    fun formatInterval(context: Context, minutes: Int): String {
        return when (val desc = describeInterval(minutes)) {
            is IntervalDescription.Disabled ->
                context.getString(R.string.subscription_auto_update_disabled)
            is IntervalDescription.Days ->
                if (desc.value == 1) {
                    context.getString(R.string.subscription_auto_update_1_day)
                } else {
                    context.getString(R.string.subscription_auto_update_days, desc.value)
                }
            is IntervalDescription.Hours ->
                if (desc.value == 1) {
                    context.getString(R.string.subscription_auto_update_1_hour)
                } else {
                    context.getString(R.string.subscription_auto_update_hours, desc.value)
                }
            is IntervalDescription.Minutes ->
                context.getString(R.string.subscription_auto_update_minutes, desc.value)
        }
    }

    /**
     * Pure conversion of a custom-dialog input to minutes. Returns null when the
     * number itself is invalid (not a positive integer); the 15-minute minimum is
     * enforced by the caller ([isBelowMinimum]) so the dialog can show a distinct
     * "minimum" error instead of an "invalid number" error.
     */
    fun toMinutes(number: Int, unit: TimeUnit): Int? {
        if (number <= 0) return null
        return when (unit) {
            TimeUnit.DAYS -> number * 1440
            TimeUnit.HOURS -> number * 60
            TimeUnit.MINUTES -> number
        }
    }

    fun isBelowMinimum(totalMinutes: Int): Boolean = totalMinutes < MIN_MINUTES

    /** Pure decomposition of a stored minute value into the custom dialog's initial value + unit. */
    fun initialValueAndUnit(minutes: Int): Pair<String, TimeUnit> {
        val safe = minutes.coerceAtLeast(MIN_MINUTES)
        return when {
            safe % 1440 == 0 -> Pair((safe / 1440).toString(), TimeUnit.DAYS)
            safe % 60 == 0 -> Pair((safe / 60).toString(), TimeUnit.HOURS)
            else -> Pair(safe.toString(), TimeUnit.MINUTES)
        }
    }

    fun show(
        context: Context,
        currentMinutes: Int,
        onSelected: (Int) -> Unit
    ) {
        val labels = arrayOf(
            context.getString(R.string.subscription_auto_update_minutes, 15),
            context.getString(R.string.subscription_auto_update_minutes, 30),
            context.getString(R.string.subscription_auto_update_1_hour),
            context.getString(R.string.subscription_auto_update_hours, 3),
            context.getString(R.string.subscription_auto_update_hours, 6),
            context.getString(R.string.subscription_auto_update_hours, 12),
            context.getString(R.string.subscription_auto_update_1_day),
            context.getString(R.string.subscription_auto_update_custom)
        )

        val presetIndex = PRESET_MINUTES.indexOf(currentMinutes)
        val checkedItem = if (presetIndex >= 0) presetIndex else labels.lastIndex

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.auto_update_delay)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                dialog.dismiss()
                if (which == labels.lastIndex) {
                    showCustomDialog(context, currentMinutes, onSelected)
                } else {
                    onSelected(PRESET_MINUTES[which])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomDialog(
        context: Context,
        currentMinutes: Int,
        onSelected: (Int) -> Unit
    ) {
        val (initVal, initUnit) = initialValueAndUnit(currentMinutes)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
        }

        val inputLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.subscription_auto_update_custom_hint)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initVal)
            setSelection(text?.length ?: 0)
            isSingleLine = true
        }
        inputLayout.addView(input)
        container.addView(inputLayout)

        val unitGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp2px(12), 0, 0)
        }
        val radioMinutes = RadioButton(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.subscription_unit_minutes)
            setPadding(0, 0, dp2px(16), 0)
        }
        val radioHours = RadioButton(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.subscription_unit_hours)
            setPadding(0, 0, dp2px(16), 0)
        }
        val radioDays = RadioButton(context).apply {
            id = View.generateViewId()
            text = context.getString(R.string.subscription_unit_days)
        }
        unitGroup.addView(radioMinutes)
        unitGroup.addView(radioHours)
        unitGroup.addView(radioDays)

        val checkedId = when (initUnit) {
            TimeUnit.DAYS -> radioDays.id
            TimeUnit.HOURS -> radioHours.id
            else -> radioMinutes.id
        }
        unitGroup.check(checkedId)
        container.addView(unitGroup)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.subscription_auto_update_custom)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val num = input.text?.toString()?.trim()?.toIntOrNull()
            val unit = when (unitGroup.checkedRadioButtonId) {
                radioDays.id -> TimeUnit.DAYS
                radioHours.id -> TimeUnit.HOURS
                else -> TimeUnit.MINUTES
            }
            val totalMinutes = if (num == null) null else toMinutes(num, unit)
            if (totalMinutes == null) {
                inputLayout.error = context.getString(R.string.subscription_auto_update_invalid)
                return@setOnClickListener
            }
            if (isBelowMinimum(totalMinutes)) {
                inputLayout.error = context.getString(R.string.subscription_auto_update_min_15m)
                return@setOnClickListener
            }
            dialog.dismiss()
            onSelected(totalMinutes)
        }
    }
}
