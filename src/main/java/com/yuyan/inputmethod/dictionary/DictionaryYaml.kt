package com.yuyan.inputmethod.dictionary

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

object DictionaryYaml {
    fun parser(): Yaml {
        val loading = LoaderOptions().apply { codePointLimit = 2 * 1024 * 1024; maxAliasesForCollections = 32; isAllowDuplicateKeys = false }
        val dumping = DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; width = 120 }
        return Yaml(SafeConstructor(loading), Representer(dumping), dumping, loading).also {
            it.setBeanAccess(org.yaml.snakeyaml.introspector.BeanAccess.FIELD)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun render(content: String, fuzzyRules: List<String>): String {
        val yaml = parser()
        val document = (yaml.load<Any>(content) as? Map<String, Any?>)?.toMutableMap() ?: error("Invalid schema template")
        val speller = (document["speller"] as? Map<String, Any?>)?.toMutableMap() ?: error("Invalid schema speller")
        val algebra = speller["algebra"] as? List<*> ?: error("Invalid schema algebra")
        require(algebra.count { it == "__SELFOPT_FUZZY__" } == 1) { "Invalid fuzzy spelling template" }
        speller["algebra"] = algebra.flatMap { if (it == "__SELFOPT_FUZZY__") fuzzyRules else listOf(it as? String ?: error("Invalid spelling rule")) }
        document["speller"] = speller
        return yaml.dump(document)
    }
}
