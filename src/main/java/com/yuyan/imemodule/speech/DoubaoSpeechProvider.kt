package com.yuyan.imemodule.speech

import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

class DoubaoSpeechProvider : SpeechProvider {
    override val id = "doubao"
    override val displayName = "豆包流式语音识别"
    override val options = listOf(
        SpeechOption("resource_id", "模型与计费方式", "需要在豆包控制台开通相应资源", SpeechOptionType.CHOICE,
            "volc.seedasr.sauc.duration", linkedMapOf(
                "volc.seedasr.sauc.duration" to "2.0 小时版（推荐）",
                "volc.seedasr.sauc.concurrent" to "2.0 并发版",
                "volc.bigasr.sauc.duration" to "1.0 小时版",
                "volc.bigasr.sauc.concurrent" to "1.0 并发版")),
        switch("enable_itn", "书面格式", "将口语数字、金额和日期转换为规范格式", true),
        switch("enable_punc", "启用标点", "自动添加标点符号", true),
        switch("enable_ddc", "语义顺滑", "删除或修正语气词、停顿词和重复内容"),
        switch("enable_nonstream", "二遍识别", "分句结束后重新识别，提高最终结果准确率", true),
        switch("enable_speaker_info", "说话人分离", "自动启用分句信息；建议使用 2.0 模型"),
        switch("enable_lid", "中英文与方言识别", "识别普通话、英语、上海话、闽南话、四川话、陕西话和粤语"),
        SpeechOption("output_zh_variant", "中文输出字形", "由识别服务转换字形", SpeechOptionType.CHOICE, "",
            linkedMapOf("" to "简体中文", "traditional" to "繁体中文（大陆）", "tw" to "台湾正体", "hk" to "香港繁体")),
        switch("show_utterances", "返回分句和分词", "保留时间戳及供应商返回的附加信息"),
        switch("show_speech_rate", "返回语速", "自动启用分句信息"),
        switch("show_volume", "返回音量", "自动启用分句信息"),
        switch("enable_emotion_detection", "情绪检测", "自动启用分句信息"),
        switch("enable_gender_detection", "性别检测", "自动启用分句信息"),
        switch("enable_age_detection", "年龄估算", "自动启用分句信息，结果仅供参考"),
        switch("enable_accelerate_text", "首字加速", "可降低首字准确率，配合加速率使用"),
        number("accelerate_score", "首字加速率", "数值越大，首字返回越快", 0, 0..Int.MAX_VALUE),
        number("end_window_size", "静音分句阈值（毫秒）", "连续静音达到该时长时结束分句", 800, 300..5000),
        number("vad_segment_duration", "语义分句阈值（毫秒）", "设置了静音分句阈值时不生效", 3000, 1..Int.MAX_VALUE),
        number("force_to_speech_time", "起始强制有声时长（毫秒）", "避免起始弱音或静音导致过早判停", 0, 0..Int.MAX_VALUE),
        switch("enable_poi_fc", "地图领域推荐词", "自动启用二遍识别；城市信息可在语境词典中配置"),
        switch("enable_music_fc", "音乐领域推荐词", "自动启用二遍识别"),
        SpeechOption("corpus", "语境词典 JSON", "配置热词、替换词表与上下文；不自动读取输入框内容", SpeechOptionType.JSON_OBJECT, ""),
        SpeechOption("sensitive_words_filter", "敏感词过滤 JSON", "配置系统词库及自定义替换规则", SpeechOptionType.JSON_STRING, ""),
        SpeechOption("extra_request", "高级请求参数 JSON", "补充或覆盖 request 参数；界面使用全量结果，result_type 必须为 full", SpeechOptionType.JSON_OBJECT, "")
    ).map { it.copy(details = optionDetails(it.key)) }

