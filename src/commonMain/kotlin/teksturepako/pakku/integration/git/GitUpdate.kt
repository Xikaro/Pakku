package teksturepako.pakku.integration.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.FetchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ProgressMonitor
import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.debug
import java.nio.file.Path

data class GitUpdateError(val dir: Path, val reason: String? = null): ActionError()
{
    override val rawMessage = message("Failed to fetch a repository, $reason")
}

suspend fun gitUpdate(
    dir: Path,
    branch: String? = null,
    onProgress: (taskName: String?, percentDone: Int) -> Unit,
): ActionError? = coroutineScope {

    val (progressMonitor, outputStream, writer) = pakkuGitProgressMonitor { taskName, percentDone ->
        onProgress(taskName, percentDone)
    }

    val git = try
    {
        val git = Git.open(dir.toFile())

        if (branch != null)
        {
            git.checkout()
                .setProgressMonitorIfPossible(progressMonitor)
                .setName(branch)
                .call()
        }

        git.clean()
            .setForce(true)
            .call()

        git.fetch()
            .setProgressMonitorIfPossible(progressMonitor)
            .call()

        git.reset()
            .setProgressMonitorIfPossible(progressMonitor)
            .setMode(ResetCommand.ResetType.HARD)
            .setRef(git.repository.getRemoteName(git.repository.branch))
            .call()

        git
    }
    catch (e: Exception)
    {
        debug { e.printStackTrace() }
        return@coroutineScope GitUpdateError(dir)
    }
    finally
    {
        withContext(Dispatchers.IO) {
            writer.close()
            outputStream.close()
        }
    }

    launch { git.close() }.join()

    return@coroutineScope null
}

private fun CheckoutCommand.setProgressMonitorIfPossible(progressMonitor: ProgressMonitor?): CheckoutCommand =
    if (progressMonitor == null) this else this.setProgressMonitor(progressMonitor)

private fun FetchCommand.setProgressMonitorIfPossible(progressMonitor: ProgressMonitor?): FetchCommand =
    if (progressMonitor == null) this else this.setProgressMonitor(progressMonitor)

private fun ResetCommand.setProgressMonitorIfPossible(progressMonitor: ProgressMonitor?): ResetCommand =
    if (progressMonitor == null) this else this.setProgressMonitor(progressMonitor)
