package net.corda.nodeapi.internal

import net.corda.cordform.CordformNode
import net.corda.core.internal.ThreadBox
import net.corda.core.internal.createDirectories
import net.corda.core.internal.isRegularFile
import net.corda.core.internal.list
import net.corda.core.utilities.contextLogger
import rx.Observable
import rx.Scheduler
import rx.Subscription
import rx.schedulers.Schedulers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * Utility class which copies nodeInfo files across a set of running nodes.
 *
 * This class will create paths that it needs to poll and to where it needs to copy files in case those
 * don't exist yet.
 */
class NodeInfoFilesCopier(scheduler: Scheduler = Schedulers.io()) : AutoCloseable {

    companion object {
        private val log = contextLogger()
        const val NODE_INFO_FILE_NAME_PREFIX = "nodeInfo-"
    }

    private val nodeDataMapBox = ThreadBox(mutableMapOf<Path, NodeData>())
    /**
     * Whether the NodeInfoFilesCopier is closed. When the NodeInfoFilesCopier is closed it will stop polling the
     * filesystem and all the public methods except [#close] will throw.
     */
    private var closed = false
    private val subscription: Subscription

    init {
        this.subscription = Observable.interval(5, TimeUnit.SECONDS, scheduler)
                .subscribe { poll() }
    }

    /**
     * @param nodeDir a path to be watched for NodeInfos
     * Add a path of a node which is about to be started.
     * Its nodeInfo file will be copied to other nodes' additional-node-infos directory, and conversely,
     * other nodes' nodeInfo files will be copied to this node additional-node-infos directory.
     */
    fun addConfig(nodeDir: Path) {
        require(!closed) { "NodeInfoFilesCopier is already closed" }
        nodeDataMapBox.locked {
            val newNodeFile = NodeData(nodeDir)
            put(nodeDir, newNodeFile)

            for (previouslySeenFile in allPreviouslySeenFiles()) {
                atomicCopy(previouslySeenFile, newNodeFile.additionalNodeInfoDirectory.resolve(previouslySeenFile.fileName))
            }
            log.info("Now watching: $nodeDir")
        }
    }

    /**
     * @param nodeConfig the configuration to be removed.
     * Remove the configuration of a node which is about to be stopped or already stopped.
     * No files written by that node will be copied to other nodes, nor files from other nodes will be copied to this
     * one.
     */
    fun removeConfig(nodeDir: Path) {
        require(!closed) { "NodeInfoFilesCopier is already closed" }
        nodeDataMapBox.locked {
            remove(nodeDir) ?: return
            log.info("Stopped watching: $nodeDir")
        }
    }

    fun reset() {
        require(!closed) { "NodeInfoFilesCopier is already closed" }
        nodeDataMapBox.locked {
            clear()
        }
    }

    /**
     * Stops polling the filesystem.
     * This function can be called as many times as one wants.
     */
    override fun close() {
        if (!closed) {
            closed = true
            subscription.unsubscribe()
        }
    }

    private fun allPreviouslySeenFiles() = nodeDataMapBox.alreadyLocked { values.flatMap { it.previouslySeenFiles.keys } }

    private fun poll() {
        nodeDataMapBox.locked {
            for (nodeData in values) {
                nodeData.nodeDir.list { paths ->
                    paths.filter { it.isRegularFile() }
                            .filter { it.fileName.toString().startsWith(NODE_INFO_FILE_NAME_PREFIX) }
                            .forEach { path -> processPath(nodeData, path) }
                }
            }
        }
    }

    // Takes a path under nodeData config dir and decides whether the file represented by that path needs to
    // be copied.
    private fun processPath(nodeData: NodeData, path: Path) {
        nodeDataMapBox.alreadyLocked {
            val newTimestamp = Files.readAttributes(path, BasicFileAttributes::class.java).lastModifiedTime()
            val previousTimestamp = nodeData.previouslySeenFiles.put(path, newTimestamp) ?: FileTime.fromMillis(-1)
            if (newTimestamp > previousTimestamp) {
                for (destination in this.values.filter { it.nodeDir != nodeData.nodeDir }.map { it.additionalNodeInfoDirectory }) {
                    val fullDestinationPath = destination.resolve(path.fileName)
                    atomicCopy(path, fullDestinationPath)
                }
            }
        }
    }

    private fun atomicCopy(source: Path, destination: Path) {
        val tempDestination = try {
            Files.createTempFile(destination.parent, "", null)
        } catch (exception: IOException) {
            log.warn("Couldn't create a temporary file to copy $source", exception)
            throw exception
        }
        try {
            // First copy the file to a temporary file within the appropriate directory.
            Files.copy(source, tempDestination, COPY_ATTRIBUTES, REPLACE_EXISTING)
        } catch (exception: IOException) {
            log.warn("Couldn't copy $source to $tempDestination.", exception)
            Files.delete(tempDestination)
            throw exception
        }
        try {
            // Then rename it to the desired name. This way the file 'appears' on the filesystem as an atomic operation.
            Files.move(tempDestination, destination, REPLACE_EXISTING)
        } catch (exception: IOException) {
            log.warn("Couldn't move $tempDestination to $destination.", exception)
            Files.delete(tempDestination)
            throw exception
        }
    }

    /**
     * Convenience holder for all the paths and files relative to a single node.
     */
    private class NodeData(val nodeDir: Path) {
        val additionalNodeInfoDirectory: Path = nodeDir.resolve(CordformNode.NODE_INFO_DIRECTORY)
        // Map from Path to its lastModifiedTime.
        val previouslySeenFiles = mutableMapOf<Path, FileTime>()

        init {
            additionalNodeInfoDirectory.createDirectories()
        }
    }
}
