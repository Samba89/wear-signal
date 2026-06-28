package dev.sam.wearsignal.contacts

import android.content.Context
import android.provider.ContactsContract
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import dev.sam.wearsignal.AppDeps
import org.signal.core.util.logging.Log
import java.util.Locale

/**
 * Reads the watch's synced contacts and normalizes their phone numbers to E.164,
 * which is the form Signal's contact-discovery service ([dev.sam.wearsignal.net.CdsApi]) expects.
 */
object ContactReader {

  private val TAG = Log.tag(ContactReader::class)

  data class DeviceContact(val name: String, val e164: String)

  fun read(context: Context): List<DeviceContact> {
    val region = defaultRegion(context)
    val util = PhoneNumberUtil.getInstance()
    // Keyed by E.164 so the same number under multiple contacts/labels collapses to one entry.
    val byNumber = LinkedHashMap<String, DeviceContact>()

    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER
    )
    context.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      projection,
      null,
      null,
      "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
    )?.use { cursor ->
      val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
      val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
      while (cursor.moveToNext()) {
        val name = cursor.getString(nameIdx)?.trim().orEmpty()
        val raw = cursor.getString(numberIdx) ?: continue
        val e164 = try {
          val parsed = util.parse(raw, region)
          if (util.isValidNumber(parsed)) util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164) else null
        } catch (e: NumberParseException) {
          null
        } ?: continue
        byNumber.putIfAbsent(e164, DeviceContact(name.ifEmpty { e164 }, e164))
      }
    }

    Log.i(TAG, "Read ${byNumber.size} distinct contact numbers")
    return byNumber.values.toList()
  }

  /** Region for parsing numbers that lack a country code: the user's own number first, then device locale. */
  private fun defaultRegion(context: Context): String {
    val util = PhoneNumberUtil.getInstance()
    AppDeps.account.e164?.let { own ->
      try {
        util.getRegionCodeForNumber(util.parse(own, null))?.let { return it }
      } catch (_: NumberParseException) {
      }
    }
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    return locale.country.ifEmpty { "US" }
  }
}
