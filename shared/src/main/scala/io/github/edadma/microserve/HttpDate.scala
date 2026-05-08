package io.github.edadma.microserve

/** RFC 7231 §7.1.1.1 HTTP-date formatter, hand-rolled so we don't depend on
  * `java.time` (Scala.js needs `scala-java-time` for that, an extra dep).
  *
  * Output looks exactly like `"Sun, 06 Nov 1994 08:49:37 GMT"`. The only input
  * is epoch-millis, which every platform exposes via `System.currentTimeMillis()`.
  */
private[microserve] object HttpDate:
  private val DayNames   = Array("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
  private val MonthNames = Array("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

  /** January 1 1970 was a Thursday — index 4 in `DayNames`. */
  private val EpochDayOfWeek = 4

  def format(epochMillis: Long): String =
    val totalSeconds = Math.floorDiv(epochMillis, 1000L)
    val daysSinceEpoch = Math.floorDiv(totalSeconds, 86400L).toInt
    val secondsOfDay = Math.floorMod(totalSeconds, 86400L).toInt

    val hour = secondsOfDay / 3600
    val minute = (secondsOfDay % 3600) / 60
    val second = secondsOfDay % 60

    val dow = Math.floorMod(EpochDayOfWeek + daysSinceEpoch, 7)
    val (year, month, day) = civilFromDays(daysSinceEpoch)

    val sb = new StringBuilder(29)
    sb.append(DayNames(dow)).append(", ")
    pad2(sb, day).append(' ').append(MonthNames(month - 1)).append(' ').append(year).append(' ')
    pad2(sb, hour).append(':')
    pad2(sb, minute).append(':')
    pad2(sb, second).append(" GMT")
    sb.toString

  private def pad2(sb: StringBuilder, n: Int): StringBuilder =
    if n < 10 then sb.append('0')
    sb.append(n)

  /** Howard Hinnant's "civil from days" algorithm — converts days-since-1970-01-01
    * into proleptic-Gregorian (year, month [1..12], day [1..31]). Branchless,
    * exact for the full Long range, no leap-second / time-zone wrinkles.
    *
    * Reference: http://howardhinnant.github.io/date_algorithms.html#civil_from_days
    */
  private def civilFromDays(daysSinceEpoch: Int): (Int, Int, Int) =
    val z = daysSinceEpoch + 719468
    val era = if z >= 0 then z / 146097 else (z - 146096) / 146097
    val doe = z - era * 146097                                   // day-of-era [0..146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // year-of-era [0..399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)            // day-of-year [0..365]
    val mp = (5 * doy + 2) / 153                                  // month-prime [0..11]
    val d = doy - (153 * mp + 2) / 5 + 1                          // day [1..31]
    val m = if mp < 10 then mp + 3 else mp - 9                    // month [1..12]
    val year = if m <= 2 then y + 1 else y
    (year, m, d)
end HttpDate
