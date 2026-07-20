package com.agentwork.productspecagent.service.okf

import com.agentwork.productspecagent.config.OkfGitProperties
import com.agentwork.productspecagent.domain.okf.OkfGitConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import org.springframework.stereotype.Service

data class OkfGitSnapshot(
    val commit: String,
    val branch: String,
    val files: Map<String, String>,
)

data class OkfGitCommitResult(val commit: String, val branch: String)

open class OkfGitException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OkfGitRemoteAdvancedException(message: String) : OkfGitException(message)

class OkfGitAuthenticationException(message: String, cause: Throwable? = null) : OkfGitException(message, cause)

class OkfGitPushRejectedException(message: String) : OkfGitException(message)

class OkfGitInvalidBundleException(message: String, cause: Throwable? = null) : OkfGitException(message, cause)

@Service
class OkfGitClient(private val properties: OkfGitProperties) {

    private val projectLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun fetch(projectId: String, connection: OkfGitConnection): OkfGitSnapshot =
        withRepository(projectId, connection) { git ->
            fetchRemote(git)
            val (commit, branch) = remoteHead(git.repository, connection)
            snapshot(git.repository, commit, branch)
        }

    fun readAt(
        projectId: String,
        connection: OkfGitConnection,
        commit: String,
    ): OkfGitSnapshot = withRepository(projectId, connection) { git ->
        fetchRemote(git)
        val objectId = git.repository.resolve("${ObjectId.fromString(commit).name}^{commit}")
            ?: throw OkfGitException("OKF commit does not exist: $commit")
        val branch = branchForCommit(git.repository, connection, objectId)
        snapshot(git.repository, objectId, branch)
    }

    fun commitAndPush(
        projectId: String,
        connection: OkfGitConnection,
        expectedRemoteCommit: String,
        files: Map<String, String>,
        message: String,
    ): OkfGitCommitResult {
        val validatedFiles = files.mapKeys { (path, _) -> validateBundlePath(path) }
        require(message.isNotBlank()) { "commit message must not be blank" }

        return withRepository(projectId, connection) { git ->
            fetchRemote(git)
            val (remoteCommit, _) = remoteHead(git.repository, connection)
            if (remoteCommit.name != expectedRemoteCommit) {
                throw OkfGitRemoteAdvancedException(
                    "remote OKF branch advanced from $expectedRemoteCommit to ${remoteCommit.name}",
                )
            }

            checkoutCanonicalBranch(git, connection, remoteCommit)
            replaceManagedBundle(git.repository.workTree.toPath(), validatedFiles)
            git.add().addFilepattern(MANAGED_ROOT).setAll(true).call()
            val identity = PersonIdent("ProductSpecAgent", "noreply@productspecagent.local")
            val commit = git.commit()
                .setAuthor(identity)
                .setCommitter(identity)
                .setMessage(message)
                .call()

            pushCanonicalBranch(git, connection)
            OkfGitCommitResult(commit.name, connection.canonicalBranch)
        }
    }

    private fun <T> withRepository(
        projectId: String,
        connection: OkfGitConnection,
        block: (Git) -> T,
    ): T {
        val checkout = checkoutPath(projectId)
        validateConnection(connection)
        return projectLocks.computeIfAbsent(projectId) { ReentrantLock() }.withLock {
            Files.createDirectories(properties.worktreeRoot.toAbsolutePath().normalize())
            if (!Files.exists(checkout.resolve(".git"))) {
                clone(checkout, connection).close()
            }

            try {
                Git.open(checkout.toFile()).use { git ->
                    configureRemote(git.repository, connection.remoteUrl)
                    block(git)
                }
            } catch (exception: TransportException) {
                throw translateTransportException(exception)
            }
        }
    }

    private fun clone(checkout: Path, connection: OkfGitConnection): Git = try {
        Git.cloneRepository()
            .setURI(connection.remoteUrl)
            .setDirectory(checkout.toFile())
            .setBranch(connection.baseBranch)
            .setCredentialsProvider(credentialsProvider())
            .call()
    } catch (exception: TransportException) {
        throw translateTransportException(exception)
    }

    private fun configureRemote(repository: Repository, remoteUrl: String) {
        val config = repository.config
        if (config.getString("remote", "origin", "url") != remoteUrl) {
            config.setString("remote", "origin", "url", remoteUrl)
            config.save()
        }
    }

