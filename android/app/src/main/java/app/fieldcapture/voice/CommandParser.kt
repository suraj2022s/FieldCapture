package app.fieldcapture.voice

/** Everything the tech can say. The first three are the brief's; the rest keep the walk hands-free. */
sealed interface Command {
  data object CaptureNameplate : Command

  /** room/surface are canonical names when recognised; the raw words are kept so nothing is lost. */
  data class CaptureDamage(val room: String?, val surface: String?, val unparsed: String) : Command

  data class Note(val text: String) : Command

  data object StartJob : Command

  data object PauseJob : Command

  data object ResumeJob : Command

  data object EndJob : Command

  /** Accept the best frame even though the quality gate rejected it. */
  data object CaptureAnyway : Command

  /** Replace the last capture with a new one. */
  data object Retake : Command

  data object UndoLast : Command

  data object Status : Command
}

data class Parsed(val command: Command, val transcript: String, val confidence: Double)

/**
 * Deterministic parser for spoken commands, built as a registry of intents.
 *
 * Why not an LLM: it must work with no signal, add ~0 ms, and be unit-testable. Adding a command
 * (e.g. the live-session "capture moisture reading [value] [location]") is one [IntentSpec] entry
 * plus a test, and [SpokenNumbers] already turns "twenty two point five" into 22.5.
 */
object CommandParser {

  private class IntentSpec(val name: String, val pattern: Regex, val build: (MatchResult) -> Command?)

  private val VERB = "(?:capture|captured|capturing|take|snap|grab|shoot|click|photograph|record)"
  // "name blade", "name play it", "name plait": how Google's recogniser heard "nameplate" from the
  // user (Indian English) on the first real walk, 2026-11-26; five of them were filed as damage.
  private val NAMEPLATE_WORD =
      "(?:name ?(?:plates?|plated|plait|plat|blades?|play(?:ed| it)?|place)|nameplates?|data ?plate|rating ?plate|" +
          "serial (?:plate|tag|label|number)|spec (?:plate|label)|model number|label|sticker)"

  private val intents: List<IntentSpec> =
      listOf(
          // "capture nameplate", "take a photo of the label on the heater", "click the rating plate"
          IntentSpec("nameplate", Regex("^$VERB\\b.*\\b$NAMEPLATE_WORD\\b.*")) { Command.CaptureNameplate },
          // "capture the name": the end of "nameplate" lost (same walk).
          IntentSpec("nameplate_cut", Regex("^$VERB (?:the |a )?name$")) { Command.CaptureNameplate },
          IntentSpec("damage", Regex("^$VERB (?:the |some )?damage[sd]?\\b(.*)$")) { m ->
            val rest = m.groupValues[1].trim()
            val room = Vocabulary.findRoom(rest)
            val surface = Vocabulary.findSurface(rest)
            Command.CaptureDamage(room, surface, rest)
          },
          IntentSpec("note", Regex("^(?:note that|notes?|add (?:a )?note|make a note)\\b[ :,.-]*(.*)$")) { m ->
            val text = m.groupValues[1].trim()
            if (text.isEmpty()) null else Command.Note(text)
          },
          IntentSpec("anyway", Regex("^(?:$VERB (?:it |that )?anyway|keep (?:it|that)(?: anyway)?|use (?:it|that) anyway)\\b.*")) {
            Command.CaptureAnyway
          },
          IntentSpec("retake", Regex("^(?:retake|re take|redo|re do|try again|again)\\b.*")) { Command.Retake },
          IntentSpec("undo", Regex("^(?:undo|delete|scratch|cancel|remove) (?:that|the last|last)(?: one| capture| photo| note)?\\b.*")) {
            Command.UndoLast
          },
          IntentSpec("pause", Regex("^(?:pause|hold) (?:the )?(?:job|walk|walkthrough|inspection)\\b.*")) { Command.PauseJob },
          IntentSpec("resume", Regex("^(?:resume|continue|unpause|restart) (?:the )?(?:job|walk|walkthrough|inspection)\\b.*")) {
            Command.ResumeJob
          },
          IntentSpec("end", Regex("^(?:end|finish|stop|close|complete) (?:the )?(?:job|walk|walkthrough|inspection)\\b.*")) {
            Command.EndJob
          },
          IntentSpec("start", Regex("^(?:start|begin|new|open) (?:a |the )?(?:job|walk|walkthrough|inspection)\\b.*")) {
            Command.StartJob
          },
          IntentSpec("status", Regex("^(?:status|job status|how many|what'?s left|queue status)\\b.*")) { Command.Status },
          // Loose capture, measured on day 1: a real user said "please capture the broken wall".
          // Any other "capture/take/click (a photo of) <thing>" is a damage capture; the room and
          // surface are still parsed out of <thing>, and the words are kept for the back office.
          IntentSpec(
              "damage_loose",
              Regex("^$VERB (?:a |an )?(?:photo |picture |pic |shot |image |snap )?(?:of )?(?:the |this |that |these |some |a )?(.+)$"),
          ) { m ->
            val rest = m.groupValues[1].trim()
            // "please capture notes" was a damage photo (same walk): a note isn't something to photograph.
            if (rest.isEmpty() || rest == "anyway" || Regex("^notes?\\b").containsMatchIn(rest)) null
            else Command.CaptureDamage(Vocabulary.findRoom(rest), Vocabulary.findSurface(rest), rest)
          },
      )

