package com.mozhi.reader.core.readium

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.asset.ContainerAsset
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

@Singleton
class ReadiumServices @Inject constructor(
    @ApplicationContext context: Context
) {
    private val httpClient = DefaultHttpClient()
    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null
        )
    )

    suspend fun open(file: File, allowUserInteraction: Boolean = false): Publication {
        val asset = assetRetriever
            .retrieve(file.absoluteFile.toUrl(isDirectory = false))
            .getOrElse { error -> throw ReadiumOpenException(error.toString()) }

        val compatibleAsset = try {
            if (asset is ContainerAsset && file.extension.equals("epub", ignoreCase = true)) {
                ContainerAsset(asset.format, EpubUriContainer.wrap(asset.container, file))
            } else asset
        } catch (error: Throwable) {
            asset.close()
            throw error
        }
        return try {
            publicationOpener
                .open(compatibleAsset, allowUserInteraction = allowUserInteraction)
                .getOrElse { error -> throw ReadiumOpenException(error.toString()) }
        } catch (error: Throwable) {
            compatibleAsset.close()
            throw error
        }
    }
}

class ReadiumOpenException(message: String) : Exception(message)
