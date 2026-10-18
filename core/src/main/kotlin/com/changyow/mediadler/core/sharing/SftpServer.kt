package com.changyow.mediadler.core.sharing

import org.apache.sshd.common.file.FileSystemFactory
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.RejectAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import org.apache.sshd.sftp.server.FileHandle
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.OpenOption
import java.nio.file.Files
import java.nio.file.attribute.FileAttribute
import java.time.Duration

class SftpServer(
    private val files: SharedFiles,
    private val address: InetAddress,
    private val port: Int,
    private val hostKey: File,
    private val authenticate: (String, String) -> Boolean,
) : Closeable {
    private var server: SshServer? = null

    fun start() {
        check(server == null)
        // Android has no user.home. MINA initializes its default key paths before our factories.
        if (OsUtils.isAndroid()) PathUtils.setUserHomeFolderResolver { requireNotNull(hostKey.parentFile).toPath() }
        val ssh = SshServer.setUpDefaultServer()
        try {
            ssh.host = address.hostAddress
            ssh.port = port
            ssh.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath()).apply {
                algorithm = "RSA"
                keySize = 3072
                // Generate/validate before accepting clients, outside their authentication deadline.
                check(loadKeys(null).isNotEmpty()) { "無法載入 SFTP 主機金鑰" }
            }
            ssh.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ ->
                authenticate(user, password)
            }
            ssh.userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE)
            ssh.forwardingFilter = RejectAllForwardingFilter.INSTANCE
            ssh.subsystemFactories = listOf(SftpSubsystemFactory.Builder().withFileSystemAccessor(object : SftpFileSystemAccessor {
                override fun openFile(subsystem: SftpSubsystemProxy, handle: FileHandle, file: Path,
                    handleId: String, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>) =
                    Files.newByteChannel(file, options, *attrs)
            }).build())
            ssh.fileSystemFactory = object : FileSystemFactory {
                override fun getUserHomeDir(session: SessionContext): Path = SharedFileSystem(files).getPath("/")
                override fun createFileSystem(session: SessionContext): FileSystem = SharedFileSystem(files)
            }
            CoreModuleProperties.AUTH_TIMEOUT.set(ssh, Duration.ofMinutes(2))
            CoreModuleProperties.IDLE_TIMEOUT.set(ssh, Duration.ofMinutes(10))
            CoreModuleProperties.MAX_AUTH_REQUESTS.set(ssh, 3)
            CoreModuleProperties.MAX_CONCURRENT_SESSIONS.set(ssh, 4)
            CoreModuleProperties.MAX_CONCURRENT_CHANNELS.set(ssh, 4)
            CoreModuleProperties.NIO_WORKERS.set(ssh, 2)
            ssh.start()
            server = ssh
        } catch (failure: Throwable) {
            runCatching { ssh.stop(true) }
            throw failure
        }
    }

    override fun close() { server?.stop(true); server = null }
}
