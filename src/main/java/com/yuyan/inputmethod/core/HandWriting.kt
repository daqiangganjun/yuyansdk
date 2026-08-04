package com.yuyan.inputmethod.core

import android.content.Context
import androidx.annotation.Keep

object HandWriting {

    init {
        System.loadLibrary("handwriting")
    }

    private var initialized = false

    fun init(context: Context): Boolean {
        if (this.initialized) return true
        val result = initWithDirectory(context, context.getExternalFilesDir("hw").toString())
        this.initialized = result
        return  result
    }

    fun setProperties() {
        reloadConfig()
    }

    fun selectInputMode(i: Int): Boolean {
        return activeMode(i)
    }

    // native 在引擎未就绪或无识别结果时会返回 null，此处如实标注为可空
    @Throws(NumberFormatException::class)
    fun getCandidatesPyComposition(): Array<Array<String?>?>? {
        return getCandidates()
    }

    @Keep
    external fun getPackageName(): String?

    external fun activeMode(mode: Int): Boolean

    external fun getCandidates(): Array<Array<String?>?>?

    external fun initWithDirectory(context: Context, str: String?): Boolean

    external fun inputHWPoints(iArr: IntArray?): Boolean

    external fun release()

    external fun reloadConfig(): Boolean

    external fun reset(): Boolean
}