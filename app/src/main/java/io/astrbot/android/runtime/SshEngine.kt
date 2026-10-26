package io.astrbot.android.runtime

import io.astrbot.android.checkLocalized

import io.astrbot.android.AppText
import io.astrbot.android.R

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.channel.ChannelSessionFactory
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import org.apache.sshd.server.shell.ShellFactory
import java.io.File
import java.security.MessageDigest
import java.time.Duration

data class SshState(val configuration: SshConfiguration = SshConfiguration(), val running: Boolean = false,
    val desired: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val fingerprint: String? = null, val paused: Boolean = false)

object SshStore { val state = MutableStateFlow(SshState()) }

/** Owned by RuntimeController, serialized with guest maintenance. A stopped extension owns no listener. */
class SshEngine(private val context: Context) {
    private var server: SshServer? = null
    private val gate = Any()
    private var closing = true
    private val commands = mutableSetOf<SshProcessCommand>()
    private val repository = SshRepository(context)

    fun refresh() {
        repository.ensureDefault()
        val config = repository.configuration()
        SshStore.state.update { it.copy(configuration = config, desired = repository.desired(), fingerprint = it.fingerprint.takeIf { config.installed }) }
    }

    fun start() {
        refresh()
        if (server != null) return
        val config = repository.configuration()
        checkLocalized(config.installed) { AppText.get(R.string.ui_install_the_ssh_extension_first) }
        checkLocalized(File(BootstrapRuntime(context).rootfs, ".astrbot-bootstrap.json").isFile) { AppText.get(R.string.ui_initialize_the_ubuntu_runtime_first) }
        val passwordDigest = MessageDigest.getInstance("SHA-256").digest(repository.password().toByteArray(Charsets.UTF_8))
        PathUtils.setUserHomeFolderResolver { File(context.filesDir, "extensions/ssh").toPath() }
        val key = repository.hostKey()
        val ssh = SshServer.setUpDefaultServer()
        try {
            ssh.host = if (config.lanEnabled) "0.0.0.0" else "127.0.0.1"
            ssh.port = config.port
            ssh.keyPairProvider = KeyPairProvider.wrap(key)
            ssh.userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
            ssh.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { name, supplied, _ ->
                name == "root" && supplied.length <= 128 && MessageDigest.isEqual(passwordDigest,
                    MessageDigest.getInstance("SHA-256").digest(supplied.toByteArray(Charsets.UTF_8)))
            }
            ssh.forwardingFilter = RejectAllForwardingFilter.INSTANCE
            ssh.channelFactories = listOf(ChannelSessionFactory.INSTANCE)
            ssh.subsystemFactories = emptyList()
            ssh.commandFactory = CommandFactory { _, command -> newCommand(command) }
            ssh.shellFactory = ShellFactory { newCommand(null) }
            CoreModuleProperties.NIO_WORKERS.set(ssh, 2)
            CoreModuleProperties.AUTH_TIMEOUT.set(ssh, Duration.ofSeconds(30))
            CoreModuleProperties.IDLE_TIMEOUT.set(ssh, Duration.ofMinutes(30))
            CoreModuleProperties.MAX_AUTH_REQUESTS.set(ssh, 3)
            CoreModuleProperties.MAX_CONCURRENT_SESSIONS.set(ssh, 4)
            CoreModuleProperties.MAX_CONCURRENT_CHANNELS.set(ssh, 4)
            synchronized(gate) { closing = false }
            ssh.start()
            server = ssh
            SshStore.state.update { it.copy(running = true, paused = false, error = null,
                fingerprint = org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(key.public)) }
        } catch (failure: Throwable) {
            synchronized(gate) { closing = true }
            ssh.stop(true)
            throw failure
        }
    }

    private fun newCommand(command: String?): SshProcessCommand = synchronized(gate) {
        checkLocalized(!closing && commands.size < 4) { AppText.get(R.string.ui_ssh_session_limit_reached_or_the_server_is_stopping) }
        checkLocalized(command == null || command.length <= 64 * 1024) { AppText.get(R.string.ui_ssh_command_is_too_long) }
        SshProcessCommand(BootstrapRuntime(context), command) { finished -> synchronized(gate) { commands.remove(finished) } }
            .also { commands.add(it) }
    }

    fun stop(paused: Boolean = false) {
        val owned = synchronized(gate) { closing = true; commands.toList() }
        try { server?.stop(true) }
        finally {
            owned.forEach { it.closeOwned() }
            server = null
            SshStore.state.update { it.copy(running = false, paused = paused) }
        }
    }
}
