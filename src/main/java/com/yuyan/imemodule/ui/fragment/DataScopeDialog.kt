package com.yuyan.imemodule.ui.fragment

import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yuyan.imemodule.data.transfer.SyncScope

internal object DataScopeDialog {
    fun show(context: Context, title: String, selected: Set<SyncScope>, requireSelection: Boolean = false, confirm: (Set<SyncScope>) -> Unit) {
        val scopes = SyncScope.entries
        val checked = BooleanArray(scopes.size) { scopes[it] in selected }
        var refreshToggle: () -> Unit = {}
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setMultiChoiceItems(scopes.map { it.label }.toTypedArray(), checked) { _, index, enabled -> checked[index] = enabled; refreshToggle() }
            .setPositiveButton("确定", null).setNegativeButton("取消", null).setNeutralButton("全选", null).create()
        dialog.setOnShowListener {
            val toggle = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
            refreshToggle = { toggle.text = if (checked.all { it }) "全不选" else "全选" }
            refreshToggle()
            toggle.setOnClickListener {
                val selectAll = !checked.all { it }
                checked.indices.forEach { index -> checked[index] = selectAll; dialog.listView.setItemChecked(index, selectAll) }
                refreshToggle()
            }
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val result = scopes.filterIndexed { index, _ -> checked[index] }.toSet()
                if (requireSelection && result.isEmpty()) Toast.makeText(context, "请至少选择一类数据", Toast.LENGTH_SHORT).show()
                else { dialog.dismiss(); confirm(result) }
            }
        }
        dialog.show()
    }
}
