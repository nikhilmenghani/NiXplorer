package com.nixplorer.screen.main.tab.files.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream

/** Providers do not promise atomic rename or durable partial-write rollback. Do not imply local-file parity. */
data class ProviderCapabilities(val readable: Boolean, val writable: Boolean, val canCreate: Boolean,
    val canDelete: Boolean, val canRename: Boolean, val virtual: Boolean) {
    fun requireRead() { if (!readable || virtual) throw IOException("Document cannot be read as a file; reselect it and grant access") }
    fun requireSafeReplacement(): Nothing = throw IOException("Safe replacement/move is unavailable for this document provider. Copy to local storage first.")
}

fun openProviderStream(capabilities: ProviderCapabilities, opener: () -> InputStream?): InputStream {
    capabilities.requireRead()
    try { return opener() ?: throw IOException("Document provider is disconnected or returned no data") }
    catch (e: SecurityException) { throw IOException("Document access was revoked. Reselect the document and grant access.", e) }
}

object DocumentProviderAccess {
    fun capabilities(context: Context, uri: Uri): ProviderCapabilities {
        if (!DocumentsContract.isDocumentUri(context, uri)) return ProviderCapabilities(true, false, false, false, false, false)
        try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("Document no longer exists or provider is disconnected")
                val flags = cursor.getInt(0)
                fun supports(flag: Int) = flags and flag != 0
                val writeGranted = context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                return ProviderCapabilities(true, writeGranted && supports(DocumentsContract.Document.FLAG_SUPPORTS_WRITE),
                    writeGranted && supports(DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE), writeGranted && supports(DocumentsContract.Document.FLAG_SUPPORTS_DELETE),
                    writeGranted && supports(DocumentsContract.Document.FLAG_SUPPORTS_RENAME), supports(DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT))
            }
            throw IOException("Document provider is unavailable")
        } catch (e: SecurityException) { throw IOException("Document access revoked; reselect the document", e) }
    }

    fun openRead(context: Context, uri: Uri): InputStream = openProviderStream(capabilities(context, uri)) {
        context.contentResolver.openInputStream(uri)
    }

    /** Persist only permissions actually offered by the caller. Existing persisted grants are used by the resolver. */
    fun retainOfferedReadGrant(context: Context, uri: Uri, flags: Int) {
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* A transient grant may still be valid for this import. */ }
        }
    }
}
