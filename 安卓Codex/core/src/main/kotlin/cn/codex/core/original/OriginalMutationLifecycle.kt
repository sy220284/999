package cn.codex.core.original

/** 持久记录与 Android 前台托管由宿主实现，核心不依赖 Android。 */
interface OriginalMutationLifecycle {
    fun begin(): String
    fun finish(ticket: String, confirmed: Boolean)
    object None : OriginalMutationLifecycle {
        override fun begin() = ""
        override fun finish(ticket: String, confirmed: Boolean) = Unit
    }
}
