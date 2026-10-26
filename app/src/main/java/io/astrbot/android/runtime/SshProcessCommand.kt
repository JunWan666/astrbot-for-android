package io.astrbot.android.runtime

import kotlinx.coroutines.*
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.Signal
import org.apache.sshd.server.SignalListener
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Each authenticated channel owns exactly one native process tree, including its PTY. */
internal class SshProcessCommand(private val runtime: BootstrapRuntime, private val command: String?,
    private val released: (SshProcessCommand) -> Unit) : Command {
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var error: OutputStream
    private lateinit var exit: ExitCallback
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private val writes = Any()
    private var process: Process? = null
    private var terminalInput: DataOutputStream? = null
    private var environment: Environment? = null
    private var listener: SignalListener? = null
    private var closed = false
    private val completed = AtomicBoolean(false)
    override fun setInputStream(value: InputStream) { input = value }
    override fun setOutputStream(value: OutputStream) { output = value }
    override fun setErrorStream(value: OutputStream) { error = value }
    override fun setExitCallback(value: ExitCallback) { exit = value }

    override fun start(channel: ChannelSession, env: Environment) {
        val terminal = command == null || env.env.containsKey(Environment.ENV_TERM)
        fun dimension(name: String, fallback: Int) = env.env[name]?.toIntOrNull()?.coerceIn(1, 1000) ?: fallback
        val columns = dimension(Environment.ENV_COLUMNS, 80)
        val rows = dimension(Environment.ENV_LINES, 24)
        val args = if (command == null) arrayOf("/bin/bash", "-l") else arrayOf("/bin/bash", "-lc", command)
        val child = synchronized(gate) {
            check(!closed) { "SSH channel closed" }
            val builder = if (terminal) runtime.terminalProcessBuilder(columns, rows, *args) else runtime.processBuilder(*args)
            // Only a terminal name crosses into the guest. SSH environment requests cannot replace PATH or loaders.
            env.env[Environment.ENV_TERM]?.takeIf { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) }?.let { term ->
                val values = builder.command().toMutableList()
                val index = values.indexOf("TERM=xterm-256color")
                if (index >= 0) values[index] = "TERM=$term"
                builder.command(values)
            }
            builder.redirectErrorStream(terminal).start().also { process = it }
        }
        if (terminal) {
            synchronized(writes) { terminalInput = DataOutputStream(child.outputStream) }
            val signals = SignalListener { _, signal ->
                runCatching {
                    when (signal) {
                        Signal.WINCH -> frame(1, byteArrayOf((dimension(Environment.ENV_COLUMNS, columns) shr 8).toByte(),
                            dimension(Environment.ENV_COLUMNS, columns).toByte(), (dimension(Environment.ENV_LINES, rows) shr 8).toByte(),
                            dimension(Environment.ENV_LINES, rows).toByte()))
                        Signal.INT -> frame(0, byteArrayOf(3))
                        Signal.QUIT -> frame(0, byteArrayOf(28))
                        Signal.TERM, Signal.HUP -> closeOwned()
                        else -> Unit
                    }
                }
            }
            environment = env; listener = signals
            env.addSignalListener(signals, Signal.WINCH, Signal.INT, Signal.QUIT, Signal.TERM, Signal.HUP)
        }
        scope.launch {
            try {
                fun drain(source: InputStream, target: OutputStream) = launch {
                    try { source.use {
                        val buffer = ByteArray(8192)
                        while (isActive) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                            target.flush()
                        }
                    } }
                    catch (_: java.io.IOException) { /* normal when a disconnect closes the owned process pipe */ }
                }
                val stdout = drain(child.inputStream, output)
                val stderr = if (!terminal) drain(child.errorStream, error) else null
                launch {
                    try {
                        val buffer = ByteArray(8192)
                        while (isActive) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (terminal) frame(0, buffer, count) else { child.outputStream.write(buffer, 0, count); child.outputStream.flush() }
                        }
                        if (terminal) frame(2, byteArrayOf()) else child.outputStream.close()
                    } catch (_: java.io.IOException) { /* channel close also destroys its owned tree */ }
                }
                val code = child.waitFor()
                stdout.join(); stderr?.join()
                finish(code)
            } catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) { finish(255) }
            finally { closeOwned() }
        }
    }

    private fun frame(type: Int, bytes: ByteArray, count: Int = bytes.size) = synchronized(writes) {
        terminalInput?.apply { writeByte(type); writeInt(count); write(bytes, 0, count); flush() }
    }

    private fun finish(code: Int) {
        if (completed.compareAndSet(false, true)) { released(this); exit.onExit(code) }
    }

    override fun destroy(channel: ChannelSession) = closeOwned()

    fun closeOwned() {
        val child = synchronized(gate) {
            if (closed) return
            closed = true
            process
        }
        listener?.let { environment?.removeSignalListener(it) }
        if (child != null) {
            child.destroy()
            if (!child.waitFor(8, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
            runCatching { child.outputStream.close() }
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
        }
        scope.cancel()
        if (completed.compareAndSet(false, true)) released(this)
    }
}