    override fun validate(options: JSONObject) {
        val request = buildRequest(options)
        this.options.forEach { option ->
            if (!request.has(option.key)) return@forEach
            val value = request.get(option.key)
            when (option.type) {
                SpeechOptionType.SWITCH -> require(value is Boolean) { "Invalid boolean option: ${option.key}" }
                SpeechOptionType.INTEGER -> require(value is Int && (option.range == null || value in option.range)) {
                    "Invalid integer option: ${option.key}"
                }
                SpeechOptionType.CHOICE -> require(value is String && value in option.choices) { "Invalid choice: ${option.key}" }
                SpeechOptionType.JSON_OBJECT -> require(value is JSONObject) { "Invalid JSON object: ${option.key}" }
                SpeechOptionType.JSON_STRING -> {
                    require(value is String) { "Invalid JSON string: ${option.key}" }
                    JSONObject(value)
                }
                else -> Unit
            }
        }
        require(request.optString("result_type") == "full") { "Only full results are supported" }
        require(request.optString("model_name") == "bigmodel") { "Unsupported speech model" }
        require(request.optInt("end_window_size", 800) in 300..5000) { "Invalid end_window_size" }
    }

    fun buildRequest(options: JSONObject): JSONObject = JSONObject(options.toString()).apply {
        remove("resource_id")
        val extra = optJSONObject("extra_request")
        remove("extra_request")
        put("model_name", "bigmodel")
        put("result_type", "full")
        extra?.keys()?.forEach { put(it, extra.get(it)) }
        if (optString("output_zh_variant").isEmpty()) remove("output_zh_variant")
        if (listOf("enable_speaker_info", "show_speech_rate", "show_volume", "enable_lid",
                "enable_emotion_detection", "enable_gender_detection", "enable_age_detection").any { optBoolean(it) }) {
            put("show_utterances", true)
        }
        if (optBoolean("enable_poi_fc") || optBoolean("enable_music_fc")) put("enable_nonstream", true)
    }

    override fun createSession(apiKey: String, options: JSONObject, listener: SpeechListener): SpeechSession {
        validate(options)
        return Session(apiKey, options.optString("resource_id", "volc.seedasr.sauc.duration"), buildRequest(options), listener)
    }

