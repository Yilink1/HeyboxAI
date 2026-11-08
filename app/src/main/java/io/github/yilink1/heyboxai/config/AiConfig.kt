package io.github.yilink1.heyboxai.config

import android.content.Context
import android.content.SharedPreferences

object AiConfig {
    const val PREF_NAME = "heybox_ai_local_cache"

    const val KEY_BASE_URL = "base_url"
    const val KEY_API_KEY = "api_key"
    const val KEY_MODEL = "model_name"
    const val KEY_MAX_FLOORS = "max_floors"
    const val KEY_SYSTEM_PROMPT = "system_prompt"

    const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"
    const val DEFAULT_MAX_FLOORS = 200
    const val DEFAULT_SYSTEM_PROMPT = """你是一个有帮助的 AI 助手，正在帮助用户理解和讨论一个小黑盒社区帖子。
用户可能会就话题内容向你提问，请基于提供的上下文回答。
请用 Markdown 格式回复。"""

    data class ConfigData(
        val baseUrl: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = DEFAULT_MODEL,
        val maxFloors: Int = DEFAULT_MAX_FLOORS,
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    )

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 在小黑盒宿主进程中直接读取配置（纯本地存储，零跨进程延迟，永不失败）
     */
    fun load(context: Context): ConfigData {
        val prefs = getPrefs(context)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val maxFloors = prefs.getInt(KEY_MAX_FLOORS, DEFAULT_MAX_FLOORS)
        val promptRaw = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        val prompt = if (promptRaw.isBlank() ||
            promptRaw.contains("严禁") ||
            promptRaw.contains("切勿") ||
            promptRaw.contains("意图识别与响应规则") ||
            promptRaw.contains("不要使用「#数字」代指人名") ||
            promptRaw.contains("提到具体网友观点时") ||
            !promptRaw.contains("正在帮助用户理解和讨论一个小黑盒社区帖子")
        ) {
            DEFAULT_SYSTEM_PROMPT
        } else {
            promptRaw
        }
        return ConfigData(baseUrl, apiKey, model, maxFloors, prompt)
    }

    fun loadFromProvider(context: Context): ConfigData = load(context)

    /**
     * 在小黑盒宿主进程中保存配置
     */
    fun save(
        context: Context,
        apiKey: String,
        baseUrl: String? = null,
        model: String? = null,
        maxFloors: Int? = null,
        systemPrompt: String? = null
    ) {
        val editor = getPrefs(context).edit().putString(KEY_API_KEY, apiKey)
        if (!baseUrl.isNullOrBlank()) editor.putString(KEY_BASE_URL, baseUrl)
        if (!model.isNullOrBlank()) editor.putString(KEY_MODEL, model)
        if (maxFloors != null) editor.putInt(KEY_MAX_FLOORS, maxFloors)
        if (!systemPrompt.isNullOrBlank()) editor.putString(KEY_SYSTEM_PROMPT, systemPrompt)
        editor.apply()
    }

    fun saveApiKeyLocally(
        context: Context,
        apiKey: String,
        baseUrl: String? = null,
        model: String? = null,
        maxFloors: Int? = null,
        systemPrompt: String? = null
    ) = save(context, apiKey, baseUrl, model, maxFloors, systemPrompt)

    /**
     * 读取指定服务供应商独立保存的 API Key
     */
    fun getProviderApiKey(context: Context, providerName: String): String {
        val prefs = getPrefs(context)
        val key = prefs.getString("api_key_provider_${providerName.trim().lowercase()}", "") ?: ""
        if (key.isNotBlank()) return key

        // 兼容历史版本：如果该供应商还没有独立存储，但当前全局配置属于该供应商，则平滑读取
        val currentBaseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: ""
        val globalKey = prefs.getString(KEY_API_KEY, "") ?: ""
        if (globalKey.isNotBlank()) {
            val pLower = providerName.trim().lowercase()
            if (pLower == "deepseek" && (currentBaseUrl.contains("deepseek") || currentBaseUrl.isBlank())) {
                return globalKey
            }
            if (pLower == "openai" && currentBaseUrl.contains("openai")) {
                return globalKey
            }
            if (pLower == "openrouter" && currentBaseUrl.contains("openrouter")) {
                return globalKey
            }
            if (pLower.contains("硅基") && currentBaseUrl.contains("siliconflow")) {
                return globalKey
            }
            if (pLower == "kimi" && currentBaseUrl.contains("moonshot")) {
                return globalKey
            }
            if (pLower.contains("智谱") && currentBaseUrl.contains("bigmodel")) {
                return globalKey
            }
        }
        return ""
    }

