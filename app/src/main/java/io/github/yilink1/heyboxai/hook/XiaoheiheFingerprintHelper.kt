package io.github.yilink1.heyboxai.hook

import android.app.Activity
import android.util.Log
import java.lang.reflect.Method

/**
 * 小黑盒关键对象与方法特征指纹签名匹配器
 *
 * 核心目标：
 * 告别对单字母/短混淆名称（如 O4, X, h, i, k）的单一硬编码依赖。
 * 结合「已知历史名称优先」+「结构特征/参数类型/接口签名启发式扫描」，
 * 即使小黑盒发版更新后混淆字典重排，也能自动容错匹配！
 */
object XiaoheiheFingerprintHelper {

    private const val TAG = "HeyboxAI_Fingerprint"

    /**
     * 1. 定位帖子 ViewModel (安全访问，绝不盲目调用 Activity 上的任意方法以防触发状态重置/闪退)
     */
    fun findPostViewModel(activity: Activity): Any? {
        val clazz = activity.javaClass

        // ① 优先从 Jetpack ViewModelStore 极速安全提取已实例化的 ViewModel（纯对象读取，0 方法调用副作用）
        try {
            val storeMethod = clazz.methods.firstOrNull { it.name == "getViewModelStore" && it.parameterCount == 0 }
            val store = storeMethod?.invoke(activity)
            if (store != null) {
                var curStoreClass: Class<*>? = store.javaClass
                while (curStoreClass != null && curStoreClass != Any::class.java) {
                    for (f in curStoreClass.declaredFields) {
                        try {
                            f.isAccessible = true
                            val mapObj = f.get(store)
                            if (mapObj is Map<*, *>) {
                                for (vm in mapObj.values) {
                                    if (vm != null) {
                                        val cName = vm.javaClass.name
                                        if (cName.contains("LinkPost") || cName.contains("PostDetail") || cName.contains("DetailVM")) {
                                            Log.i(TAG, "从 ViewModelStore 命中帖子 ViewModel: $cName")
                                            return vm
                                        }
                                        if (isViewModelClass(vm.javaClass) && findLinkPostModel(vm) != null) {
                                            Log.i(TAG, "从 ViewModelStore 结构特征命中 ViewModel: $cName")
                                            return vm
                                        }
                                    }
                                }
                            }
                        } catch (ignored: Throwable) {}
                    }
                    curStoreClass = curStoreClass.superclass
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ViewModelStore 读取跳过: ${e.message}")
        }

        // ② 安全扫描 Activity 字段（读字段绝对无代码执行副作用）
        var cur: Class<*>? = clazz
        while (cur != null && cur != Activity::class.java && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                if (f.type.name.contains("ViewModel") || f.type.name.contains("LinkPost") || f.type.name.contains("DetailVM")) {
                    try {
                        f.isAccessible = true
                        val obj = f.get(activity)
                        if (obj != null) {
                            Log.i(TAG, "通过字段特征匹配到 ViewModel: field=${f.name}, class=${obj.javaClass.name}")
                            return obj
                        }
                    } catch (ignored: Throwable) {}
                }
            }
            cur = cur.superclass
        }

        // ③ 仅在必要时尝试已知只读 Getter（绝不调用 Unit 返回值或可能修改状态的未知方法）
        val safeGetterNames = setOf("O4", "getPostViewModel", "getDetailViewModel", "getViewModel")
        for (m in clazz.methods) {
            if (m.parameterCount != 0) continue
            if (m.returnType == Void.TYPE || m.returnType.isPrimitive || m.returnType.name == "kotlin.Unit") continue

            val isSafeName = safeGetterNames.contains(m.name) || (m.name.startsWith("get") && m.name.contains("ViewModel"))
            val returnsViewModel = isViewModelClass(m.returnType) || m.returnType.name.contains("LinkPost") || m.returnType.name.contains("DetailVM")

            if (isSafeName || returnsViewModel) {
                try {
                    val candidate = m.invoke(activity) ?: continue
                    if (isViewModelClass(candidate.javaClass) || candidate.javaClass.name.contains("LinkPost")) {
                        Log.i(TAG, "通过安全 Getter 匹配到 ViewModel: method=${m.name}")
                        return candidate
                    }
                } catch (ignored: Throwable) {}
            }
        }

        Log.w(TAG, "未能定位到 ViewModel")
        return null
    }

    private fun isViewModelClass(c: Class<*>): Boolean {
        var cur: Class<*>? = c
        while (cur != null) {
            if (cur.name == "androidx.lifecycle.ViewModel") return true
            cur = cur.superclass
        }
        return false
    }

    /**
     * 2. 定位 LinkPostModel (安全访问，优先从字段读取，严禁调用清空/重置等有副作用的方法)
     */
    fun findLinkPostModel(viewModel: Any?): Any? {
        if (viewModel == null) return null
        val clazz = viewModel.javaClass

        // ① 优先从 ViewModel 字段直接读取（零代码调用副作用）
        var cur: Class<*>? = clazz
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                try {
                    f.isAccessible = true
                    val candidate = f.get(viewModel) ?: continue
                    if (isPostModelStructure(candidate)) {
                        Log.i(TAG, "通过字段结构特征匹配到 LinkPostModel: field=${f.name}")
                        return candidate
                    }
                } catch (ignored: Throwable) {}
            }
            cur = cur.superclass
        }

