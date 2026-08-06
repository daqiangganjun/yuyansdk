package com.yuyan.imemodule.ui.fragment

import android.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.yuyan.imemodule.R
import com.yuyan.imemodule.handwriting.HandwritingClient
import com.yuyan.imemodule.handwriting.HandwritingState
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.ui.fragment.base.ManagedPreferenceFragment

class HandwritingSettingsFragment: ManagedPreferenceFragment(AppPrefs.getInstance().handwriting) {

    private var modelPreference: Preference? = null

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        modelPreference = Preference(screen.context).apply {
            key = KEY_MODEL
            title = getString(R.string.hw_model_title)
            isPersistent = false
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                onModelClick()
                true
            }
        }
        screen.addPreference(modelPreference!!)
    }

    // 回调可能来自后台线程，切回主线程刷新
    private val stateListener: (HandwritingState) -> Unit = { state ->
        activity?.runOnUiThread { renderState(state) }
    }

    // 模型的查询、下载与删除都发生在 :hw 进程，本页停留期间需保持绑定
    override fun onResume() {
        super.onResume()
        context?.let { HandwritingClient.acquire(it) }
        HandwritingClient.addListener(stateListener)
        HandwritingClient.refresh()
    }

    override fun onPause() {
        HandwritingClient.removeListener(stateListener)
        HandwritingClient.release()
        super.onPause()
    }

    private fun renderState(state: HandwritingState) {
        modelPreference?.summary = when (state) {
            HandwritingState.Unknown -> getString(R.string.hw_model_state_unknown)
            HandwritingState.NotDownloaded -> getString(R.string.hw_model_state_not_downloaded)
            HandwritingState.Downloading -> getString(R.string.hw_model_state_downloading)
            HandwritingState.Downloaded -> getString(R.string.hw_model_state_downloaded)
            is HandwritingState.Failed -> getString(R.string.hw_model_state_failed, state.message)
        }
        modelPreference?.isEnabled = state != HandwritingState.Downloading
    }

    private fun onModelClick() {
        when (HandwritingClient.state) {
            HandwritingState.Downloaded -> confirmDelete()
            HandwritingState.Downloading -> Unit
            else -> HandwritingClient.download()
        }
    }

    private fun confirmDelete() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.hw_model_delete_title)
            .setMessage(R.string.hw_model_delete_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> HandwritingClient.delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val KEY_MODEL = "mlkit_handwriting_model"
    }
}
