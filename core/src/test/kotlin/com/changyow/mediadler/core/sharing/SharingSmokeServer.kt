package com.changyow.mediadler.core.sharing

import java.io.File
import java.net.InetAddress
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.util.UUID

/** Host-only real protocol fixture. Android storage is exercised by connected instrumentation. */
fun main(args: Array<String>) {
    val root = File(args.single()).canonicalFile
    val files = object : SharedFiles {
        fun file(path: String): File {
            val f = File(root, SharePath.normalize(path).removePrefix("/")).canonicalFile
            if (!f.toPath().startsWith(root.toPath())) throw AccessDeniedException(path)
            if (!f.exists() || path.split('/').any { it.isNotEmpty() && !SharePath.isPublished(it) }) throw NoSuchFileException(path)
            return f
        }
        override fun stat(path: String): SharedEntry {
            val p = SharePath.normalize(path)
            val f = file(p)
            return SharedEntry(p, if (p == "/") "media-dler" else f.name, f.isDirectory,
                if (f.isFile) f.length() else 0, f.lastModified(),
                if (f.extension == "mp4") "video/mp4" else "application/octet-stream")
        }
        override fun list(path: String): List<SharedEntry> = file(path).listFiles()?.filter { SharePath.isPublished(it.name) }
            ?.map { stat(SharePath.child(path, it.name)) } ?: throw NotDirectoryException(path)
        override fun open(path: String): SeekableByteChannel = Files.newByteChannel(file(path).toPath(), StandardOpenOption.READ)
    }
    val address = InetAddress.getByName("127.0.0.1")
    val ssh = SftpServer(files, address, 22222, File(root.parentFile, "smoke-host-key")) { u, p -> u == "media" && p == "smoke-test-password" }
    val upnp = UpnpServer(files, address, UUID.fromString("d0930f35-a0c2-4b43-9874-5bd6b189749a"))
    try {
        ssh.start()
        upnp.start()
        println("SHARING_READY ${upnp.httpPort} 22222")
        System.out.flush()
        while (true) {
            when (readlnOrNull()) { null, "stop" -> break; "changed" -> upnp.contentChanged() }
        }
    } finally { upnp.close(); ssh.close() }
}