  /** Words that mean the tech was trying to give a command, even if it didn't parse. */
  private val COMMAND_HINT = Regex("\\b(capture|nameplate|name plate|damage|note|photo|picture)\\b")

  /** True when [transcript] looks like a failed attempt at a command (worth a spoken hint). */
  fun looksLikeCommand(transcript: String): Boolean = COMMAND_HINT.containsMatchIn(normalize(transcript))

  /** Parses one transcript, or returns null if it isn't a command (chatter is ignored, not an error). */
  fun parse(transcript: String, asrConfidence: Double = 1.0): Parsed? {
    val text = normalize(transcript)
    if (text.isEmpty()) return null
    for (spec in intents) {
      val m = spec.pattern.matchEntire(text) ?: continue
      val cmd = spec.build(m) ?: continue
      val slotPenalty =
          when (cmd) {
            is Command.CaptureDamage ->
                (if (cmd.room == null) 0.15 else 0.0) + (if (cmd.surface == null) 0.15 else 0.0)
            else -> 0.0
          }
      // Recognisers report -1 or 0.0 when they have no score (Google's service on the test phone
      // returns 0.00 for every result), so <= 0 means "unknown", not "certainly wrong".
      val conf = (if (asrConfidence <= 0) UNKNOWN_ASR_CONFIDENCE else asrConfidence) * (1.0 - slotPenalty)
      return Parsed(cmd, transcript, conf.coerceIn(0.0, 1.0))
    }
    return null
  }

  /**
   * The recogniser returns several hypotheses. Take the first that parses: hypotheses are ordered
   * by the recogniser's own confidence, and a lower-ranked one that *parses* beats a top one that doesn't.
   */
  fun parseBest(hypotheses: List<String>, confidences: List<Float>? = null): Parsed? =
      hypotheses.withIndex().firstNotNullOfOrNull { (i, h) ->
        parse(h, confidences?.getOrNull(i)?.toDouble() ?: -1.0)
      }

  private const val UNKNOWN_ASR_CONFIDENCE = 0.8

  /** Polite or filler openers people put in front of a command. Stripped repeatedly. */
  private val FILLER =
      Regex(
          "^(?:please|pls|kindly|can you|could you|would you|will you|hey|hi|okay|ok|um|uh|so|now|and|then|" +
              "alright|right|let's|lets|go ahead and|i want to|i'd like to|i would like to|i need to|just|also)\\b\\s*",
      )

  internal fun normalize(raw: String): String {
    var s =
        raw.lowercase()
            .replace(Regex("[’']"), "'")
            .replace(Regex("[^a-z0-9': .-]"), " ")
            .replace(Regex("(?<=[a-z])[.,:;](?=\\s|$)"), " ")
            .replace(":", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    while (true) {
      val next = s.replace(FILLER, "").trim()
      if (next == s) break
      s = next
    }
    return s.removeSuffix(" please").trim()
  }
}

/** Rooms and surfaces a restoration tech names, with the variants speech recognisers produce. */
object Vocabulary {
  private val rooms: List<Pair<String, List<String>>> =
      listOf(
          "master bathroom" to listOf("master bathroom", "master bath", "primary bathroom", "primary bath", "ensuite", "en suite"),
          "bathroom" to listOf("bathroom", "bath room", "bath", "restroom", "washroom", "toilet", "powder room", "half bath"),
          "master bedroom" to listOf("master bedroom", "primary bedroom", "main bedroom"),
          "bedroom" to listOf("bedroom", "bed room", "guest room", "kids room", "nursery"),
          "kitchen" to listOf("kitchen", "kitchenette", "pantry"),
          "living room" to listOf("living room", "lounge", "family room", "front room", "drawing room", "hall"),
          "dining room" to listOf("dining room", "dining"),
          "hallway" to listOf("hallway", "corridor", "passage", "landing"),
          "laundry room" to listOf("laundry room", "laundry", "utility room", "utility", "wash area"),
          "basement" to listOf("basement", "cellar", "crawl space", "crawlspace"),
          "attic" to listOf("attic", "loft"),
          "garage" to listOf("garage", "car port", "carport", "workshop"),
          "closet" to listOf("closet", "wardrobe", "storage room", "store room", "storeroom", "cupboard"),
          "stairs" to listOf("stairs", "stairway", "stairwell", "staircase"),
          "entry" to listOf("entry", "entryway", "foyer", "front door", "mudroom", "mud room"),
          "office" to listOf("office", "study", "den"),
          "balcony" to listOf("balcony", "porch", "patio", "terrace", "deck"),
      )