        // ② 尝试已知历史混淆名 (X)
        try {
            val legacyX = clazz.methods.firstOrNull { it.name == "X" && it.parameterCount == 0 }
            val model = legacyX?.invoke(viewModel)
            if (model != null && isPostModelStructure(model)) {
                return model
            }
        } catch (ignored: Throwable) {}

        // ③ 安全扫描 Getter 方法：严禁调用非 Getter 或有修改状态风险的方法
        val dangerousKeywords = setOf("clear", "reset", "remove", "delete", "clean", "destroy", "cancel", "refresh", "load", "fetch", "init", "update", "set", "on")
        for (m in clazz.methods) {
            if (m.parameterCount != 0 || m.returnType == Void.TYPE || m.returnType.isPrimitive || m.returnType.name == "kotlin.Unit") continue
            val name = m.name
            if (dangerousKeywords.any { name.startsWith(it) || name.contains(it, ignoreCase = true) }) continue
            if (name in setOf("getClass", "hashCode", "toString")) continue

            try {
                val retName = m.returnType.name
                val isLikelyGetter = name.startsWith("get") || retName.contains("Model") || retName.contains("Post") || retName.contains("Detail")
                if (!isLikelyGetter) continue

                val candidate = m.invoke(viewModel) ?: continue
                if (isPostModelStructure(candidate)) {
                    Log.i(TAG, "通过结构特征匹配到 LinkPostModel: method=${m.name}, class=${candidate.javaClass.name}")
                    return candidate
                }
            } catch (ignored: Throwable) {}
        }

