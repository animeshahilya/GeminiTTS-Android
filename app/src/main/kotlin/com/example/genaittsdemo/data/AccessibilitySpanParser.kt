package com.example.genaittsdemo.data

import android.os.PersistableBundle
import android.text.Spanned
import android.text.style.TtsSpan
import android.text.style.URLSpan

/**
 * Parses and translates Android accessibility Spanned text (specifically [TtsSpan])
 * into high-fidelity, phonetically-expanded text suitable for the Gemini on-device NPU TTS model.
 *
 * Screen readers such as Android TalkBack attach [TtsSpan] metadata to text ranges to indicate
 * telephone numbers, currencies, dates, times, fractions, units of measure, verbatim characters,
 * and URLs. Standard TTS callers that simply invoke `charSequence.toString()` lose this semantic
 * information entirely. This parser extracts and expands all spans before model synthesis.
 */
object AccessibilitySpanParser {

    /**
     * Parses the incoming [CharSequence], extracting and replacing all [TtsSpan] annotations
     * with expanded, speech-optimized natural text.
     */
    fun parse(charSequence: CharSequence?): String {
        if (charSequence == null) return ""
        if (charSequence !is Spanned) return charSequence.toString()

        val text = charSequence.toString()
        val spans = charSequence.getSpans(0, charSequence.length, Any::class.java)
            .filter { it is TtsSpan || it is URLSpan }

        if (spans.isEmpty()) return text

        // Sort spans by start index, then by end index descending
        val sortedSpans = spans.map { span ->
            val start = charSequence.getSpanStart(span)
            val end = charSequence.getSpanEnd(span)
            SpanRange(span, start, end)
        }.sortedWith(compareBy({ it.start }, { -it.end }))

        val sb = StringBuilder()
        var currentIndex = 0

        for (range in sortedSpans) {
            // Ignore nested or backwards spans
            if (range.start < currentIndex) continue
            if (range.start >= text.length) break

            // Append unspanned text preceding this span
            if (range.start > currentIndex) {
                sb.append(text.substring(currentIndex, range.start))
            }

            val rawSpanText = if (range.end <= text.length) {
                text.substring(range.start, range.end)
            } else {
                text.substring(range.start)
            }

            val expanded = when (val span = range.span) {
                is TtsSpan -> expandTtsSpan(span, rawSpanText)
                is URLSpan -> expandUrl(span.url ?: rawSpanText)
                else -> rawSpanText
            }

            sb.append(expanded)
            currentIndex = range.end
        }

        // Append any remaining trailing text
        if (currentIndex < text.length) {
            sb.append(text.substring(currentIndex))
        }

        return sb.toString()
    }

    private data class SpanRange(
        val span: Any,
        val start: Int,
        val end: Int
    )

    private fun expandTtsSpan(span: TtsSpan, rawText: String): String {
        val args = span.args ?: return rawText
        val type = span.type ?: return rawText

        return try {
            when (type) {
                TtsSpan.TYPE_CARDINAL -> expandCardinal(args, rawText)
                TtsSpan.TYPE_ORDINAL -> expandOrdinal(args, rawText)
                TtsSpan.TYPE_DIGITS -> expandDigits(args, rawText)
                TtsSpan.TYPE_TELEPHONE -> expandTelephone(args, rawText)
                TtsSpan.TYPE_MONEY -> expandMoney(args, rawText)
                TtsSpan.TYPE_TIME -> expandTime(args, rawText)
                TtsSpan.TYPE_DATE -> expandDate(args, rawText)
                TtsSpan.TYPE_DECIMAL -> expandDecimal(args, rawText)
                TtsSpan.TYPE_FRACTION -> expandFraction(args, rawText)
                TtsSpan.TYPE_MEASURE -> expandMeasure(args, rawText)
                TtsSpan.TYPE_ELECTRONIC -> expandElectronic(args, rawText)
                TtsSpan.TYPE_VERBATIM -> expandVerbatim(args, rawText)
                TtsSpan.TYPE_TEXT -> args.getString(TtsSpan.ARG_TEXT) ?: rawText
                else -> rawText
            }
        } catch (_: Exception) {
            rawText
        }
    }

    private fun expandCardinal(args: PersistableBundle, fallback: String): String {
        return args.getString(TtsSpan.ARG_NUMBER)
            ?: args.getString(TtsSpan.ARG_INTEGER_PART)
            ?: fallback
    }

    private fun expandOrdinal(args: PersistableBundle, fallback: String): String {
        val numberStr = args.getString(TtsSpan.ARG_NUMBER) ?: fallback
        val num = numberStr.toLongOrNull() ?: return numberStr
        return numberToOrdinalWord(num)
    }

    private fun expandDigits(args: PersistableBundle, fallback: String): String {
        val digits = args.getString(TtsSpan.ARG_DIGITS) ?: fallback
        // Separate digits with spaces so the NPU synthesizes each digit individually
        return digits.toCharArray().joinToString(" ")
    }

