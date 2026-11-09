package com.yuyan.inputmethod.dictionary

import com.yuyan.imemodule.data.transfer.SyncProtocol
import com.yuyan.imemodule.data.transfer.TransferFiles
import com.yuyan.inputmethod.DictionaryPackage
import com.yuyan.inputmethod.SchemaRegistry
import kotlinx.serialization.encodeToString
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.util.PriorityQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MobileDictionaryRecipe(private val source: GithubDictionarySource, private val baseline: File, private val output: File) {
    companion object { const val VERSION = 1 }
    private val yaml = DictionaryYaml.parser()
    private val visiting = hashSetOf<String>()
    private val completed = hashMapOf<String, String>()
    private val rule get() = source.rule
    @Suppress("UNCHECKED_CAST") private fun read(file: File): MutableMap<String, Any?> =
        (yaml.load<Any>(file.readText(Charsets.UTF_8).removePrefix("\uFEFF").replace("\t", "    ")) as? Map<String, Any?>)?.toMutableMap() ?: error("Invalid source YAML: ${file.name}")
    private fun write(file: File, data: Map<String, Any?>) { file.parentFile!!.mkdirs(); file.writeText(yaml.dump(data)) }
    private fun planFiles() {
        val paths = linkedSetOf<String>()
        val active = hashSetOf<String>()
        fun addDictionary(name: String) {
            require(active.size < 64 && name !in active) { "Cyclic or excessive dictionary imports" }
            val path = "$name.dict.yaml"
            if (!paths.add(path)) return
            active += name
            val metadata = yaml.load<Any>(source.dictionaryHeader(path)) as? Map<*, *> ?: error("Invalid dictionary header")
            val imports = metadata["import_tables"]
            if (imports != null) {
                require(imports is List<*> && imports.size <= 256) { "Invalid dictionary imports" }
                imports.forEach { addDictionary(it as? String ?: error("Invalid dictionary import")) }
            }
            active -= name
        }
        addDictionary(rule.dictionary)
        for (logical in SchemaRegistry.chineseSchemas) {
            if (logical == "t9_pinyin" || logical == "double_pinyin_ls17") continue
            val stem = if (logical == "pinyin") rule.dictionary else rule.schemaPrefix + logical.replace("_natural", "")
            paths += "$stem.schema.yaml"
        }
        if (rule.family == "frost") {
            addDictionary("melt_eng"); addDictionary("stroke")
            paths += source.paths("opencc/"); paths += source.paths("lua/")
            paths += listOf("essay.txt", "zh-moqi.gram", "en_dicts/cn_en.txt", "melt_eng.schema.yaml", "cn_dicts/8105.dict.yaml", "cn_dicts/base.dict.yaml", "stroke.dict.yaml")
        }
        paths += source.paths("LICENSE")
        source.plan(paths)
    }
    @Suppress("UNCHECKED_CAST") private fun section(data: MutableMap<String, Any?>, key: String): MutableMap<String, Any?> =
        (data[key] as? Map<String, Any?>)?.toMutableMap()?.also { data[key] = it } ?: error("Missing source section: $key")

    private fun dictionary(name: String, target: String = "${rule.family}_${name.replace('/', '_')}"): String {
        completed[name]?.let { return it }
        require(visiting.size < 64 && visiting.add(name)) { "Cyclic or excessive dictionary imports" }
        val file = source.file("$name.dict.yaml")
        file.bufferedReader().use { reader ->
            val header = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: error("Dictionary header has no terminator")
                if (line.trim() == "...") break
                header.appendLine(line.removePrefix("\uFEFF"))
                require(header.length <= 1024 * 1024) { "Dictionary header is too large" }
            }
            @Suppress("UNCHECKED_CAST") val metadata = (yaml.load<Any>(header.toString()) as? Map<String, Any?>)?.toMutableMap() ?: error("Invalid dictionary header")
            metadata["name"] = target
            metadata["version"] = source.commit
            val imports = metadata["import_tables"]
            if (imports != null) {
                require(imports is List<*> && imports.size <= 256) { "Invalid dictionary imports" }
                metadata["import_tables"] = imports.map { child -> dictionary(child as? String ?: error("Invalid dictionary import")) }
            }
            File(output, "$target.dict.yaml").bufferedWriter().use { writer ->
                writer.write(yaml.dump(metadata)); writer.write("...\n"); reader.copyTo(writer)
            }
        }
        visiting.remove(name); completed[name] = target
        return target
    }

    fun build(): File {
        planFiles()
        output.mkdirs()
        if (rule.family == "frost") baseline.copyRecursively(output, overwrite = true)
        output.listFiles().orEmpty().filter { it.name.endsWith(".dict.yaml") }.forEach { it.delete() }
        dictionary(rule.dictionary, "rime_${rule.family}")
        SchemaRegistry.chineseSchemas.forEach { logical ->
            val template = File(baseline, "templates/selfopt_frost_$logical.schema.yaml")
            val config = read(template)
            val schema = section(config, "schema")
            schema["schema_id"] = "selfopt_${rule.family}_$logical"
            schema["name"] = "${rule.name} $logical"
            schema["version"] = source.commit
            val translator = section(config, "translator")
            translator["dictionary"] = "rime_${rule.family}"
            translator["user_dict"] = "rime_${rule.family}"
            translator["prism"] = "selfopt_${rule.family}_$logical"
            if (rule.family == "ice") { config.remove("grammar"); translator.remove("grammar_penalty") }
            if (logical != "t9_pinyin" && logical != "double_pinyin_ls17") {
                val name = if (logical == "pinyin") rule.dictionary else rule.schemaPrefix + logical.replace("_natural", "")
                config["speller"] = section(read(source.file("$name.schema.yaml")), "speller")
            }
            val speller = section(config, "speller")
            val rules = (speller["algebra"] as? List<*>).orEmpty().map { it as? String ?: error("Invalid spelling rule") }
            speller["algebra"] = listOf("__SELFOPT_FUZZY__") + rules.filter { it != "__SELFOPT_FUZZY__" }
            write(File(output, "templates/selfopt_${rule.family}_$logical.schema.yaml"), config)
        }
        if (rule.family == "frost") updateShared()
        for (path in source.paths("LICENSE")) copy(path, "licenses/rime-${rule.family}/$path")
        File(output, "sources.json").writeText(org.json.JSONObject().put("repository", rule.repository).put("revision", source.commit).put("recipe", VERSION).toString())
        File(output, "bundle.json").writeText(org.json.JSONObject().put("format", 1).put("compatibility", "selfopt-1")
            .put("family", rule.family).put("revision", source.commit).put("updatedAt", source.updatedAt).toString())
        return output
    }

    private fun copy(sourcePath: String, targetPath: String = sourcePath) {
        val target = TransferFiles.child(output, targetPath)
        target.parentFile!!.mkdirs(); source.file(sourcePath).copyTo(target, overwrite = true)
    }

    private fun updateShared() {
        File(output, "opencc").deleteRecursively()
        File(output, "lua").deleteRecursively()
        for (prefix in listOf("opencc/", "lua/")) source.paths(prefix).forEach { copy(it) }
        for (required in listOf("lua/autocap_filter.lua", "lua/reduce_english_filter.lua", "opencc/emoji.json", "opencc/s2t.json")) {
            require(File(output, required).isFile) { "Missing source file: $required" }
        }
        File(baseline, "lua/selfopt_stroke.lua").copyTo(File(output, "lua/selfopt_stroke.lua"), overwrite = true)
        copy("essay.txt"); copy("zh-moqi.gram")
        dictionary("melt_eng", "melt_eng")
        copy("en_dicts/cn_en.txt", "selfopt_cn_en.txt")
        val english = read(File(baseline, "selfopt_english.schema.yaml"))
        val upstream = read(source.file("melt_eng.schema.yaml"))
        upstream.filterKeys { it.startsWith("algebra_") }.forEach { (key, value) -> english[key] = value }
        section(english, "speller")["algebra"] = section(upstream, "speller")["algebra"]
        write(File(output, "selfopt_english.schema.yaml"), english)
        dictionary("stroke", "stroke")
        val frequencies = hashMapOf<String, Double>()
        source.file("cn_dicts/8105.dict.yaml").forEachLine { line ->
            val fields = line.split('\t')
            if (fields.size >= 3 && fields[0].length == 1) fields[2].toDoubleOrNull()?.let { frequencies[fields[0]] = maxOf(frequencies[fields[0]] ?: 0.0, it) }
        }
        val strokes = mutableListOf<Pair<String, String>>()
        source.file("stroke.dict.yaml").forEachLine { line ->
            val parts = line.split('\t'); if (parts.size >= 2 && parts[1].matches(Regex("[hspnz]+"))) strokes += parts[0] to parts[1]
        }
        strokes.sortWith(compareBy<Pair<String, String>> { it.second.length }.thenByDescending { frequencies[it.first] ?: 0.0 }.thenBy { it.first })
        File(output, "selfopt_stroke.tsv").bufferedWriter().use { writer -> strokes.forEach { writer.append(it.first).append('\t').append(it.second).append('\n') } }
        val heap = PriorityQueue<Pair<Double, String>>(compareBy<Pair<Double, String>> { it.first }.thenBy { it.second })
        val chinese = Regex("[\u3400-\u9fff]{2,5}")
        source.file("cn_dicts/base.dict.yaml").forEachLine { line ->
            val fields = line.split('\t')
            if (fields.size >= 3 && fields[0].matches(chinese)) fields[2].toDoubleOrNull()?.let { weight ->
                heap.add(weight to fields[0]); if (heap.size > 40000) heap.poll()
            }
        }
        val predictions = sortedMapOf<String, MutableMap<String, Double>>()
        heap.sortedWith(compareByDescending<Pair<Double, String>> { it.first }.thenByDescending { it.second }).forEach { (weight, word) ->
            for (split in 1 until word.length) {
                val values = predictions.getOrPut(word.substring(0, split)) { linkedMapOf() }
                val suffix = word.substring(split)
                if (suffix in values || values.size < 16) values[suffix] = maxOf(weight, values[suffix] ?: 0.0)
            }
        }
        File(output, "predict.tsv").bufferedWriter().use { writer -> predictions.forEach { (prefix, values) ->
            values.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).forEach {
                writer.append("$prefix\t${it.key}\t${it.value}\n")
            }
        } }
    }

    fun archive(destination: File): DictionaryPackage {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            output.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(output).invariantSeparatorsPath }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(output).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        return DictionaryPackage(rule.family, source.commit, TransferFiles.sha256(destination), destination.length(), updatedAt = source.updatedAt,
            summary = source.summary, recipe = VERSION)
    }
}