    /**
     * 独立持久化指定服务供应商的 API Key
     */
    fun saveProviderApiKey(context: Context, providerName: String, apiKey: String) {
        if (providerName.isBlank()) return
        getPrefs(context).edit()
            .putString("api_key_provider_${providerName.trim().lowercase()}", apiKey.trim())
            .apply()
    }

    /**
     * 读取指定服务供应商独立保存的 Base URL
     */
    fun getProviderBaseUrl(context: Context, providerName: String, defaultUrl: String): String {
        val prefs = getPrefs(context)
        val key = "base_url_provider_${providerName.trim().lowercase()}"
        val saved = prefs.getString(key, "") ?: ""
        return if (saved.isNotBlank()) saved else defaultUrl
    }

    /**
     * 独立持久化指定服务供应商的 Base URL
     */
    fun saveProviderBaseUrl(context: Context, providerName: String, url: String) {
        if (providerName.isBlank()) return
        getPrefs(context).edit()
            .putString("base_url_provider_${providerName.trim().lowercase()}", url.trim())
            .apply()
    }

    /**
     * 读取指定服务供应商独立保存的 Model 名称
     */
    fun getProviderModel(context: Context, providerName: String, defaultModel: String): String {
        val prefs = getPrefs(context)
        val key = "model_provider_${providerName.trim().lowercase()}"
        val saved = prefs.getString(key, "") ?: ""
        return if (saved.isNotBlank()) saved else defaultModel
    }

    /**
     * 独立持久化指定服务供应商的 Model 名称
     */
    fun saveProviderModel(context: Context, providerName: String, model: String) {
        if (providerName.isBlank()) return
        getPrefs(context).edit()
            .putString("model_provider_${providerName.trim().lowercase()}", model.trim())
            .apply()
    }

    /**
     * 读取常用/收藏模型列表（中转站玩家秒切常用模型）
     */
    fun getFavoriteModels(context: Context): List<String> {
        val prefs = getPrefs(context)
        val raw = prefs.getString("favorite_models_json", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "")
                if (item.isNotBlank() && !list.contains(item)) {
                    list.add(item)
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存/更新常用模型列表
     */
    fun saveFavoriteModels(context: Context, list: List<String>) {
        val arr = org.json.JSONArray()
        for (item in list) {
            if (item.isNotBlank()) arr.put(item.trim())
        }
        getPrefs(context).edit()
            .putString("favorite_models_json", arr.toString())
            .apply()
    }

    /**
     * 添加常用模型（上限 15 个，最近添加排在最前）
     */
    fun addFavoriteModel(context: Context, model: String) {
        if (model.isBlank()) return
        val current = getFavoriteModels(context).toMutableList()
        current.remove(model)
        current.add(0, model)
        val limited = current.take(15)
        saveFavoriteModels(context, limited)
    }

    /**
     * 移除常用模型
     */
    fun removeFavoriteModel(context: Context, model: String) {
        if (model.isBlank()) return
        val current = getFavoriteModels(context).toMutableList()
        current.remove(model)
        saveFavoriteModels(context, current)
    }

    /**
     * 读取当前活跃的服务商名称（如 DeepSeek, OpenAI, 自定义）
     */
    fun getCurrentProvider(context: Context): String {
        return getPrefs(context).getString("current_active_provider", "DeepSeek") ?: "DeepSeek"
    }

    /**
     * 保存当前活跃的服务商名称
     */
    fun saveCurrentProvider(context: Context, providerName: String) {
        if (providerName.isBlank()) return
        getPrefs(context).edit().putString("current_active_provider", providerName.trim()).apply()
    }

    /**
     * 读取指定服务商已勾选启用的模型列表（中转站拉取后打勾保存）
     */
    fun getEnabledModelsForProvider(context: Context, providerName: String): List<String> {
        val prefs = getPrefs(context)
        val key = "enabled_models_${providerName.trim().lowercase()}"
        val raw = prefs.getString(key, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "")
                if (item.isNotBlank() && !list.contains(item)) {
                    list.add(item)
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存指定服务商已勾选启用的模型列表
     */
    fun saveEnabledModelsForProvider(context: Context, providerName: String, models: List<String>) {
        val arr = org.json.JSONArray()
        for (item in models) {
            if (item.isNotBlank()) arr.put(item.trim())
        }
        val key = "enabled_models_${providerName.trim().lowercase()}"
        getPrefs(context).edit()
            .putString(key, arr.toString())
            .apply()
    }
}
