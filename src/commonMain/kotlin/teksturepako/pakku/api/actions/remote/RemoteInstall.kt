package teksturepako.pakku.api.actions.remote

import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.api.actions.errors.FileNotFound
import teksturepako.pakku.api.data.Dirs
import teksturepako.pakku.api.data.LockFile
import teksturepako.pakku.api.data.workingPath
import teksturepako.pakku.api.overrides.OverrideType
import teksturepako.pakku.cli.ui.hint
import teksturepako.pakku.debug
import teksturepako.pakku.integration.git.gitClone
import teksturepako.pakku.io.FileAction
import teksturepako.pakku.io.tryOrNull
import kotlin.io.path.*

suspend fun canInstallRemote(): Boolean = !(!modpackDirIsEmpty() || LockFile.exists() || Dirs.remoteDir.exists())

suspend fun remoteInstall(
    onProgress: (taskName: String?, percentDone: Int) -> Unit,
    onSync: suspend (FileAction) -> Unit,
    remoteUrl: String,
    branch: String? = null,
    allowedTypes: Set<OverrideType>?,
): ActionError?
{
    if (Dirs.remoteDir.exists()) return RemoteAlreadyExists(remoteUrl)
    if (!modpackDirIsEmpty() || LockFile.exists()) return CouldNotInstallRemote(remoteUrl)

    debug { println("remoteInstall") }

    return when
    {
        remoteUrl.endsWith(".git") || remoteUrl.contains("github.com") ->
        {
            handleGit(remoteUrl, branch, allowedTypes, onProgress, onSync)
        }
        else -> InvalidUrl(remoteUrl)
    }
}

data class RemoteAlreadyExists(val url: String): ActionError()
{
    override val rawMessage = message(
        "Could not install remote: '$url'.",
        "A remote for this modpack already exists.",
        hint("use \"pakku remote --rm\" to remove the remote from your modpack"),
        hint("use \"pakku remote update\" to update the remote"),
        newlines = true,
    )
}

data class InvalidUrl(val url: String): ActionError()
{
    override val rawMessage = "Invalid URL: '$url'"
}

private suspend fun handleGit(
    uri: String,
    branch: String?,
    allowedTypes: Set<OverrideType>?,
    onProgress: (taskName: String?, percentDone: Int) -> Unit,
    onSync: suspend (FileAction) -> Unit,
): ActionError?
{
    debug { println("starting git & cloning the repo") }

    gitClone(uri, Dirs.remoteDir, branch) { taskName, percentDone -> onProgress(taskName, percentDone) }
        ?.onError {
            remoteRemove()
            return it
        }

    if (!LockFile.existsAt(Path(Dirs.remoteDir.pathString, LockFile.FILE_NAME)))
    {
        remoteRemove()
        return FileNotFound(Path(Dirs.remoteDir.pathString, LockFile.FILE_NAME).pathString)
    }

    syncRemoteDirectory(onSync, allowedTypes)
        ?.onError { return it }

    return null
}

private suspend fun modpackDirIsEmpty(): Boolean = Path(workingPath)
    .tryOrNull {
        if (!exists()) return@tryOrNull true // Path doesn't exist, consider it empty

        if (!isDirectory()) return@tryOrNull false // Not a directory

        // If no files found, directory is empty
        return@tryOrNull listDirectoryEntries()
            .filterNot { it.name == "pakku.jar" }
            .none { it.isRegularFile() || it.isDirectory() && it.listDirectoryEntries().isNotEmpty() }
    }
    ?: false