    private fun expandTelephone(args: PersistableBundle, fallback: String): String {
        val countryCode = args.getString(TtsSpan.ARG_COUNTRY_CODE)
        val numberParts = args.getString(TtsSpan.ARG_NUMBER_PARTS)
        val extension = args.getString(TtsSpan.ARG_EXTENSION)

        val parts = mutableListOf<String>()
        if (!countryCode.isNullOrBlank()) {
            val codeClean = if (countryCode.startsWith("+")) "plus ${countryCode.drop(1)}" else countryCode
            parts.add(codeClean)
        }
        if (!numberParts.isNullOrBlank()) {
            // Group digit sequences with pauses
            val formatted = numberParts.split(Regex("[\\s-]+"))
                .map { segment ->
                    if (segment.all { it.isDigit() }) segment.toCharArray().joinToString(" ") else segment
                }
                .joinToString(", ")
            parts.add(formatted)
        }
        if (!extension.isNullOrBlank()) {
            val extDigits = extension.toCharArray().joinToString(" ")
            parts.add("extension $extDigits")
        }

        return if (parts.isNotEmpty()) parts.joinToString(", ") else fallback
    }

    private fun expandMoney(args: PersistableBundle, fallback: String): String {
        val currency = args.getString(TtsSpan.ARG_CURRENCY)?.uppercase() ?: "USD"
        val integerPart = args.getString(TtsSpan.ARG_INTEGER_PART) ?: "0"
        val fractionalPart = args.getString(TtsSpan.ARG_FRACTIONAL_PART)

        val (majorUnit, minorUnit) = when (currency) {
            "USD", "$" -> "dollar" to "cent"
            "EUR", "€" -> "euro" to "cent"
            "GBP", "£" -> "pound" to "pence"
            "JPY", "¥" -> "yen" to ""
            "INR", "₹" -> "rupee" to "paise"
            "CAD" -> "Canadian dollar" to "cent"
            "AUD" -> "Australian dollar" to "cent"
            else -> currency to "cent"
        }

        val majorInt = integerPart.toLongOrNull() ?: 0L
        val majorStr = "$majorInt " + if (majorInt == 1L) majorUnit else "${majorUnit}s"

        if (fractionalPart.isNullOrBlank() || fractionalPart == "00" || fractionalPart == "0") {
            return majorStr
        }

        val minorInt = fractionalPart.toLongOrNull() ?: 0L
        val minorStr = if (minorUnit.isNotBlank()) {
            "$minorInt " + if (minorInt == 1L) minorUnit else "${minorUnit}s"
        } else {
            "point $fractionalPart"
        }

        return if (majorInt > 0) "$majorStr and $minorStr" else minorStr
    }

    private fun expandTime(args: PersistableBundle, fallback: String): String {
        val hours = args.getInt(TtsSpan.ARG_HOURS, -1)
        val minutes = args.getInt(TtsSpan.ARG_MINUTES, -1)

        if (hours in 0..23 && minutes in 0..59) {
            val period = if (hours >= 12) "PM" else "AM"
            val displayHour = when (hours) {
                0 -> 12
                in 1..12 -> hours
                else -> hours - 12
            }
            val displayMin = if (minutes < 10) "oh $minutes" else "$minutes"
            return if (minutes == 0) "$displayHour $period" else "$displayHour $displayMin $period"
        }
        return fallback
    }

    private fun expandDate(args: PersistableBundle, fallback: String): String {
        val weekday = args.getString(TtsSpan.ARG_WEEKDAY)
        val day = args.getInt(TtsSpan.ARG_DAY, -1)
        val month = args.getInt(TtsSpan.ARG_MONTH, -1)
        val year = args.getInt(TtsSpan.ARG_YEAR, -1)

        val months = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        val parts = mutableListOf<String>()
        if (!weekday.isNullOrBlank()) parts.add(weekday)
        if (month in 0..11) {
            val monthName = months[month]
            if (day in 1..31) {
                parts.add("$monthName ${numberToOrdinalWord(day.toLong())}")
            } else {
                parts.add(monthName)
            }
        } else if (day in 1..31) {
            parts.add(numberToOrdinalWord(day.toLong()))
        }

        if (year > 0) parts.add("$year")

        return if (parts.isNotEmpty()) parts.joinToString(", ") else fallback
    }

    private fun expandDecimal(args: PersistableBundle, fallback: String): String {
        val integerPart = args.getString(TtsSpan.ARG_INTEGER_PART) ?: "0"
        val fractionalPart = args.getString(TtsSpan.ARG_FRACTIONAL_PART) ?: ""
        return if (fractionalPart.isNotBlank()) {
            val fracSpaced = fractionalPart.toCharArray().joinToString(" ")
            "$integerPart point $fracSpaced"
        } else {
            integerPart
        }
    }

