package com.yuyan.inputmethod.core

import android.content.Context
import com.yuyan.imemodule.application.Launcher
import com.yuyan.inputmethod.EngineRuntime

class Rime(fullCheck: Boolean) {

    init {
        startup(Launcher.instance.context, fullCheck)
    }

    companion object {
        private var instance: Rime? = null
        private var mContext: RimeContext? = null
        private var mStatus: RimeStatus? = null

        @JvmStatic
        fun getInstance(fullCheck: Boolean = false): Rime {
            if (instance == null) instance = Rime(fullCheck)
            return instance!!
        }

        init {
            System.loadLibrary("selfopt_rime")
        }

        fun startup(context: Context, fullCheck: Boolean) {
            EngineRuntime.initialize(context)
        }

        @JvmStatic
        fun destroy() {
            instance = null
            mContext = null
            mStatus = null
        }

        fun updateStatus() {
            mStatus = if (EngineRuntime.isReady) getRimeStatus() ?: RimeStatus() else RimeStatus()
        }

        fun updateContext() {
            mContext = if (EngineRuntime.isReady) getRimeContext() ?: RimeContext() else RimeContext()
            updateStatus()
        }

        /**
         * 当前候选词。读取 updateContext() 已缓存的结果，避免重复跨 JNI 编组整页候选。
         */
        @JvmStatic
        val candidates: Array<CandidateListItem>
            get() = mContext?.candidates ?: arrayOf()

        @JvmStatic
        val isComposing get() = mStatus?.isComposing == true

        @JvmStatic
        fun hasMenu(): Boolean {
            return isComposing && mContext?.menu?.numCandidates != 0
        }

        @JvmStatic
        fun hasRight(): Boolean {
            return hasMenu() && mContext?.menu?.isLastPage == false
        }

        @JvmStatic
        val composition: RimeComposition?
            get() = mContext?.composition

        @JvmStatic
        val compositionText: String
            get() = composition?.preedit ?: ""

        @JvmStatic
        fun processKey(keycode: Int, mask: Int): Boolean {
            if (!EngineRuntime.isReady || keycode <= 0 || keycode == 0xffffff) return false
            return processRimeKey(keycode, mask).also {
                updateContext()
            }
        }

        @JvmStatic
        fun replaceKey(caretPos: Int, length: Int, key: String): Boolean {
            if (!EngineRuntime.isReady) return false
            return replaceRimeKey(caretPos, length, key).also {
                updateContext()
            }
        }

        @JvmStatic
        fun clearComposition() {
            if (EngineRuntime.isReady) clearRimeComposition()
            updateContext()
        }

        @JvmStatic
        fun selectCandidate(index: Int): Boolean {
            if (!EngineRuntime.isReady) return false
            return selectRimeCandidate(index).also {
                updateContext()
            }
        }

        @JvmStatic
        fun setOption(option: String, value: Boolean) {
            if (EngineRuntime.isReady) setRimeOption(option, value)
        }

        @JvmStatic
        fun selectSchema(schemaId: String): Boolean {
            return EngineRuntime.selectSchema(schemaId).also {
                updateContext()
            }
        }

        fun getAssociateList(key: String?): Array<String?> {
            return if (EngineRuntime.isReady) getRimeAssociateList(key) ?: emptyArray() else emptyArray()
        }

        fun chooseAssociate(index: Int): Boolean {
            return EngineRuntime.isReady && selectRimeAssociate(index)
        }

        @JvmStatic
        external fun startupRime(context: Context, sharedDir: String, userDir: String, fullCheck: Boolean): Boolean

        @JvmStatic
        external fun migrateUserData(sharedDir: String, stagedUserDir: String): Boolean

        @JvmStatic
        external fun getLastError(): String

        @JvmStatic
        external fun getRawInput(): String

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun setRimePageSize(pageSize:Int)

        @JvmStatic
        external fun processRimeKey(keycode: Int, mask: Int): Boolean

        @JvmStatic
        external fun replaceRimeKey(caretPos: Int, length: Int, key: String?): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        @JvmStatic
        external fun getRimeCommit(): RimeCommit?

        @JvmStatic
        external fun getRimeContext(): RimeContext?

        @JvmStatic
        external fun getRimeStatus(): RimeStatus?

        @JvmStatic
        external fun setRimeOption(option: String, value: Boolean, )

        @JvmStatic
        fun getCurrentRimeSchema(): String = EngineRuntime.logicalSchema

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        @JvmStatic
        external fun validateRimeSchema(): Boolean

        @JvmStatic
        external fun selectRimeCandidate(index: Int): Boolean

        @JvmStatic
        external fun getRimeKeycodeByName(name: String): Int

        @JvmStatic
        external fun getRimeAssociateList(key: String?): Array<String?>?

        @JvmStatic
        external fun selectRimeAssociate(index: Int): Boolean
    }
}
