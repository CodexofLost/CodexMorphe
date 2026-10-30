package io.github.codexoflost.codexmorphe.hook.model

import com.google.gson.annotations.SerializedName

/**
 * Manifest containing dynamic hook definitions packaged inside a bundle APK.
 */
data class HookRecipeManifest(
    @SerializedName("version")
    val version: Int = 1,
    @SerializedName("package_name")
    val packageName: String = "",
    @SerializedName("entrypoints")
    val entrypoints: List<String> = emptyList(),
    @SerializedName("resource_fallbacks")
    val resourceFallbacks: List<String> = emptyList(),
    @SerializedName("hooks")
    val hooks: List<HookDefinition> = emptyList()
)

/**
 * Single hook definition describing bytecode match criteria and interceptor action.
 */
data class HookDefinition(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("action")
    val action: String = "METHOD_REPLACE", // CONSTANT_REPLACE, EARLY_RETURN_SUPPRESSOR, ARG_REWRITE, RETURN_TRANSFORM, METHOD_REPLACE
    @SerializedName("extension_call")
    val extensionCall: String? = null,
    @SerializedName("extension_class")
    val extensionClass: String? = null,
    @SerializedName("extension_method")
    val extensionMethod: String? = null,
    @SerializedName("constant_val")
    val constantVal: Any? = null,
    @SerializedName("arg_index")
    val argIndex: Int = 0,
    @SerializedName("fingerprint")
    val fingerprint: FingerprintQuery = FingerprintQuery()
)

/**
 * Criteria passed to DexKit for locating target methods.
 */
data class FingerprintQuery(
    @SerializedName("declaring_class")
    val declaringClass: String? = null,
    @SerializedName("declaring_class_ends_with")
    val declaringClassEndsWith: String? = null,
    @SerializedName("method_name")
    val methodName: String? = null,
    @SerializedName("return_type")
    val returnType: String? = null,
    @SerializedName("param_types")
    val paramTypes: List<String> = emptyList(),
    @SerializedName("param_count")
    val paramCount: Int? = null,
    @SerializedName("strings")
    val strings: List<String> = emptyList(),
    @SerializedName("opcodes")
    val opcodes: List<String> = emptyList(),
    @SerializedName("literal")
    val literal: Long? = null
)
