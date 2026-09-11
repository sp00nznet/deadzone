package net.sp00nz.deadzone

/**
 * Working out which episode a file on disk actually is.
 *
 * Local audio is named by whatever ripped it, not by the publisher:
 * "0007 - 7 The Quarry (Part 1).mp3" has to find "Ep 7: The Quarry (Part 1)". The
 * same job tools/sideload.py does on a desktop, done on the phone so that a folder
 * import needs nothing but the folder.
 *
 * Pure — no Android imports, so it runs under plain JUnit.
 */

/** A title to match against, and whatever the caller wants back when it matches. */
class Candidate<T>(val title: String, val value: T)

// "0007 - 7 Some Title" -> "Some Title". The index the ripper prepended is never in
// the feed, and left in place it drags every score down by the same amount.
private val LEADING_INDEX = Regex("""^\s*\d{1,5}\s*[-._]\s*(?:\d{1,5}\s*[-._:]?\s*)?""")
// The filler word goes; the number after it does NOT. "(Part 1)" and "(Part 2)" are
// different episodes, and swallowing the digit makes them the same string — a silent
// mis-pairing, which is the worst thing this matcher can do.
private val NOISE = Regex("""\b(ep|episode|pt|part|no)\b\.?""", RegexOption.IGNORE_CASE)

fun normalise(s: String): String {
    var t = LEADING_INDEX.replace(s, "")
    t = NOISE.replace(t, " ")
    return t.lowercase()
        // Strip accents so "Café" and "Cafe" are the same word.
        .map { if (it in ACCENTS) ACCENT_PLAIN[ACCENTS.indexOf(it)] else it }
        .joinToString("")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private const val ACCENTS = "àáâãäåèéêëìíîïòóôõöùúûüñçÿ"
private const val ACCENT_PLAIN = "aaaaaaeeeeiiiiooooouuuuncy"

// Single letters are noise; single digits are not — a lone "1" is what separates
// part one from part two.
private fun tokens(s: String) =
    normalise(s).split(' ').filter { it.length > 1 || it.firstOrNull()?.isDigit() == true }.toSet()

private fun String.isNumeric() = all(Char::isDigit)

/**
 * Matches a batch of filenames to a batch of episode titles, one episode per file.
 *
 * ponytail: an inverted index, not every-file-against-every-title. A 23k-file library
 * against an 800-episode feed is 18 million string comparisons done naively, on a
 * phone; scoring only the episodes that share a word with the filename is the
 * difference between seconds and minutes. Swap in proper edit distance if token
 * overlap ever proves too blunt.
 */
fun <T> matchAll(
    files: List<String>,
    candidates: List<Candidate<T>>,
    threshold: Double = 0.55,
): Map<String, T> {
    if (files.isEmpty() || candidates.isEmpty()) return emptyMap()

    val candTokens = candidates.map { tokens(it.title) }
    val index = HashMap<String, MutableList<Int>>()
    for ((i, ts) in candTokens.withIndex()) {
        for (t in ts) index.getOrPut(t) { mutableListOf() }.add(i)
    }

    val taken = HashSet<Int>()
    val out = LinkedHashMap<String, T>()

    // Score every file first, then hand out episodes best-score-first. Going in file
    // order lets a mediocre early match steal an episode that a later file matches
    // exactly — which is how you end up with one wrong pairing cascading into ten.
    data class Scored<T>(val file: String, val idx: Int, val score: Double)
    val scored = ArrayList<Scored<T>>()

    for (f in files) {
        val ft = tokens(f)
        if (ft.isEmpty()) continue
        val seen = HashMap<Int, Int>()
        val seenWords = HashMap<Int, Int>()
        for (t in ft) index[t]?.forEach { i ->
            seen[i] = (seen[i] ?: 0) + 1
            if (!t.isNumeric()) seenWords[i] = (seenWords[i] ?: 0) + 1
        }
        var bestIdx = -1
        var best = 0.0
        for ((i, shared) in seen) {
            // Numbers disambiguate, they do not identify. Every show has an
            // "Episode 12", so agreeing on digits alone is not evidence that two
            // things are the same episode — especially matching across the whole
            // library rather than within one feed.
            if ((seenWords[i] ?: 0) == 0) continue
            // Overlap against the longer of the two, so a one-word filename cannot
            // score 1.0 against a ten-word title by accident.
            val score = shared.toDouble() / maxOf(ft.size, candTokens[i].size)
            if (score > best) { best = score; bestIdx = i }
        }
        if (bestIdx >= 0 && best >= threshold) scored += Scored(f, bestIdx, best)
    }

    for (s in scored.sortedByDescending { it.score }) {
        if (taken.add(s.idx)) out[s.file] = candidates[s.idx].value
    }
    return out
}
