// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactMatch(
    val displayName: String,
    val phoneNumbers: List<String>,
    val emails: List<String>,
)

/**
 * Reads device contacts via [ContactsContract] for the chat agent's `lookup_contact` tool.
 *
 * This data is sensitive — call sites must check the `contactsAccessEnabled` privacy flag
 * before invoking, since granting Android's READ_CONTACTS permission is not the same as
 * consenting to send contact info to a remote LLM.
 */
@Singleton
class ContactsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun search(query: String, limit: Int = 5): List<ContactMatch> {
        if (query.isBlank()) return emptyList()
        if (!hasPermission()) return emptyList()

        return withContext(Dispatchers.IO) {
            val matches = mutableMapOf<Long, ContactMatch>()
            val resolver = context.contentResolver
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            try {
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER,
                    ),
                    selection,
                    selectionArgs,
                    "${ContactsContract.Contacts.STARRED} DESC, " +
                        "${ContactsContract.Contacts.TIMES_CONTACTED} DESC " +
                        "LIMIT ${limit.coerceAtMost(MAX_LIMIT)}",
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val name = cursor.getString(nameIdx) ?: continue
                        matches[id] = ContactMatch(name, emptyList(), emptyList())
                    }
                }

                for ((contactId, _) in matches.toMap()) {
                    val phones = readPhones(contactId)
                    val emails = readEmails(contactId)
                    matches[contactId] = matches[contactId]!!.copy(
                        phoneNumbers = phones,
                        emails = emails,
                    )
                }
            } catch (e: SecurityException) {
                Log.d(TAG, "READ_CONTACTS revoked at query time")
                return@withContext emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Contact lookup failed", e)
                return@withContext emptyList()
            }

            matches.values.toList()
        }
    }

    private fun readPhones(contactId: Long): List<String> {
        val phones = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                cursor.getString(numIdx)?.takeIf { it.isNotBlank() }?.let { phones.add(it) }
            }
        }
        return phones.distinct()
    }

    private fun readEmails(contactId: Long): List<String> {
        val emails = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            val addrIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (cursor.moveToNext()) {
                cursor.getString(addrIdx)?.takeIf { it.isNotBlank() }?.let { emails.add(it) }
            }
        }
        return emails.distinct()
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "ARIA.Contacts"
        private const val MAX_LIMIT = 20
    }
}