  private val surfaces: List<Pair<String, List<String>>> =
      listOf(
          "north wall" to listOf("north wall", "northern wall"),
          "south wall" to listOf("south wall", "southern wall"),
          "east wall" to listOf("east wall", "eastern wall"),
          "west wall" to listOf("west wall", "western wall"),
          "left wall" to listOf("left wall"),
          "right wall" to listOf("right wall"),
          "back wall" to listOf("back wall", "rear wall", "far wall"),
          "front wall" to listOf("front wall"),
          "ceiling" to listOf("ceiling", "roof", "overhead"),
          "subfloor" to listOf("subfloor", "sub floor", "sub-floor"),
          "floor" to listOf("floor", "flooring", "carpet", "tiles", "tile floor", "laminate"),
          "baseboard" to listOf("baseboard", "base board", "skirting", "skirting board"),
          "cabinet" to listOf("under sink", "under the sink", "sink base", "sink cabinet", "cabinet", "cupboard", "vanity"),
          "countertop" to listOf("countertop", "counter top", "counter", "worktop"),
          "window" to listOf("window sill", "windowsill", "window frame", "window"),
          "door" to listOf("door frame", "doorframe", "door jamb", "door"),
          "wall" to listOf("wall", "walls"),
      )

  /** Matches whole words, longest phrase first; also accepts one-letter ASR typos in longer words. */
  fun findRoom(text: String): String? = find(text, rooms)?.let { withNumber(text, it) }

  fun findSurface(text: String): String? = find(text, surfaces)

  private fun find(text: String, table: List<Pair<String, List<String>>>): String? {
    val words = text.split(' ').filter { it.isNotBlank() }
    val joined = " ${words.joinToString(" ")} "
    val candidates = table.flatMap { (canon, variants) -> variants.map { canon to it } }.sortedByDescending { it.second.length }
    for ((canon, v) in candidates) if (joined.contains(" $v ")) return canon
    // Fuzzy fallback for single-word variants ("kitchin", "celing").
    for ((canon, v) in candidates) {
      if (v.contains(' ') || v.length < 5) continue
      if (words.any { it.length >= 5 && editDistance(it, v) <= 1 }) return canon
    }
    return null
  }

  /** "bedroom two" / "bedroom 2" -> "bedroom 2". */
  private fun withNumber(text: String, room: String): String {
    if (room != "bedroom" && room != "bathroom") return room
    val m = Regex("\\b(?:bed ?room|bath ?room|bath)\\s+(\\w+)").find(text) ?: return room
    val n = SpokenNumbers.parse(m.groupValues[1]) ?: return room
    return if (n == Math.floor(n) && n in 1.0..9.0) "$room ${n.toInt()}" else room
  }

  internal fun editDistance(a: String, b: String): Int {
    val dp = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      var prev = dp[0]
      dp[0] = i
      for (j in 1..b.length) {
        val tmp = dp[j]
        dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = tmp
      }
    }
    return dp[b.length]
  }
}

/** "twenty two point five" -> 22.5, "18" -> 18.0, "one hundred and five" -> 105.0. */
object SpokenNumbers {
  private val units =
      mapOf(
          "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "to" to 2, "too" to 2, "three" to 3, "four" to 4,
          "for" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
          "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
          "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
      )
  private val tens =
      mapOf(
          "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
          "eighty" to 80, "ninety" to 90,
      )

  fun parse(text: String): Double? {
    val t = text.lowercase().replace("%", " percent").replace(Regex("[^a-z0-9. ]"), " ").trim()
    Regex("^-?\\d+(?:\\.\\d+)?").find(t)?.let { return it.value.toDouble() }
    val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "and" && it != "a" }
    if (words.isEmpty()) return null
    var total = 0.0
    var current = 0.0
    var seenNumber = false
    var i = 0
    while (i < words.size) {
      val w = words[i]
      when {
        w in units -> {
          current += units.getValue(w)
          seenNumber = true
        }
        w in tens -> {
          current += tens.getValue(w)
          seenNumber = true
        }
        w == "hundred" && seenNumber -> current *= 100
        w == "thousand" && seenNumber -> {
          total += current * 1000
          current = 0.0
        }
        (w == "point" || w == "dot") && seenNumber -> {
          val decimals = words.drop(i + 1).takeWhile { it in units }.joinToString("") { units.getValue(it).toString() }
          return if (decimals.isEmpty()) total + current else (total + current) + "0.$decimals".toDouble()
        }
        else -> if (seenNumber) break else return null
      }
      i++
    }
    return if (seenNumber) total + current else null
  }
}
