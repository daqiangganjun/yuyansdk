package com.yuyan.inputmethod.core

import android.content.Context
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.application.Launcher

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
            System.loadLibrary("yuyanime")
        }

        // 页大小是 session 级配置，只需在引擎启动与方案切换后各设置一次
        private const val PAGE_SIZE = 100

        fun startup(context: Context, fullCheck: Boolean) {
            startupRime(context, CustomConstant.RIME_DICT_PATH, CustomConstant.RIME_DICT_PATH, fullCheck)
            setRimePageSize(PAGE_SIZE)
            updateStatus()
        }

        @JvmStatic
        fun destroy() {
            exitRime()
            instance = null
        }

        fun updateStatus() {
            mStatus = getRimeStatus() ?: RimeStatus()
        }

        fun updateContext() {
            mContext = getRimeContext() ?: RimeContext()
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
            if (keycode <= 0 || keycode == 0xffffff) return false
            return processRimeKey(keycode, mask).also {
                updateContext()
            }
        }

        @JvmStatic
        fun replaceKey(caretPos: Int, length: Int, key: String): Boolean {
            return replaceRimeKey(caretPos, length, key).also {
                updateContext()
            }
        }

        @JvmStatic
        fun clearComposition() { clearRimeComposition()
            updateContext()
        }

        @JvmStatic
        fun selectCandidate(index: Int): Boolean {
            return selectRimeCandidate(index).also {
                updateContext()
            }
        }

        @JvmStatic
        fun setOption(option: String, value: Boolean) {
            setRimeOption(option, value)
        }

        @JvmStatic
        fun selectSchema(schemaId: String): Boolean {
            return selectRimeSchema(schemaId).also {
                setRimePageSize(PAGE_SIZE)
                updateContext()
            }
        }

        fun getAssociateList(key: String?): Array<String?> {
            return getRimeAssociateList(key)
        }

        fun chooseAssociate(index: Int): Boolean {
            return selectRimeAssociate(index)
        }

        @JvmStatic
        external fun startupRime(context: Context, sharedDir: String, userDir: String, fullCheck: Boolean, )

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
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        @JvmStatic
        external fun selectRimeCandidate(index: Int): Boolean

        @JvmStatic
        external fun getRimeKeycodeByName(name: String): Int

        @JvmStatic
        external fun getRimeAssociateList(key: String?): Array<String?>

        @JvmStatic
        external fun selectRimeAssociate(index: Int): Boolean
    }
}