    private fun fetchRemote(git: Git) {
        git.fetch()
            .setRemote("origin")
            .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
            .setRemoveDeletedRefs(true)
            .setCredentialsProvider(credentialsProvider())
            .call()
    }

    private fun remoteHead(repository: Repository, connection: OkfGitConnection): Pair<ObjectId, String> {
        val canonical = repository.exactRef(remoteRef(connection.canonicalBranch))
        if (canonical != null) return canonical.objectId to connection.canonicalBranch
        val base = repository.exactRef(remoteRef(connection.baseBranch))
            ?: throw OkfGitException("remote base branch does not exist: ${connection.baseBranch}")
        return base.objectId to connection.baseBranch
    }

    private fun branchForCommit(
        repository: Repository,
        connection: OkfGitConnection,
        commit: ObjectId,
    ): String = when {
        repository.exactRef(remoteRef(connection.canonicalBranch))?.objectId == commit -> connection.canonicalBranch
        repository.exactRef(remoteRef(connection.baseBranch))?.objectId == commit -> connection.baseBranch
        else -> connection.canonicalBranch
    }

    private fun snapshot(repository: Repository, commitId: ObjectId, branch: String): OkfGitSnapshot {
        val commit = RevWalk(repository).use { walk -> walk.parseCommit(commitId) }
        return OkfGitSnapshot(commit.name, branch, readManagedFiles(repository, commit))
    }

    private fun readManagedFiles(repository: Repository, commit: RevCommit): Map<String, String> =
        TreeWalk(repository).use { walk ->
            walk.addTree(commit.tree)
            walk.isRecursive = true
            var totalBytes = 0L
            var fileCount = 0
            buildMap {
                while (walk.next()) {
                    val path = walk.pathString
                    if (path == MANAGED_ROOT) {
                        throw OkfGitInvalidBundleException("OKF root is not a directory: $path")
                    }
                    if (!path.startsWith(MANAGED_PREFIX)) continue
                    val relativePath = validateBundlePath(path.removePrefix(MANAGED_PREFIX))
                    if (walk.getFileMode(0) !in REGULAR_FILE_MODES) {
                        throw OkfGitInvalidBundleException("OKF path is not a regular file: $path")
                    }
                    fileCount++
                    if (fileCount > MAX_FILE_COUNT) {
                        throw OkfGitInvalidBundleException("OKF bundle exceeds $MAX_FILE_COUNT files")
                    }
                    val loader = repository.open(walk.getObjectId(0), Constants.OBJ_BLOB)
                    if (loader.size > MAX_FILE_BYTES) {
                        throw OkfGitInvalidBundleException("OKF file exceeds $MAX_FILE_BYTES bytes: $path")
                    }
                    totalBytes += loader.size
                    if (totalBytes > MAX_BUNDLE_BYTES) {
                        throw OkfGitInvalidBundleException("OKF bundle exceeds $MAX_BUNDLE_BYTES bytes")
                    }
                    val bytes = loader.bytes
                    put(relativePath, decodeUtf8(path, bytes))
                }
            }
        }

