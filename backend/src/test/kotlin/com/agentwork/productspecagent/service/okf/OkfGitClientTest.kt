package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.config.OkfGitProperties
import com.agentwork.productspecagent.domain.okf.OkfGitConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryBuilder
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.transport.RefSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OkfGitClientTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reads base and creates canonical branch only on approved push`() {
        val remote = seedBareRemote(tempDir.resolve("remote.git"))
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))
        val connection = OkfGitConnection(remote.toUri().toString(), "main")

        val initial = client.fetch("project-1", connection)

        assertEquals("main", initial.branch)
        assertEquals(mapOf("index.md" to INDEX), initial.files)
        bareRepository(remote).use { repository ->
            assertEquals(null, repository.exactRef("refs/heads/productspec/okf"))
        }

        val result = client.commitAndPush(
            projectId = "project-1",
            connection = connection,
            expectedRemoteCommit = initial.commit,
            files = mapOf(
                "index.md" to INDEX.replace("0.1", "0.2"),
                "features/login.md" to "# Login\n",
            ),
            message = "docs(okf): migrate features",
        )

        assertNotEquals(initial.commit, result.commit)
        assertEquals("productspec/okf", result.branch)
        bareRepository(remote).use { repository ->
            assertEquals(result.commit, repository.exactRef("refs/heads/productspec/okf")?.objectId?.name)
        }

        val canonical = client.fetch("project-1", connection)
        assertEquals(result.commit, canonical.commit)
        assertEquals("productspec/okf", canonical.branch)
        assertEquals(setOf("index.md", "features/login.md"), canonical.files.keys)
    }

    @Test
    fun `replaces only the managed bundle and can read an earlier commit`() {
        val remote = seedBareRemote(tempDir.resolve("remote.git"))
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))
        val connection = OkfGitConnection(remote.toUri().toString(), "main")
        val base = client.fetch("project-2", connection)

        val pushed = client.commitAndPush(
            "project-2",
            connection,
            base.commit,
            mapOf("features/new.md" to "# New\n"),
            "docs(okf): replace bundle",
        )

        assertEquals(mapOf("features/new.md" to "# New\n"), client.fetch("project-2", connection).files)
        assertEquals(mapOf("index.md" to INDEX), client.readAt("project-2", connection, base.commit).files)
        val checkout = tempDir.resolve("worktrees/project-2")
        assertTrue(checkout.resolve("README.md").exists())
        assertFalse(checkout.resolve("docs/okf/index.md").exists())
        assertEquals(pushed.commit, client.fetch("project-2", connection).commit)

        checkout.toFile().deleteRecursively()
        assertFalse(checkout.exists(), "closed JGit resources must not retain the isolated checkout")
    }

    @Test
    fun `rejects a stale expected remote head`() {
        val remote = seedBareRemote(tempDir.resolve("remote.git"))
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))
        val connection = OkfGitConnection(remote.toUri().toString(), "main")
        val base = client.fetch("project-3", connection)
        client.commitAndPush("project-3", connection, base.commit, mapOf("index.md" to INDEX), "first")

        assertThrows(OkfGitRemoteAdvancedException::class.java) {
            client.commitAndPush("project-3", connection, base.commit, mapOf("index.md" to INDEX), "stale")
        }
    }

    @Test
    fun `validates project ids and remote urls before opening a repository`() {
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))
        val remote = seedBareRemote(tempDir.resolve("remote.git"))

        assertThrows(IllegalArgumentException::class.java) {
            client.fetch("../escape", OkfGitConnection(remote.toUri().toString()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.fetch("project", OkfGitConnection("https://user:secret@example.test/repo.git"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.fetch("project", OkfGitConnection("https:user:secret@example.test/repo.git"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.fetch("project", OkfGitConnection("https://example.test/repo.git?token=secret"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.fetch("project", OkfGitConnection("ssh://example.test/repo.git"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OkfGitClient(properties(tempDir.resolve("other"), allowFileRemotes = false))
                .fetch("project", OkfGitConnection(remote.toUri().toString()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.fetch("project", OkfGitConnection(remote.toUri().toString(), canonicalBranch = "../escape"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.commitAndPush(
                "project",
                OkfGitConnection(remote.toUri().toString()),
                "unused",
                mapOf("../README.md" to "overwrite"),
                "invalid path",
            )
        }
    }

    @Test
    fun `rejects symlinks in the managed bundle`() {
        val remote = seedBareRemote(tempDir.resolve("remote.git"))
        addRawEntry(remote, "refs/heads/main", "docs/okf/link.md", FileMode.SYMLINK, "../../secret")
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))

        assertThrows(OkfGitInvalidBundleException::class.java) {
            client.fetch("project-4", OkfGitConnection(remote.toUri().toString()))
        }
    }

    @Test
    fun `rejects non UTF-8 blobs in the managed bundle`() {
        val remote = seedBareRemote(tempDir.resolve("remote.git"))
        addRawEntry(
            remote,
            "refs/heads/main",
            "docs/okf/binary.md",
            FileMode.REGULAR_FILE,
            byteArrayOf(0xC3.toByte(), 0x28),
        )
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))

        assertThrows(OkfGitInvalidBundleException::class.java) {
            client.fetch("project-5", OkfGitConnection(remote.toUri().toString()))
        }
    }

    @Test
    fun `accepts executable regular files in the managed bundle`() {
        val remote = seedBareRemote(tempDir.resolve("remote.git"))
        addRawEntry(remote, "refs/heads/main", "docs/okf/script.md", FileMode.EXECUTABLE_FILE, "# Script\n")
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))

        assertEquals(
            "# Script\n",
            client.fetch("project-6", OkfGitConnection(remote.toUri().toString())).files["script.md"],
        )
    }

    @Test
    fun `rejects oversized remote files before loading their content`() {
        val remote = seedBareRemote(tempDir.resolve("remote.git"))
        addRawEntry(
            remote,
            "refs/heads/main",
            "docs/okf/oversized.md",
            FileMode.REGULAR_FILE,
            ByteArray(4_000_001) { 'a'.code.toByte() },
        )
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))

        assertThrows(OkfGitInvalidBundleException::class.java) {
            client.fetch("project-7", OkfGitConnection(remote.toUri().toString()))
        }
    }

    @Test
    fun `serializes concurrent operations for the same project`() {
        val remote = seedBareRemote(tempDir.resolve("remote.git"))
        val client = OkfGitClient(properties(tempDir.resolve("worktrees")))
        val connection = OkfGitConnection(remote.toUri().toString())
        val base = client.fetch("project-8", connection)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val results = listOf("first.md", "second.md").map { path ->
            executor.submit<Result<OkfGitCommitResult>> {
                start.await()
                runCatching {
                    client.commitAndPush("project-8", connection, base.commit, mapOf(path to "# $path\n"), path)
                }
            }
        }
        start.countDown()
        val completed = results.map { it.get() }
        executor.shutdownNow()

        assertEquals(1, completed.count { it.isSuccess })
        assertEquals(1, completed.count { it.exceptionOrNull() is OkfGitRemoteAdvancedException })
        assertTrue(
            client.fetch("project-8", connection).files.keys in
                setOf(setOf("first.md"), setOf("second.md")),
        )
    }

    private fun properties(root: Path, allowFileRemotes: Boolean = true) = OkfGitProperties(
        worktreeRoot = root,
        allowFileRemotes = allowFileRemotes,
    )

    private fun seedBareRemote(remote: Path): Path {
        Git.init().setBare(true).setDirectory(remote.toFile()).call().close()
        val seed = tempDir.resolve("seed-${remote.fileName}").createDirectories()
        Git.init().setDirectory(seed.toFile()).call().use { git ->
            seed.resolve("docs/okf").createDirectories()
            seed.resolve("docs/okf/index.md").writeText(INDEX)
            seed.resolve("README.md").writeText("outside managed bundle\n")
            git.add().addFilepattern(".").call()
            git.commit()
                .setAuthor("OKF Test", "okf@example.test")
                .setCommitter("OKF Test", "okf@example.test")
                .setMessage("seed")
                .call()
            git.push()
                .setRemote(remote.toUri().toString())
                .setRefSpecs(RefSpec("HEAD:refs/heads/main"))
                .call()
        }
        return remote
    }

    private fun addRawEntry(remote: Path, branch: String, path: String, mode: FileMode, content: String) =
        addRawEntry(remote, branch, path, mode, content.toByteArray(StandardCharsets.UTF_8))

    private fun addRawEntry(remote: Path, branch: String, path: String, mode: FileMode, content: ByteArray) {
        bareRepository(remote).use { repository ->
            val parent = repository.parseCommit(branch)
            val entries = linkedMapOf<String, Pair<FileMode, ByteArray>>()
            repository.readTree(parent.tree).forEach { (entryPath, entryMode, bytes) ->
                entries[entryPath] = entryMode to bytes
            }
            entries[path] = mode to content
            val inserter = repository.newObjectInserter()
            inserter.use {
                val treeObjectId = writeTree(inserter, entries)
                val builder = org.eclipse.jgit.lib.CommitBuilder().apply {
                    setTreeId(treeObjectId)
                    setParentId(parent)
                    author = PersonIdent("OKF Test", "okf@example.test")
                    committer = author
                    message = "add raw entry"
                }
                val commitId = inserter.insert(builder)
                inserter.flush()
                repository.updateRef(branch).apply {
                    setNewObjectId(commitId)
                    setExpectedOldObjectId(parent)
                }.update()
            }
        }
    }

    private fun writeTree(
        inserter: org.eclipse.jgit.lib.ObjectInserter,
        entries: Map<String, Pair<FileMode, ByteArray>>,
    ): org.eclipse.jgit.lib.ObjectId {
        data class Node(val files: MutableMap<String, Pair<FileMode, ByteArray>> = linkedMapOf(), val dirs: MutableMap<String, Node> = linkedMapOf())
        val root = Node()
        entries.forEach { (path, value) ->
            val segments = path.split('/')
            var node = root
            segments.dropLast(1).forEach { segment -> node = node.dirs.getOrPut(segment) { Node() } }
            node.files[segments.last()] = value
        }
        fun insert(node: Node): org.eclipse.jgit.lib.ObjectId {
            val formatter = TreeFormatter()
            node.dirs.toSortedMap().forEach { (name, child) ->
                formatter.append(name, FileMode.TREE, insert(child))
            }
            node.files.toSortedMap().forEach { (name, value) ->
                formatter.append(name, value.first, inserter.insert(Constants.OBJ_BLOB, value.second))
            }
            return inserter.insert(formatter)
        }
        return insert(root)
    }

    private fun bareRepository(path: Path): Repository = RepositoryBuilder()
        .setGitDir(path.toFile())
        .setBare()
        .build()

    private fun Repository.parseCommit(ref: String) = org.eclipse.jgit.revwalk.RevWalk(this).use { walk ->
        walk.parseCommit(resolve(ref))
    }

    private fun Repository.readTree(tree: org.eclipse.jgit.revwalk.RevTree): List<Triple<String, FileMode, ByteArray>> =
        org.eclipse.jgit.treewalk.TreeWalk(this).use { walk ->
            walk.addTree(tree)
            walk.isRecursive = true
            buildList {
                while (walk.next()) {
                    add(Triple(walk.pathString, walk.getFileMode(0), open(walk.getObjectId(0)).bytes))
                }
            }
        }

    private companion object {
        const val INDEX = "---\nokf_version: \"0.1\"\nprofile: \"productspecagent/1\"\n---\n"
    }
}
