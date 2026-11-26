package app.grapheneos.pdfviewer

import java.text.DateFormat
import java.text.ParseException
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

// PDF date string format, per section 7.9.4 of PDF 32000-2:2020:
//
//     D:YYYYMMDDHHmmSSOHH'mm
//
// PDF <= 1.7 required a trailing apostrophe, which is still accepted. The
// apostrophe between the offset HH and mm may also be omitted, matching pdf.js.
private val PDF_DATE_PATTERN = Regex(
    """
        ^D:
        (\d{4})        # Year (required)
        (\d{2})?       # Month (optional)
        (\d{2})?       # Day (optional)
        (\d{2})?       # Hours (optional)
        (\d{2})?       # Minutes (optional)
        (\d{2})?       # Seconds (optional)
        (?:
          ([Z+\-])     # Universal time relation
          (?:
            (\d{2})    # Offset hours
            '?         # Splitting apostrophe (optional)
            (?:
              (\d{2})  # Offset minutes
              '?       # Trailing apostrophe (optional, PDF <= 1.7)
            )?
          )?
        )?$
    """.trimIndent(),
    RegexOption.COMMENTS
)

private fun parseIntSafely(field: String): Int =
    field.toIntOrNull() ?: throw ParseException("Error while parsing int", -1)

private fun parseGroup(matchResult: MatchResult, group: Int, defaultValue: Int): Int {
    val field = matchResult.groups[group]?.value ?: return defaultValue
    return parseIntSafely(field)
}

// Resolve the universal time relation into a TimeZone. A missing relation or
// "Z" means UTC; offsets are capped to the real-world UTC-12:00 to UTC+14:00 range.
private fun parseTimeZone(matchResult: MatchResult): TimeZone {
    val utRel = matchResult.groups[7]?.value
    val offsetHours = parseGroup(matchResult, 8, 0)
    val offsetMinutes = parseGroup(matchResult, 9, 0)

    if (utRel == null || utRel == "Z") {
        if (offsetHours != 0 || offsetMinutes != 0) {
            throw ParseException("UTC indicator 'Z' must not have a non-zero offset", 0)
        }
        return TimeZone.getTimeZone("UTC")
    }
    if (offsetMinutes > 59) {
        throw ParseException("Invalid UTC offset minutes", 0)
    }
    val magnitude = offsetHours * 100 + offsetMinutes
    if (utRel == "-") {
        if (magnitude > 1200) {
            throw ParseException("Invalid UTC offset", 0)
        }
        val id = String.format(Locale.US, "GMT-%02d:%02d", offsetHours, offsetMinutes)
        return TimeZone.getTimeZone(id)
    }
    if (magnitude > 1400) {
        throw ParseException("Invalid UTC offset", 0)
    }
    val id = String.format(Locale.US, "GMT+%02d:%02d", offsetHours, offsetMinutes)
    return TimeZone.getTimeZone(id)
}

// Parse date as per PDF spec (complies with PDF v1.4 to v2.0), API 23+ compatible.
@Throws(ParseException::class)
fun parseDate(date: String): String {
    // D: prefix is optional for PDF < v1.7; required for PDF v1.7+
    val input = if (date.startsWith("D:")) date else "D:$date"

    val matchResult = PDF_DATE_PATTERN.matchEntire(input)
        ?: throw ParseException("Invalid date format", 0)

    val year = parseIntSafely(matchResult.groups[1]?.value ?: throw ParseException("Missing year", 0))
    val month = parseGroup(matchResult, 2, 1)
    val day = parseGroup(matchResult, 3, 1)
    val hours = parseGroup(matchResult, 4, 0)
    val minutes = parseGroup(matchResult, 5, 0)
    val seconds = parseGroup(matchResult, 6, 0)
    val timeZone = parseTimeZone(matchResult)

    if (month !in 1..12 || day !in 1..31 || hours !in 0..23 || minutes !in 0..59 || seconds !in 0..59) {
        throw ParseException("Invalid date field", 0)
    }

    val instant: Date = try {
        val cal = GregorianCalendar(timeZone, Locale.US).apply {
            isLenient = false
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, seconds)
            set(Calendar.MILLISECOND, 0)
        }
        cal.time
    } catch (_: IllegalArgumentException) {
        throw ParseException("Invalid date", 0)
    }

    // Rendered in the device's default time zone and locale.
    return DateFormat
        .getDateTimeInstance(DateFormat.DEFAULT, DateFormat.LONG)
        .format(instant)
}
