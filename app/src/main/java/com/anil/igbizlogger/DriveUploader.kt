package com.anil.igbizlogger

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.io.File

/**
 * Uploads the day's encrypted export to a single Drive folder tied to the signed-in
 * Google account. Requires one-time setup (see README section "Drive backup setup"):
 * an OAuth client ID registered in Google Cloud Console for this package name + your
 * signing certificate's SHA-1. I can't generate that credential for you — it's tied
 * to your own Google Cloud project and app signing key.
 */
object DriveUploader {

    private const val FOLDER_NAME = "IGBizLogger Backups"

    fun uploadTodayExport(context: Context, file: File): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return false
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_FILE))
        credential.selectedAccount = account

        val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Biz Logger")
            .build()

        val folderId = findOrCreateFolder(drive)

        val metadata = com.google.api.services.drive.model.File().apply {
            name = file.name
            parents = listOf(folderId)
        }
        val content = FileContent("application/octet-stream", file)
        drive.files().create(metadata, content).setFields("id").execute()

        // Local deletion timing is configurable in Settings — defaults to immediate,
        // per your original ask, but can be delayed so you have a local copy for a
        // while after upload too.
        val delayHours = SecurePrefs.getAutoDeleteDelayHours(context)
        if (delayHours <= 0) {
            file.delete()
        } else {
            DeleteLocalFileWorker.scheduleDelete(context, file, delayHours)
        }
        return true
    }

    private fun findOrCreateFolder(drive: Drive): String {
        val existing = drive.files().list()
            .setQ("name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false")
            .setSpaces("drive")
            .execute()
        existing.files?.firstOrNull()?.let { return it.id }

        val folderMeta = com.google.api.services.drive.model.File().apply {
            name = FOLDER_NAME
            mimeType = "application/vnd.google-apps.folder"
        }
        return drive.files().create(folderMeta).setFields("id").execute().id
    }
}
