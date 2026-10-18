package com.changyow.mediadler.core.sharing

import org.apache.sshd.common.file.util.BaseFileSystem
import org.apache.sshd.common.file.util.BasePath
import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider

/** Small read-only NIO adapter. Every operation stays in SharedFiles, including stat/realpath. */
internal class SharedFileSystem(val files: SharedFiles) : BaseFileSystem<SharedPath>(SharedFileProvider(files)) {
    private var opened = true
    override fun create(root: String?, names: MutableList<String>): SharedPath = SharedPath(this, root, names)
    override fun isReadOnly() = true
    override fun isOpen() = opened
    override fun close() { opened = false }
    override fun supportedFileAttributeViews() = setOf("basic", "posix", "unix")
    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = object : UserPrincipalLookupService() {
        override fun lookupPrincipalByName(name: String) = UserPrincipal { name }
        override fun lookupPrincipalByGroupName(group: String) = GroupPrincipal { group }
    }
}

internal class SharedPath(fs: SharedFileSystem, root: String?, names: List<String>) :
    BasePath<SharedPath, SharedFileSystem>(fs, root, names) {
    override fun toUri(): URI = URI("media-dler", null, toAbsolutePath().toString(), null)
    override fun toRealPath(vararg options: LinkOption): Path {
        val normalized = fileSystem.getPath(SharePath.normalize(toString()))
        fileSystem.provider().checkAccess(normalized)
        return normalized
    }
}

private class SharedFileProvider(private val files: SharedFiles) : FileSystemProvider() {
    override fun getScheme() = "media-dler"
    override fun newFileSystem(uri: URI, env: MutableMap<String, *>): FileSystem = throw UnsupportedOperationException()
    override fun getFileSystem(uri: URI): FileSystem = throw UnsupportedOperationException()
    override fun getPath(uri: URI): Path = throw UnsupportedOperationException()
    private fun path(path: Path): String {
        if (path !is SharedPath || path.fileSystem.provider() !== this) throw ProviderMismatchException()
        if (!path.fileSystem.isOpen) throw ClosedFileSystemException()
        return SharePath.normalize(path.toString())
    }
    override fun newByteChannel(path: Path, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>): SeekableByteChannel {
        if (options.any { it != StandardOpenOption.READ }) throw AccessDeniedException(path.toString())
        return files.open(path(path))
    }
    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val children = files.list(path(dir)).map { dir.fileSystem.getPath(it.path) }.filter { filter.accept(it) }
        return object : DirectoryStream<Path> {
            private var closed = false
            private var iterated = false
            override fun iterator(): MutableIterator<Path> {
                check(!closed && !iterated)
                iterated = true
                return children.toMutableList().iterator()
            }
            override fun close() { closed = true }
        }
    }
    override fun checkAccess(path: Path, vararg modes: AccessMode) {
        val entry = files.stat(path(path))
        if (modes.any { it == AccessMode.WRITE || it == AccessMode.EXECUTE && !entry.directory })
            throw AccessDeniedException(path.toString())
    }
    override fun isSameFile(a: Path, b: Path) = path(a) == path(b)
    override fun isHidden(path: Path) = files.stat(path(path)).name.startsWith('.')
    override fun getFileStore(path: Path): FileStore = throw UnsupportedOperationException()
    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) { throw AccessDeniedException(dir.toString()) }
    override fun delete(path: Path) { throw AccessDeniedException(path.toString()) }
    override fun copy(source: Path, target: Path, vararg options: CopyOption) { throw AccessDeniedException(target.toString()) }
    override fun move(source: Path, target: Path, vararg options: CopyOption) { throw AccessDeniedException(target.toString()) }
    override fun setAttribute(path: Path, attribute: String, value: Any, vararg options: LinkOption) { throw AccessDeniedException(path.toString()) }
    override fun <V : FileAttributeView> getFileAttributeView(path: Path, type: Class<V>, vararg options: LinkOption): V? {
        if (type != BasicFileAttributeView::class.java && type != PosixFileAttributeView::class.java) return null
        val view = object : PosixFileAttributeView {
            override fun name() = "posix"
            override fun readAttributes() = attributes(path)
            override fun getOwner() = readAttributes().owner()
            override fun setTimes(a: FileTime?, m: FileTime?, c: FileTime?) { throw AccessDeniedException(path.toString()) }
            override fun setOwner(owner: UserPrincipal) { throw AccessDeniedException(path.toString()) }
            override fun setGroup(group: GroupPrincipal) { throw AccessDeniedException(path.toString()) }
            override fun setPermissions(permissions: MutableSet<PosixFilePermission>) { throw AccessDeniedException(path.toString()) }
        }
        return type.cast(view)
    }
    override fun <A : BasicFileAttributes> readAttributes(path: Path, type: Class<A>, vararg options: LinkOption): A {
        if (type != BasicFileAttributes::class.java && type != PosixFileAttributes::class.java) throw UnsupportedOperationException()
        return type.cast(attributes(path))
    }
    override fun readAttributes(path: Path, attributes: String, vararg options: LinkOption): MutableMap<String, Any> {
        val a = attributes(path)
        val all = linkedMapOf<String, Any>(
            "size" to a.size(), "isDirectory" to a.isDirectory, "isRegularFile" to a.isRegularFile,
            "isSymbolicLink" to false, "isOther" to false, "lastModifiedTime" to a.lastModifiedTime(),
            "lastAccessTime" to a.lastAccessTime(), "creationTime" to a.creationTime(), "fileKey" to a.fileKey(),
            "permissions" to a.permissions(), "owner" to a.owner(), "group" to a.group(),
            "uid" to 0, "gid" to 0, "nlink" to 1, "mode" to if (a.isDirectory) 0x416D else 0x8124,
        )
        val requested = attributes.substringAfter(':').split(',')
        return if ("*" in requested) all else all.filterKeys { it in requested }.toMutableMap()
    }
    private fun attributes(path: Path): PosixFileAttributes {
        val e = files.stat(path(path))
        return object : PosixFileAttributes {
            override fun size() = e.size
            override fun isDirectory() = e.directory
            override fun isRegularFile() = !e.directory
            override fun isSymbolicLink() = false
            override fun isOther() = false
            override fun fileKey(): Any = e.path
            override fun lastModifiedTime(): FileTime = FileTime.fromMillis(e.modifiedMillis)
            override fun lastAccessTime() = lastModifiedTime()
            override fun creationTime() = lastModifiedTime()
            override fun owner() = UserPrincipal { "media" }
            override fun group() = GroupPrincipal { "media" }
            override fun permissions(): MutableSet<PosixFilePermission> = PosixFilePermissions.fromString(if (e.directory) "r-xr-xr-x" else "r--r--r--")
        }
    }
}
