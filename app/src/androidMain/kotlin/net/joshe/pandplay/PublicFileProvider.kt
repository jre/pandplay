package net.joshe.pandplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import net.joshe.pandplay.local.LocalPaths
import java.io.File

class PublicFileProvider() : ContentProvider() {
    companion object {
        fun getUri(tag: String, pathname: String) = Uri.Builder()
            .scheme("content")
            .authority(LocalPaths.authority)
            .appendPath(tag)
            .appendPath(pathname)
            .build()!!
    }

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        if (info.authority != LocalPaths.authority)
            throw IllegalArgumentException("invalid provider authority: ${info.authority}")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String?>?) = 0

    override fun getType(uri: Uri) = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()

    override fun onCreate() = true

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String?>?)
            = throw UnsupportedOperationException()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r")
            throw UnsupportedOperationException()
        if (uri.scheme != "content")
            throw IllegalArgumentException("not a content URI: $uri")
        if (uri.authority != LocalPaths.authority)
            throw IllegalArgumentException("invalid provider authority in URI: $uri")
        if (uri.pathSegments.size < 2)
            throw IllegalArgumentException("invalid path in URI: $uri")
        val base = LocalPaths.paths[uri.pathSegments[0]]
            ?: throw IllegalArgumentException("unknown path in URI: $uri")
        val relStr = uri.pathSegments.subList(1, uri.pathSegments.size).joinToString(File.separator)
        val res = base.resolve(relStr).normalize()
        if (!res.path.startsWith(base.path + File.separator))
            throw IllegalArgumentException("invalid path in URI: $uri")

        return ParcelFileDescriptor.open(res, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String?>?, selection: String?,
        selectionArgs: Array<out String?>?, sortOrder: String?) = throw UnsupportedOperationException()
}
