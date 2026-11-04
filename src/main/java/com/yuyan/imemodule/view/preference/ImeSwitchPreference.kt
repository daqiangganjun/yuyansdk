
package com.yuyan.imemodule.view.preference

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuyan.imemodule.R
import com.yuyan.imemodule.prefs.restore

class ImeSwitchPreference(context: Context) : SwitchPreferenceCompat(context) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(title ?: "Preference")
                .setMessage(R.string.whether_reset_switch_preference)
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setPositiveButton(R.string.reset) { _, _ -> restore() }
                .show()
            true
        }
    }
}
