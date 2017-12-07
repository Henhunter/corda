package net.corda.nodeapi.internal

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SignedData
import net.corda.core.crypto.sign
import net.corda.core.internal.copyTo
import net.corda.core.internal.div
import net.corda.core.serialization.serialize
import net.corda.nodeapi.internal.network.NetworkParameters
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPair

class NetworkParametersCopier(
        networkParameters: NetworkParameters,
        signingKeyPair: KeyPair = Crypto.generateKeyPair(),
        overwriteFile: Boolean = false
) {
    private val copyOptions = if (overwriteFile) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
    private val serializedNetworkParameters = networkParameters.let {
        val serialize = it.serialize()
        val signature = signingKeyPair.sign(serialize)
        SignedData(serialize, signature).serialize()
    }

    fun install(nodeDir: Path) {
        try {
            serializedNetworkParameters.open().copyTo(nodeDir / "network-parameters", *copyOptions)
        } catch (e: FileAlreadyExistsException) {
            // This is only thrown if the file already exists and we didn't specify to overwrite it. In that case we
            // ignore this exception as we're happy with the existing file.
        }
    }
}