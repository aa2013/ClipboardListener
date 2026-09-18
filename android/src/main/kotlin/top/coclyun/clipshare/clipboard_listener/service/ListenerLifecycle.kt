package top.coclyun.clipshare.clipboard_listener.service

import top.coclyun.clipshare.clipboard_listener.ClipboardListeningWay
import top.coclyun.clipshare.clipboard_listener.EnvironmentType

/** 管理监听请求的代次；所有回调必须携带原会话，不能覆盖后续请求。 */
internal class ListenerLifecycle {
    enum class State { STARTING, RUNNING, STOPPING, STOPPED }

    /** 保存一次请求的环境、监听方式及状态，编号仅在当前插件实例内有效。 */
    class Session(val id: Long, val environment: EnvironmentType?, val way: ClipboardListeningWay) {
        @Volatile
        var state = State.STARTING
            internal set
    }

    private var nextId = 0L
    @Volatile
    var current: Session? = null
        private set

    val running: Boolean
        get() = current?.state == State.RUNNING

    /** 接受指定配置；相同配置正在启动或运行时返回同一会话。 */
    @Synchronized
    fun start(environment: EnvironmentType?, way: ClipboardListeningWay): Session {
        current?.let {
            if (isActive(it) && it.environment == environment && it.way == way) return it
            it.state = State.STOPPING
        }
        return Session(++nextId, environment, way).also { current = it }
    }

    /** 判断回调所属会话是否仍可启动、接收内容或发送状态通知。 */
    @Synchronized
    fun isActive(session: Session): Boolean = current === session &&
        (session.state == State.STARTING || session.state == State.RUNNING)

    /** 将当前会话标记为运行中；重复连接和过期连接均不能再次启动线程。 */
    @Synchronized
    fun markRunning(session: Session): Boolean {
        if (current !== session || session.state != State.STARTING) return false
        session.state = State.RUNNING
        return true
    }

    /** 立即使指定会话的回调失效，实际资源清理由服务串行完成。 */
    @Synchronized
    fun stop(session: Session? = current): Session? {
        session ?: return null
        if (session.state != State.STOPPED) session.state = State.STOPPING
        return session
    }

    /** 完成指定会话的清理；旧会话退出不能清除新会话。 */
    @Synchronized
    fun finish(session: Session) {
        session.state = State.STOPPED
        if (current === session) current = null
    }
}
