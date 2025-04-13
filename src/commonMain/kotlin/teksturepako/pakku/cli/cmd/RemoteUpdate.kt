package teksturepako.pakku.cli.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.animation.coroutines.animateInCoroutine
import com.github.ajalt.mordant.animation.progress.MultiProgressBarAnimation
import com.github.ajalt.mordant.animation.progress.ProgressTask
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.text
import com.github.michaelbull.result.getOrElse
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.*
import teksturepako.pakku.api.actions.errors.AlreadyExists
import teksturepako.pakku.api.actions.fetch.DeletionActionType
import teksturepako.pakku.api.actions.fetch.deleteOldFiles
import teksturepako.pakku.api.actions.fetch.fetch
import teksturepako.pakku.api.actions.fetch.retrieveProjectFiles
import teksturepako.pakku.api.actions.remote.canInstallRemote
import teksturepako.pakku.api.actions.remote.remoteUpdate
import teksturepako.pakku.api.actions.sync.sync
import teksturepako.pakku.api.data.ConfigFile
import teksturepako.pakku.api.data.Dirs
import teksturepako.pakku.api.data.LockFile
import teksturepako.pakku.api.overrides.OverrideType
import teksturepako.pakku.api.overrides.readManualOverridesFrom
import teksturepako.pakku.api.platforms.Platform
import teksturepako.pakku.api.platforms.Provider
import teksturepako.pakku.cli.ui.*
import kotlin.io.path.Path
import kotlin.io.path.pathString
import kotlin.time.Duration.Companion.seconds

class RemoteUpdate : CliktCommand("update")
{
    override fun help(context: Context) = "Update the modpack from its remote"

    private val argObjects by requireObject<MutableList<Remote.Args>>()

    override fun run() = runBlocking {
        coroutineScope {
            val args = argObjects.first()

            if (canInstallRemote() && args.urlArg != null) remoteInstallImpl(args) else remoteUpdateImpl(args)
        }
    }
}

suspend fun CliktCommand.remoteUpdateImpl(args: Remote.Args) = coroutineScope {
    terminal.cursor.hide()

    terminal.pMsg("Updating remote")

    val gitProgressLayout = progressBarContextLayout(spacing = 2) {
        text(align = TextAlign.LEFT) {
            prefixed(context, prefix = terminal.theme.string("pakku.prefix", ">>>"))
        }
        percentage()
        progressBar()
    }

    val gitProgress = MultiProgressBarAnimation(terminal).animateInCoroutine()
    val tasks = atomic(mutableMapOf<String, ProgressTask<String>>())

    val remoteJob = async {
        remoteUpdate(
            onProgress = { taskName, percentDone ->
                val id = taskName?.lowercase()?.filterNot { it.isWhitespace() }
                if (id != null)
                {
                    // Start progress animation when first task is added
                    if (tasks.value.isEmpty())
                    {
                        launch { gitProgress.execute() }
                    }

                    // Atomically update tasks map
                    tasks.update { currentTasks ->
                        if (id !in currentTasks)
                        {
                            currentTasks[id] = gitProgress.addTask(gitProgressLayout, taskName, total = 100)
                            currentTasks
                        }
                        else
                        {
                            currentTasks
                        }
                    }

                    // Update the task progress
                    tasks.value[id]?.let { task ->
                        runBlocking {
                            task.update {
                                this.completed = percentDone.toLong()
                            }
                        }
                    }
                }
            },
            onSync = {
                terminal.pSuccess(it.description)
            },
            args.branchOpt,
            if (args.serverPackFlag) setOf(OverrideType.OVERRIDE, OverrideType.SERVER_OVERRIDE) else null
        )
    }

    remoteJob.await()?.onError {
        terminal.pError(it)
        echo()
        return@coroutineScope
    }

    remoteJob.join()

    launch {
        delay(1.seconds)
        runBlocking {
            gitProgress.stop()
        }
    }.join()

    terminal.cursor.show()

    val lockFile = LockFile.readToResultFrom(Path(Dirs.remoteDir.pathString, LockFile.FILE_NAME))
        .getOrElse {
            terminal.pError(it)
            echo()
            return@coroutineScope
        }

    val platforms: List<Platform> = lockFile.getPlatforms().getOrElse {
        terminal.pError(it)
        echo()
        return@coroutineScope
    }

    val configFile = if (ConfigFile.existsAt(Path(Dirs.remoteDir.pathString, ConfigFile.FILE_NAME)))
    {
        ConfigFile.readToResultFrom(Path(Dirs.remoteDir.pathString, ConfigFile.FILE_NAME))
            .getOrElse {
                terminal.pError(it)
                echo()
                return@coroutineScope
            }
    }
    else null

    val projectFiles = retrieveProjectFiles(
        lockFile, Provider.providers,
        if (args.serverPackFlag) setOf(OverrideType.OVERRIDE, OverrideType.SERVER_OVERRIDE) else null
    ).mapNotNull { result ->
        result.getOrElse {
            terminal.pError(it)
            null
        }
    }

    val progressBar = progressBarContextLayout(spacing = 2) {
        text {
            prefixed(context, prefix = terminal.theme.string("pakku.prefix", ">>>"))
        }
        percentage()
        progressBar()
    }.animateInCoroutine(terminal, "Fetching")

    launch { progressBar.execute() }

    val fetchJob = projectFiles.fetch(
        onError = { error ->
            if (error !is AlreadyExists) terminal.pError(error)
        },
        onProgress = { completed, total ->
            progressBar.update {
                this.completed = completed
                this.total = total
            }
        },
        onSuccess = { path, projectFile ->
            val slug = projectFile.getParentProject(lockFile)?.getFullMsg()

            terminal.pSuccess("$slug saved to $path")
        },
        lockFile, configFile, args.retryOpt
    )

    // -- OVERRIDES --

    val projectOverrides = readManualOverridesFrom(
        Dirs.remoteDir, configFile,
        if (args.serverPackFlag) setOf(OverrideType.OVERRIDE, OverrideType.SERVER_OVERRIDE) else null
    )

    val syncJob = launch {
        projectOverrides.sync(
            onError = { error ->
                if (error !is AlreadyExists) terminal.pError(error)
            },
            onSuccess = { projectOverride ->
                terminal.pInfo("${projectOverride.fullOutputPath} synced")
            },
            syncPrimaryDirectories = true
        )
    }

    // -- OLD FILES --

    val oldFilesJob = launch {
        deleteOldFiles(
            onError = { error ->
                terminal.pError(error)
            },
            onSuccess = { file, actionType ->
                when (actionType)
                {
                    DeletionActionType.DELETE -> terminal.pDanger("$file ${actionType.result}")
                    DeletionActionType.SHELF  ->
                    {
                        val shelvedPath = Path(Dirs.shelfDir.pathString, file.fileName.pathString)
                        terminal.pInfo("$file ${actionType.result} to $shelvedPath")
                    }
                }
            },
            projectFiles, projectOverrides, lockFile, configFile, platforms,
        )
    }

    fetchJob.join()

    launch {
        delay(3.seconds)
        progressBar.update {
            if (this.total != null)
            {
                this.completed = this.total!!
            }
        }
        runBlocking {
            progressBar.stop()
        }
    }.join()

    syncJob.join()
    oldFilesJob.join()

    echo()
}