        return null
    }

    /**
     * 检验对象是否具备帖子模型的核心结构特征
     */
    private fun isPostModelStructure(candidate: Any): Boolean {
        val cName = candidate.javaClass.name
        if (cName.contains("LinkPostModel") || cName.contains("PostDetailModel")) {
            return true
        }
        val methodNames = candidate.javaClass.methods.map { it.name }.toSet()
        // 帖子模型必含的核心方法特征
        val hasCommentSection = methodNames.any { it.contains("CommentSection", ignoreCase = true) }
        val hasContentSection = methodNames.any { it.contains("ContentSection", ignoreCase = true) }
        val hasMetaInfo = methodNames.any { it.contains("MetaInfo", ignoreCase = true) || it.contains("LinkId", ignoreCase = true) }

        return (hasCommentSection && hasContentSection) || (hasCommentSection && hasMetaInfo)
    }

    /**
     * 3. 定位 LinkPostRepository (替代仅匹配字段名包含 "LinkPostRepository")
     * 特征：持有多参数挂起网络请求方法的仓库对象
     */
    fun findLinkPostRepository(viewModel: Any?): Any? {
        if (viewModel == null) return null
        var current: Class<*>? = viewModel.javaClass
        while (current != null && current != Any::class.java) {
            for (f in current.declaredFields) {
                try {
                    f.isAccessible = true
                    val obj = f.get(viewModel) ?: continue
                    val typeName = f.type.name
                    // ① 字段类型匹配
                    if (typeName.contains("LinkPostRepository") || typeName.contains("Repository")) {
                        return obj
                    }
                    // ② 结构特征匹配：拥有分页方法 (12参数) 或 楼中楼方法 (5参数)
                    if (findMainCommentPaginateMethod(obj) != null || findSubCommentPaginateMethod(obj) != null) {
                        return obj
                    }
                } catch (ignored: Throwable) {}
            }
            current = current.superclass
        }
        return null
    }

    /**
     * 4. 定位主评论分页方法 (替代硬编码 repo.h())
     * 特征：12 个参数，第 4 个参数为 Map 类型，末尾参数为 Continuation
     * 注意：跨 ClassLoader 时 Map::class.java.isAssignableFrom 会假阴性，按类名判断
     */
    fun findMainCommentPaginateMethod(repo: Any): Method? {
        val methods = repo.javaClass.methods

        // ① 优先匹配已知历史名 "h"
        val legacyH = methods.firstOrNull { it.name == "h" && it.parameterCount == 12 }
        if (legacyH != null) return legacyH

        fun isMapType(t: Class<*>): Boolean =
            t.name == "java.util.Map" || Map::class.java.isAssignableFrom(t)

        // ② 签名特征匹配：参数总量 12，且第 4 个参数为 Map，且末尾参数为 Continuation
        return methods.firstOrNull { m ->
            if (m.parameterCount != 12) return@firstOrNull false
            val pTypes = m.parameterTypes
            pTypes[0] == String::class.java &&
                isMapType(pTypes[3]) &&
                pTypes[11].name.contains("Continuation")
        } ?: methods.firstOrNull { m ->
            // 次级容错：参数总量 12 且末尾为 Continuation
            m.parameterCount == 12 && m.parameterTypes[11].name.contains("Continuation")
        }
    }

    /**
     * 5. 定位折叠楼中楼分页方法 (替代硬编码 repo.i())
     * 特征：5 个参数，末尾参数为 Continuation
     */
    fun findSubCommentPaginateMethod(repo: Any): Method? {
        val methods = repo.javaClass.methods

        // ① 优先匹配已知历史名 "i"
        val legacyI = methods.firstOrNull { it.name == "i" && it.parameterCount == 5 }
        if (legacyI != null) return legacyI

        // ② 签名特征匹配：参数总量 5，且末尾参数为 Continuation
        return methods.firstOrNull { m ->
            m.parameterCount == 5 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == String::class.java &&
                m.parameterTypes[4].name.contains("Continuation")
        }
    }

    /**
     * 6. 提取帖子 link_id (替代硬编码 fieldK = viewModel.declaredField("k"))
     * 特征：Intent > PostModel.getMetaInfo > 正则数字串 > ViewModel 纯数字 ID 字段
     */
    fun extractLinkId(activity: Activity?, viewModel: Any?, postModel: Any?): String {
        // ① Intent 参数
        activity?.intent?.extras?.let { bundle ->
            for (key in listOf("link_id", "linkId", "post_id", "postId", "id", "feed_id")) {
                val value = bundle.get(key)
                if (value != null && value.toString().isNotBlank() && value.toString() != "0") {
                    return value.toString()
                }
            }
        }

        val dataStr = activity?.intent?.dataString ?: ""
        if (dataStr.isNotBlank()) {
            Regex("""\d{5,}""").find(dataStr)?.value?.let { return it }
        }

        // ② PostModel MetaInfo
        if (postModel != null) {
            try {
                val metaMethod = postModel.javaClass.methods.firstOrNull { it.name.contains("MetaInfo", ignoreCase = true) }
                val metaObj = metaMethod?.invoke(postModel)
                if (metaObj != null) {
                    for (m in metaObj.javaClass.methods) {
                        if (m.parameterCount == 0 && (m.name.contains("LinkId", ignoreCase = true) || m.name.contains("Link_id", ignoreCase = true))) {
                            val id = m.invoke(metaObj)?.toString()
                            if (!id.isNullOrBlank()) return id
                        }
                    }
                }
            } catch (ignored: Throwable) {}
        }

        // ③ ViewModel 内部查找（优先查找纯数字字符串字段，兼容历史 "k" 字段）
        if (viewModel != null) {
            // 优先历史混淆字段 k
            try {
                val fieldK = viewModel.javaClass.getDeclaredField("k")
                fieldK.isAccessible = true
                val v = fieldK.get(viewModel)?.toString()
                if (!v.isNullOrBlank() && v.matches(Regex("""\d+"""))) return v
            } catch (ignored: Throwable) {}

            // 动态扫描：寻找值为 5 位以上纯数字的 String 字段（小黑盒帖子 ID 特征）
            var cur: Class<*>? = viewModel.javaClass
            while (cur != null && cur != Any::class.java) {
                for (f in cur.declaredFields) {
                    if (f.type == String::class.java) {
                        try {
                            f.isAccessible = true
                            val value = f.get(viewModel) as? String
                            if (!value.isNullOrBlank() && value.matches(Regex("""\d{5,}"""))) {
                                return value
                            }
                        } catch (ignored: Throwable) {}
                    }
                }
                cur = cur.superclass
            }
        }

        return ""
    }
}
