package com.apoorvdarshan.verceltics.ui.cloudflare.operations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which feature package renders a pushed Cloudflare operations screen. */
enum class CloudflareOperationsDomain {
    ZONE,
    PAGES,
    WORKER,
    STORAGE,
}

/**
 * One pushed Cloudflare operations screen (iOS `NavigationLink` destination).
 *
 * [screen] and [args] are owned by the domain package that renders [domain]; [title] is shown in
 * the Cloudflare top bar. Routes are persisted in [SavedStateHandle], so every field must be text.
 */
data class CloudflareOperationsRoute(
    val domain: CloudflareOperationsDomain,
    val screen: String,
    val title: String,
    val args: List<String> = emptyList(),
) {
    init {
        require(screen.isNotBlank() && screen.length <= 64 && FIELD_SEPARATOR !in screen) { "Invalid Cloudflare screen." }
        require(title.length <= 512 && FIELD_SEPARATOR !in title) { "Invalid Cloudflare screen title." }
        require(args.size <= 12 && args.all { it.length <= 4_096 && FIELD_SEPARATOR !in it }) { "Invalid Cloudflare route." }
    }

    /** Identity of this screen instance (title excluded so a renamed resource keeps its state). */
    val key: String get() = (listOf(domain.name, screen) + args).joinToString(KEY_SEPARATOR)

    fun arg(index: Int): String = args.getOrElse(index) { "" }

    fun encode(): String = (listOf(domain.name, screen, title) + args).joinToString(FIELD_SEPARATOR.toString())

    companion object {
        private const val FIELD_SEPARATOR = '\u001F'
        private const val KEY_SEPARATOR = "\u001E"

        fun decode(value: String): CloudflareOperationsRoute? = runCatching {
            val fields = value.split(FIELD_SEPARATOR)
            require(fields.size >= 3)
            CloudflareOperationsRoute(
                domain = CloudflareOperationsDomain.valueOf(fields[0]),
                screen = fields[1],
                title = fields[2],
                args = fields.drop(3),
            )
        }.getOrNull()
    }
}

/**
 * Back stack for Cloudflare operations screens, kept by the activity-scoped [CloudflareViewModel]
 * so it survives configuration changes and process recreation (via [SavedStateHandle]).
 *
 * Each screen gets its own [ViewModelStore]; popping or clearing a screen clears its ViewModels so
 * in-flight requests are cancelled instead of leaking across resources.
 */
class CloudflareOperationsNavigator(private val savedStateHandle: SavedStateHandle? = null) {
    private val _stack = MutableStateFlow(restore())
    val stack: StateFlow<List<CloudflareOperationsRoute>> = _stack.asStateFlow()

    private val _refreshRequests = MutableStateFlow(0)

    /** Incremented when the top-bar refresh is pressed while an operations screen is visible. */
    val refreshRequests: StateFlow<Int> = _refreshRequests.asStateFlow()

    private val stores = LinkedHashMap<String, ViewModelStore>()

    val top: CloudflareOperationsRoute? get() = _stack.value.lastOrNull()

    fun push(route: CloudflareOperationsRoute) {
        val current = _stack.value
        if (current.lastOrNull()?.key == route.key) return
        val trimmed = current.filterNot { it.key == route.key }
        val next = (trimmed + route).takeLast(MAXIMUM_DEPTH)
        (current - next.toSet()).forEach { releaseStore(it.key) }
        update(next)
    }

    /** Pops the top screen. Returns false when the stack was already empty. */
    fun pop(): Boolean {
        val current = _stack.value
        val removed = current.lastOrNull() ?: return false
        update(current.dropLast(1))
        releaseStore(removed.key)
        return true
    }

    /** Pops screens until [predicate] no longer matches the top (e.g. after deleting a resource). */
    fun popWhile(predicate: (CloudflareOperationsRoute) -> Boolean) {
        while (top?.let(predicate) == true) pop()
    }

    /** Clears every pushed screen and every resource-scoped ViewModel. */
    fun clear() {
        update(emptyList())
        stores.values.toList().forEach(ViewModelStore::clear)
        stores.clear()
    }

    fun requestRefresh() {
        _refreshRequests.value += 1
    }

    /** ViewModels for one screen instance. The store lives until that screen leaves the stack. */
    fun storeFor(key: String): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    fun releaseStore(key: String) {
        stores.remove(key)?.clear()
    }

    private fun update(routes: List<CloudflareOperationsRoute>) {
        _stack.value = routes
        savedStateHandle?.set(SAVED_STACK, ArrayList(routes.map(CloudflareOperationsRoute::encode)))
    }

    private fun restore(): List<CloudflareOperationsRoute> =
        savedStateHandle?.get<ArrayList<String>>(SAVED_STACK).orEmpty()
            .mapNotNull(CloudflareOperationsRoute::decode)
            .takeLast(MAXIMUM_DEPTH)

    companion object {
        const val MAXIMUM_DEPTH: Int = 12
        internal const val SAVED_STACK = "cloudflare.operationsStack"

        /** ViewModel-store key for the root detail of a selected zone, Pages project or Worker. */
        fun resourceKey(kind: String, id: String): String = "resource\u001E$kind\u001E$id"
    }
}