    private fun expandFraction(args: PersistableBundle, fallback: String): String {
        val intPart = args.getString(TtsSpan.ARG_INTEGER_PART)
        val num = args.getString(TtsSpan.ARG_NUMERATOR) ?: return fallback
        val den = args.getString(TtsSpan.ARG_DENOMINATOR) ?: return fallback

        val numVal = num.toLongOrNull() ?: 1L
        val denName = when (den) {
            "2" -> if (numVal == 1L) "half" else "halves"
            "3" -> if (numVal == 1L) "third" else "thirds"
            "4" -> if (numVal == 1L) "quarter" else "quarters"
            "5" -> if (numVal == 1L) "fifth" else "fifths"
            "8" -> if (numVal == 1L) "eighth" else "eighths"
            else -> "${numberToOrdinalWord(den.toLongOrNull() ?: 4L)}${if (numVal > 1L) "s" else ""}"
        }

        val fractionStr = "$num $denName"
        return if (!intPart.isNullOrBlank() && intPart != "0") {
            "$intPart and $fractionStr"
        } else {
            fractionStr
        }
    }

    private fun expandMeasure(args: PersistableBundle, fallback: String): String {
        val number = args.getString(TtsSpan.ARG_NUMBER) ?: ""
        val unit = args.getString(TtsSpan.ARG_UNIT)?.lowercase() ?: ""

        val expandedUnit = when (unit) {
            "km/h", "kph" -> "kilometers per hour"
            "mph" -> "miles per hour"
            "m" -> "meters"
            "km" -> "kilometers"
            "cm" -> "centimeters"
            "mm" -> "millimeters"
            "kg" -> "kilograms"
            "g" -> "grams"
            "mg" -> "milligrams"
            "lb", "lbs" -> "pounds"
            "oz" -> "ounces"
            "mb" -> "megabytes"
            "gb" -> "gigabytes"
            "tb" -> "terabytes"
            "kb" -> "kilobytes"
            "ms" -> "milliseconds"
            "s", "sec" -> "seconds"
            "min" -> "minutes"
            "h", "hr" -> "hours"
            "c", "°c" -> "degrees Celsius"
            "f", "°f" -> "degrees Fahrenheit"
            "%" -> "percent"
            else -> unit
        }

        return if (number.isNotBlank()) "$number $expandedUnit" else fallback
    }

    private fun expandElectronic(args: PersistableBundle, fallback: String): String {
        val domain = args.getString(TtsSpan.ARG_DOMAIN)
        val username = args.getString(TtsSpan.ARG_USERNAME)
        val path = args.getString(TtsSpan.ARG_PATH)

        if (!username.isNullOrBlank() && !domain.isNullOrBlank()) {
            val domainClean = domain.replace(".", " dot ")
            return "$username at $domainClean"
        }

        val url = domain ?: fallback
        return expandUrl(if (!path.isNullOrBlank()) "$url/$path" else url)
    }

    private fun expandVerbatim(args: PersistableBundle, fallback: String): String {
        val verbatim = args.getString(TtsSpan.ARG_VERBATIM) ?: fallback
        return verbatim.toCharArray().joinToString(" ")
    }

    private fun expandUrl(url: String): String {
        return url
            .replace(Regex("^https?://(www\\.)?"), "")
            .replace(".", " dot ")
            .replace("/", " slash ")
            .replace("-", " dash ")
            .replace("_", " underscore ")
            .replace("?", " question mark ")
            .replace("=", " equals ")
            .replace("&", " and ")
            .replace("#", " hash ")
            .trim()
    }

    private fun numberToOrdinalWord(number: Long): String {
        return when (number) {
            1L -> "first"
            2L -> "second"
            3L -> "third"
            4L -> "fourth"
            5L -> "fifth"
            6L -> "sixth"
            7L -> "seventh"
            8L -> "eighth"
            9L -> "ninth"
            10L -> "tenth"
            11L -> "eleventh"
            12L -> "twelfth"
            13L -> "thirteenth"
            14L -> "fourteenth"
            15L -> "fifteenth"
            16L -> "sixteenth"
            17L -> "seventeenth"
            18L -> "eighteenth"
            19L -> "nineteenth"
            20L -> "twentieth"
            21L -> "twenty-first"
            22L -> "twenty-second"
            23L -> "twenty-third"
            24L -> "twenty-fourth"
            25L -> "twenty-fifth"
            26L -> "twenty-sixth"
            27L -> "twenty-seventh"
            28L -> "twenty-eighth"
            29L -> "twenty-ninth"
            30L -> "thirtieth"
            31L -> "thirty-first"
            40L -> "fortieth"
            50L -> "fiftieth"
            60L -> "sixtieth"
            70L -> "seventieth"
            80L -> "eightieth"
            90L -> "ninetieth"
            100L -> "one hundredth"
            else -> "${number}th"
        }
    }
}