    private fun decodeUtf8(path: String, bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (exception: Exception) {
        throw OkfGitInvalidBundleException("OKF file is not valid UTF-8: $path", exception)
    }

    private fun checkoutCanonicalBranch(git: Git, connection: OkfGitConnection, start: ObjectId) {
        val branchRef = "refs/heads/${connection.canonicalBranch}"
        if (git.repository.exactRef(branchRef) == null) {
            git.checkout()
                .setCreateBranch(true)
                .setName(connection.canonicalBranch)
                .setStartPoint(start.name)
                .call()
        } else {
            git.checkout().setName(connection.canonicalBranch).call()
        }
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(start.name).call()
    }

    private fun replaceManagedBundle(worktree: Path, files: Map<String, String>) {
        val root = worktree.resolve(MANAGED_ROOT).normalize()
        require(root.startsWith(worktree.normalize())) { "invalid managed worktree root" }
        if (Files.exists(root)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        files.forEach { (relativePath, content) ->
            val target = root.resolve(relativePath).normalize()
            require(target.startsWith(root)) { "OKF path escapes managed root: $relativePath" }
            target.parent?.let(Files::createDirectories)
            Files.writeString(target, content, StandardCharsets.UTF_8)
        }
    }

    private fun pushCanonicalBranch(git: Git, connection: OkfGitConnection) {
        val updates = git.push()
            .setRemote("origin")
            .setRefSpecs(RefSpec("HEAD:refs/heads/${connection.canonicalBranch}"))
            .setCredentialsProvider(credentialsProvider())
            .call()
            .flatMap { it.remoteUpdates }
        if (updates.isEmpty()) {
            throw OkfGitPushRejectedException("OKF push returned no remote update")
        }
        val rejected = updates.firstOrNull { it.status !in SUCCESSFUL_PUSH_STATUSES } ?: return
        if (rejected.status in REMOTE_ADVANCED_STATUSES) {
            throw OkfGitRemoteAdvancedException("remote OKF branch advanced during push")
        }
        throw OkfGitPushRejectedException(
            "OKF push rejected for ${rejected.remoteName}: ${rejected.status} ${rejected.message.orEmpty()}".trim(),
        )
    }

    private fun checkoutPath(projectId: String): Path {
        require(PROJECT_ID.matches(projectId)) { "invalid project id" }
        val root = properties.worktreeRoot.toAbsolutePath().normalize()
        val checkout = root.resolve(projectId).normalize()
        require(checkout.startsWith(root)) { "project worktree escapes configured root" }
        return checkout
    }

    private fun validateConnection(connection: OkfGitConnection) {
        val uri = try {
            URI(connection.remoteUrl)
        } catch (exception: Exception) {
            throw IllegalArgumentException("invalid Git remote URL", exception)
        }
        require(uri.rawUserInfo == null) { "Git remote URL must not contain credentials" }
        require(!uri.isOpaque && uri.rawQuery == null && uri.rawFragment == null) {
            "Git remote URL must not contain opaque, query, or fragment components"
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "file" && properties.allowFileRemotes) {
            "Git remote URL must use HTTPS"
        }
        if (scheme == "https") {
            require(!uri.host.isNullOrBlank()) { "HTTPS Git remote URL must contain a host" }
        }
        require(Repository.isValidRefName("refs/heads/${connection.baseBranch}")) { "invalid base branch" }
        require(Repository.isValidRefName("refs/heads/${connection.canonicalBranch}")) { "invalid canonical branch" }
    }

    private fun validateBundlePath(path: String): String {
        require(path.isNotBlank()) { "OKF path must not be blank" }
        require(!path.contains('\\')) { "OKF path must use forward slashes: $path" }
        val parsed = Path.of(path)
        require(!parsed.isAbsolute) { "OKF path must be relative: $path" }
        require(parsed.none { it.toString() == ".." }) { "OKF path must not contain parent traversal: $path" }
        val normalized = parsed.normalize().toString().replace('\\', '/')
        require(normalized != "." && normalized == path) { "OKF path must be normalized: $path" }
        return normalized
    }

    private fun credentialsProvider() =
        UsernamePasswordCredentialsProvider(properties.username, properties.token)

    private fun translateTransportException(exception: TransportException): OkfGitException {
        val message = exception.message.orEmpty()
        return if (AUTH_FAILURE.containsMatchIn(message)) {
            OkfGitAuthenticationException("Git authentication failed", exception)
        } else {
            OkfGitException("Git transport failed: $message", exception)
        }
    }

    private fun remoteRef(branch: String) = "refs/remotes/origin/$branch"

    private companion object {
        const val MANAGED_ROOT = "docs/okf"
        const val MANAGED_PREFIX = "$MANAGED_ROOT/"
        val PROJECT_ID = Regex("[A-Za-z0-9_-]+")
        val AUTH_FAILURE = Regex(
            "auth|unauthorized|forbidden|401|403|not authorized|access denied|credentials",
            RegexOption.IGNORE_CASE,
        )
        val REGULAR_FILE_MODES = setOf(FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE)
        const val MAX_FILE_COUNT = 2_000
        const val MAX_FILE_BYTES = 4_000_000L
        const val MAX_BUNDLE_BYTES = 32_000_000L
        val SUCCESSFUL_PUSH_STATUSES = setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE)
        val REMOTE_ADVANCED_STATUSES = setOf(
            RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD,
            RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED,
        )
    }
}
