package com.yuyan.imemodule.ui.fragment

import android.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.yuyan.imemodule.R
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.ui.fragment.base.ManagedPreferenceFragment
import com.yuyan.inputmethod.MlKitHandwritingModel

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
    private val stateListener: (MlKitHandwritingModel.State) -> Unit = { state ->
        activity?.runOnUiThread { renderState(state) }
    }

    override fun onResume() {
        super.onResume()
        MlKitHandwritingModel.addListener(stateListener)
        MlKitHandwritingModel.refreshState()
    }

    override fun onPause() {
        MlKitHandwritingModel.removeListener(stateListener)
        super.onPause()
    }

    private fun renderState(state: MlKitHandwritingModel.State) {
        modelPreference?.summary = when (state) {
            MlKitHandwritingModel.State.Unknown -> getString(R.string.hw_model_state_unknown)
            MlKitHandwritingModel.State.NotDownloaded -> getString(R.string.hw_model_state_not_downloaded)
            MlKitHandwritingModel.State.Downloading -> getString(R.string.hw_model_state_downloading)
            MlKitHandwritingModel.State.Downloaded -> getString(R.string.hw_model_state_downloaded)
            is MlKitHandwritingModel.State.Failed -> getString(R.string.hw_model_state_failed, state.message)
        }
        modelPreference?.isEnabled = state != MlKitHandwritingModel.State.Downloading
    }

    private fun onModelClick() {
        when (MlKitHandwritingModel.state) {
            MlKitHandwritingModel.State.Downloaded -> confirmDelete()
            MlKitHandwritingModel.State.Downloading -> Unit
            else -> MlKitHandwritingModel.download()
        }
    }

    private fun confirmDelete() {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.hw_model_delete_title)
            .setMessage(R.string.hw_model_delete_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> MlKitHandwritingModel.delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val KEY_MODEL = "mlkit_handwriting_model"
    }
}