    private class Session(
        private var apiKey: String,
        private val resourceId: String,
        private val requestOptions: JSONObject,
        private val listener: SpeechListener
    ) : WebSocketListener(), SpeechSession {
        private var socket: WebSocket? = null
        private var ready = false
        private var finished = false
        private var closed = false
        private var started = false
        private var sequence = 2

        @Synchronized
        override fun start() {
            check(!started) { "Speech session has already started" }
            if (closed) return
            started = true
            val request = Request.Builder().url(ENDPOINT)
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", resourceId)
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .header("X-Api-Connect-Id", UUID.randomUUID().toString()).build()
            apiKey = ""
            socket = client.newWebSocket(request, this)
        }

        @Synchronized
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) { webSocket.cancel(); return }
            val payload = JSONObject().put("user", JSONObject().put("uid", "yuyan_ime"))
                .put("audio", JSONObject().put("format", "pcm").put("codec", "raw")
                    .put("rate", 16000).put("bits", 16).put("channel", 1))
                .put("request", requestOptions)
            if (!webSocket.send(DoubaoProtocol.request(payload).toByteString())) {
                fail(SpeechFailure(SpeechFailureKind.NETWORK)); return
            }
            ready = true
            if (finished) sendLast() else listener.onReady()
        }

        @Synchronized
        override fun sendAudio(pcm: ByteArray): Boolean {
            if (!ready || closed || finished) return false
            if ((socket?.queueSize() ?: 0) > 256 * 1024) {
                fail(SpeechFailure(SpeechFailureKind.NETWORK)); return false
            }
            val sent = socket?.send(DoubaoProtocol.audio(pcm, sequence++, false).toByteString()) == true
            if (!sent) fail(SpeechFailure(SpeechFailureKind.NETWORK))
            return sent
        }

        @Synchronized
        override fun finish() {
            if (closed || finished) return
            finished = true
            if (ready) sendLast()
        }

        private fun sendLast() {
            if (socket?.send(DoubaoProtocol.audio(byteArrayOf(), sequence++, true).toByteString()) != true) {
                fail(SpeechFailure(SpeechFailureKind.NETWORK))
            }
        }

        @Synchronized
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed) return
            try {
                val response = DoubaoProtocol.decode(bytes.toByteArray())
                if (response.errorCode != null) {
                    fail(SpeechFailure(SpeechFailureKind.SERVER, response.errorCode)); return
                }
                val body = response.payload
                val result = body.optJSONObject("result")
                val utterances = result?.optJSONArray("utterances")
                val segments = if (utterances == null) emptyList() else (0 until utterances.length()).map { index ->
                    val item = utterances.getJSONObject(index)
                    val additions = item.optJSONObject("additions")
                    SpeechUtterance(item.optString("text"), item.optInt("start_time"), item.optInt("end_time"),
                        item.optBoolean("definite"), additions?.optString("speaker_id"), additions?.toString() ?: "{}")
                }
                if (response.isFinal) {
                    closed = true
                    ready = false
                    webSocket.close(1000, "Recognition complete")
                }
                listener.onResult(SpeechResult(result?.optString("text") ?: "", response.isFinal, segments, body.toString()))
            } catch (_: Exception) {
                fail(SpeechFailure(SpeechFailureKind.PROTOCOL))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = fail(SpeechFailure(SpeechFailureKind.PROTOCOL))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            response?.close()
            fail(SpeechFailure(if (code == 401 || code == 403) SpeechFailureKind.AUTHENTICATION else SpeechFailureKind.NETWORK, code))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(SpeechFailure(SpeechFailureKind.NETWORK))

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            fail(SpeechFailure(SpeechFailureKind.NETWORK))
        }

        @Synchronized
        private fun fail(failure: SpeechFailure) {
            if (closed) return
            cancel()
            listener.onFailure(failure)
        }

        @Synchronized
        override fun cancel() {
            closed = true
            ready = false
            apiKey = ""
            socket?.cancel()
            socket = null
        }
    }

    companion object {
        const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
        }
        private fun switch(key: String, title: String, description: String, default: Boolean = false) =
            SpeechOption(key, title, description, SpeechOptionType.SWITCH, default)
        private fun number(key: String, title: String, description: String, default: Int, range: IntRange) =
            SpeechOption(key, title, "$description；留空使用服务默认值（$default）", SpeechOptionType.INTEGER, "", range = range)

        private fun optionDetails(key: String): String = when (key) {
            "resource_id" -> "选择识别模型和账号的计费资源。新接入建议使用 2.0 小时版；已有并发资源的账号请选择并发版。选择项必须与豆包控制台已开通的资源一致，切换到未开通的资源会导致请求失败。"
            "enable_itn" -> "默认开启。将口语数字、金额和日期转为常用书写形式。例如：说“一九七零年”可输出“1970 年”，说“一百二十三美元”可输出“123 美元”。需要逐字保留口语数字时可关闭。此功能不负责润色整段文章。"
            "enable_punc" -> "默认开启。根据语意添加逗号、句号和问号等。适合聊天和日常听写；希望输出连续文字、自行加标点时可关闭。"
            "enable_ddc" -> "默认关闭。删除或修正“嗯”“啊”等语气词、重复和停顿，让句子更通顺。适合整理口述内容；需要保留原话、语气和重复时建议关闭。处理结果由模型决定。"
            "enable_nonstream" -> "本应用默认开启。说完一个分句后，服务会重新识别这一小段音频，修正前面的临时结果。通常能提高准确率，也可能增加最终结果的等待时间；关闭可减少这一步处理。"
            "enable_speaker_info" -> "默认关闭。区分一段录音中的不同说话人，返回说话人编号，并不识别真实身份。建议使用 2.0 模型。本应用会自动启用分句信息；说话人编号通过公共识别接口提供，输入框中仍只输入识别文字，不插入编号。"
            "enable_lid" -> "默认关闭。启用中英文及方言识别，支持普通话、英语、上海话、闽南话、四川话、陕西话和粤语，并返回语种标签。说方言时可开启；实际效果受音质和口音影响。本应用会自动启用分句信息。"
            "output_zh_variant" -> "默认输出简体中文。可改为大陆繁体、台湾正体或香港繁体，由语音服务转换识别结果。这个选择独立于拼音输入的繁简设置，只影响本供应商的语音结果。"
            "show_utterances" -> "默认关闭。返回每个分句、分词及其起止时间。说话人、语速、音量、方言和检测功能开启时，本应用会自动启用此项。附加信息保留在公共接口中，日常上屏仍为纯文字。"
            "show_speech_rate" -> "默认关闭。返回分句语速，单位为每秒 token 数；token 是模型处理文字的单位，不等同于汉字数。会启用分句信息及语音活动检测，静音分句默认阈值为 800 毫秒。语速信息不上屏。"
            "show_volume" -> "默认关闭。返回分句音量信息，单位为分贝（dB）。会启用分句信息及语音活动检测，静音分句默认阈值为 800 毫秒。该数值用于音频分析，不等同于手机播放音量，也不会改变录音音量。"
            "enable_emotion_detection" -> "默认关闭。估计说话情绪，可返回生气、开心、平静、悲伤或惊讶等标签。会自动启用分句信息。标签仅供参考，不会给识别文字添加情绪描述。"
            "enable_gender_detection" -> "默认关闭。根据声音特征估计性别标签，会自动启用分句信息。结果只是模型估计，不用于确认身份，也不会写入输入框。"
            "enable_age_detection" -> "默认关闭。根据声音特征估计年龄，会自动启用分句信息。音质、语速和口音都可能影响结果；估计值仅供参考，不会写入输入框。"
            "enable_accelerate_text" -> "默认关闭。尽量更早返回第一个识别字，需配合首字加速率使用。可能降低首字准确率；重视准确性时建议保持关闭。它主要影响录音时的预览，上屏仍等待最终结果。"
            "accelerate_score" -> "服务默认值为 0，表示不加速；留空使用服务默认值。需同时开启首字加速，数值越大首字返回越快，但可能更容易识别错误。官方文档未给出统一推荐的非零值，建议从较小值开始试用。"
            "end_window_size" -> "留空使用服务默认值 800 毫秒。设置范围为 300 至 5000 毫秒，官方建议 800 至 1000 毫秒。连续静音达到阈值时判定一句结束；较小值响应更快，但容易把思考停顿切成多句；较大值更能容忍停顿，但等待更长。显式设置此项后，语义分句阈值不生效。松开空格仍会结束整次录音。"
            "vad_segment_duration" -> "留空使用服务默认值 3000 毫秒。控制语义分句可接受的最长静音，只影响分句，不触发确定结果的判停。显式设置了静音分句阈值时，此参数不生效；要使用本参数，请将静音分句阈值留空。它不会让应用在静音后自动停止整次录音。"
            "force_to_speech_time" -> "留空使用服务默认值 0，即不强制。设定时长内，即使录音开头较轻或无声，服务也先按有声处理，以减少过早判停。官方推荐可尝试 1000 毫秒。只影响开头的判断，不会凭空补回未录到的声音。"
            "enable_poi_fc" -> "默认关闭。调用地图领域推荐词服务，辅助识别地名或地点。本应用会自动开启二遍识别。可在语境词典中手动填写城市信息；应用不会读取定位。适合经常口述地点的场景。"
            "enable_music_fc" -> "默认关闭。调用音乐领域推荐词服务，辅助识别歌曲等专业词语。本应用会自动开启二遍识别。适合经常口述音乐相关内容的场景。"
            "corpus" -> "默认不设置。用于提供自定义热词、替换词表和上下文，提升专有名词等内容的识别效果。请输入 JSON 对象，例如：\n{\"context\":\"{\\\"hotwords\\\":[{\\\"word\\\":\\\"语燕输入法\\\"}]}\"}\n其中 context 是再次序列化的 JSON 字符串。服务会收到您手动填写的内容；应用不会自动提取输入框、聊天历史或剪贴板。热词只是提示，不能保证一定命中；长度限制以供应商文档为准。"
            "sensitive_words_filter" -> "默认不设置。可启用系统敏感词库，或指定删除、替换的词语。请输入 JSON 对象，例如：\n{\"system_reserved_filter\":true,\"filter_with_empty\":[\"示例词\"],\"filter_with_signed\":[\"另一个词\"]}\n本应用会按接口要求转为 JSON 字符串。开启会改变识别文本，不需要过滤时请留空。"
            "extra_request" -> "默认不设置。用于补充豆包 request 中尚未提供控件的参数，或覆盖已有参数。例如：\n{\"end_window_size\":1000}\n高级参数优先于对应控件，但应用仍会补全说话人分离等必要依赖。为正确处理累计结果，result_type 只支持 full，model_name 只支持 bigmodel。不要在这里填写密钥。参数拼写、类型和适用模型请参照供应商文档。"
            else -> "请参照供应商接口文档配置。"
        }
    }
}
