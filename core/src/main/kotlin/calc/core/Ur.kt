package calc.core

import java.security.MessageDigest
import java.util.zip.CRC32

/** Blockchain Commons UR (BCR-2020-005) + multipart fountain codes (BCR-2020-012), ported from bc-ur reference. */
object UrUtil {
    fun crc32(b: ByteArray): Long = CRC32().apply { update(b) }.value
    fun u32be(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    fun unhex(s: String): ByteArray {
        val c = s.trim(); require(c.length % 2 == 0) { "odd hex" }
        return ByteArray(c.length / 2) { c.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

class Xoshiro256(seed: ByteArray) {
    private val s = LongArray(4)
    init {
        val d = UrUtil.sha256(seed)
        for (i in 0 until 4) { var v = 0L; for (n in 0 until 8) { v = (v shl 8) or (d[i * 8 + n].toLong() and 0xff) }; s[i] = v }
    }
    constructor(seed: String) : this(seed.toByteArray())
    private fun rotl(x: Long, k: Int) = (x shl k) or (x ushr (64 - k))
    fun next(): Long {
        val result = rotl(s[1] * 5, 7) * 9
        val t = s[1] shl 17
        s[2] = s[2] xor s[0]; s[3] = s[3] xor s[1]; s[1] = s[1] xor s[2]; s[0] = s[0] xor s[3]
        s[2] = s[2] xor t; s[3] = rotl(s[3], 45)
        return result
    }
    fun nextDouble(): Double {
        // unsigned 64-bit to double, divided by 2^64 (matches C++ (double)next()/ (UINT64_MAX+1.0))
        val n = next()
        val u = if (n >= 0) n.toDouble() else (((n ushr 1) or (n and 1L)).toDouble() * 2.0)
        return u / 18446744073709551616.0
    }
    fun nextInt(low: Long, high: Long): Long = (nextDouble() * (high - low + 1)).toLong() + low
    fun nextByte(): Byte = nextInt(0, 255).toByte()
    fun nextData(n: Int) = ByteArray(n) { nextByte() }
}

class RandomSampler(probs: List<Double>) {
    private val probsT: DoubleArray
    private val aliases: IntArray
    init {
        val sum = probs.sum(); val n = probs.size
        val p = DoubleArray(n) { probs[it] * n / sum }
        val small = ArrayList<Int>(); val large = ArrayList<Int>()
        for (i in n - 1 downTo 0) if (p[i] < 1) small.add(i) else large.add(i)
        val pr = DoubleArray(n); val al = IntArray(n)
        while (small.isNotEmpty() && large.isNotEmpty()) {
            val a = small.removeAt(small.size - 1); val g = large.removeAt(large.size - 1)
            pr[a] = p[a]; al[a] = g; p[g] += p[a] - 1
            if (p[g] < 1) small.add(g) else large.add(g)
        }
        while (large.isNotEmpty()) pr[large.removeAt(large.size - 1)] = 1.0
        while (small.isNotEmpty()) pr[small.removeAt(small.size - 1)] = 1.0
        probsT = pr; aliases = al
    }
    fun next(rng: () -> Double): Int {
        val r1 = rng(); val r2 = rng(); val i = (probsT.size.toDouble() * r1).toInt()
        return if (r2 < probsT[i]) i else aliases[i]
    }
}

object Fountain {
    fun <T> shuffled(items: List<T>, rng: Xoshiro256): List<T> {
        val rem = items.toMutableList(); val out = ArrayList<T>()
        while (rem.isNotEmpty()) { val idx = rng.nextInt(0, (rem.size - 1).toLong()).toInt(); out.add(rem.removeAt(idx)) }
        return out
    }
    fun chooseDegree(seqLen: Int, rng: Xoshiro256): Int =
        RandomSampler((1..seqLen).map { 1.0 / it }).next { rng.nextDouble() } + 1
    fun chooseFragments(seqNum: Long, seqLen: Int, checksum: Long): Set<Int> {
        if (seqNum <= seqLen) return setOf((seqNum - 1).toInt())
        val rng = Xoshiro256(UrUtil.u32be(seqNum) + UrUtil.u32be(checksum))
        val degree = chooseDegree(seqLen, rng)
        return shuffled((0 until seqLen).toList(), rng).take(degree).toSortedSet()
    }
    fun nominalFragmentLength(messageLen: Int, minLen: Int, maxLen: Int): Int {
        val maxCount = maxOf(1, messageLen / minLen)
        var len = messageLen
        for (count in 1..maxCount) { len = Math.ceil(messageLen.toDouble() / count).toInt(); if (len <= maxLen) break }
        return len
    }
    fun partition(message: ByteArray, fragLen: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>(); var i = 0
        while (i < message.size) { val f = ByteArray(fragLen); System.arraycopy(message, i, f, 0, minOf(fragLen, message.size - i)); out.add(f); i += fragLen }
        return out
    }
}

class FountainPart(val seqNum: Long, val seqLen: Int, val messageLen: Int, val checksum: Long, val data: ByteArray) {
    fun cbor(): ByteArray = Cbor.Writer().apply {
        array(5); uint(seqNum); uint(seqLen.toLong()); uint(messageLen.toLong()); uint(checksum); bytes(data)
    }.toByteArray()
    companion object {
        fun fromCbor(b: ByteArray): FountainPart {
            val r = Cbor.Reader(b)
            val arr = r.read() as? List<*> ?: error("bad part")
            require(arr.size == 5) { "bad part" }
            return FountainPart((arr[0] as Long), (arr[1] as Long).toInt(), (arr[2] as Long).toInt(), arr[3] as Long, arr[4] as ByteArray)
        }
    }
}

class FountainEncoder(message: ByteArray, maxFragmentLen: Int, firstSeqNum: Long = 0, minFragmentLen: Int = 10) {
    val messageLen = message.size
    val checksum = UrUtil.crc32(message)
    val fragmentLen = Fountain.nominalFragmentLength(messageLen, minFragmentLen, maxFragmentLen)
    val fragments = Fountain.partition(message, fragmentLen)
    var seqNum = firstSeqNum; private set
    val seqLen get() = fragments.size
    val isSinglePart get() = seqLen == 1
    fun nextPart(): FountainPart {
        seqNum += 1
        val idx = Fountain.chooseFragments(seqNum, seqLen, checksum)
        val mixed = ByteArray(fragmentLen)
        for (i in idx) for (j in 0 until fragmentLen) mixed[j] = (mixed[j].toInt() xor fragments[i][j].toInt()).toByte()
        return FountainPart(seqNum, seqLen, messageLen, checksum, mixed)
    }
}

class FountainDecoder {
    private class Part(val indexes: Set<Int>, val data: ByteArray) { val isSimple get() = indexes.size == 1; val index get() = indexes.first() }
    private val received = HashSet<Int>()
    private val simple = HashMap<Set<Int>, Part>()
    private var mixed = HashMap<Set<Int>, Part>()
    private val queue = ArrayDeque<Part>()
    private var expected: Set<Int>? = null
    private var expMsgLen = 0; private var expChecksum = 0L; private var expFragLen = 0
    var result: ByteArray? = null; private set
    var error: String? = null; private set
    var processed = 0; private set
    val isComplete get() = result != null || error != null
    val expectedCount get() = expected?.size ?: 0
    val receivedCount get() = received.size
    fun progress(): Double = when { isComplete -> 1.0; expected == null -> 0.0; else -> minOf(0.99, processed / (expectedCount * 1.75)) }

    fun receive(p: FountainPart): Boolean {
        if (isComplete) return false
        if (expected == null) {
            expected = (0 until p.seqLen).toSet(); expMsgLen = p.messageLen; expChecksum = p.checksum; expFragLen = p.data.size
        } else if (expectedCount != p.seqLen || expMsgLen != p.messageLen || expChecksum != p.checksum || expFragLen != p.data.size) return false
        queue.addLast(Part(Fountain.chooseFragments(p.seqNum, p.seqLen, p.checksum), p.data))
        while (!isComplete && queue.isNotEmpty()) {
            val part = queue.removeFirst()
            if (part.isSimple) processSimple(part) else processMixed(part)
        }
        processed++
        return true
    }
    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }
    private fun reduce(a: Part, b: Part): Part =
        if (a.indexes != b.indexes && a.indexes.containsAll(b.indexes)) Part(a.indexes - b.indexes, xor(a.data, b.data)) else a
    private fun reduceMixedBy(p: Part) {
        val nm = HashMap<Set<Int>, Part>()
        for (m in mixed.values) { val r = reduce(m, p); if (r.isSimple) queue.addLast(r) else nm[r.indexes] = r }
        mixed = nm
    }
    private fun processSimple(p: Part) {
        val fi = p.index
        if (fi in received) return
        simple[p.indexes] = p; received.add(fi)
        if (received == expected) {
            val msg = ByteArray(expFragLen * expectedCount)
            for (part in simple.values) System.arraycopy(part.data, 0, msg, part.index * expFragLen, expFragLen)
            val m = msg.copyOf(expMsgLen)
            if (UrUtil.crc32(m) == expChecksum) result = m else error = "Invalid checksum"
        } else reduceMixedBy(p)
    }
    private fun processMixed(p: Part) {
        if (mixed.containsKey(p.indexes)) return
        var p2 = p
        for (s in simple.values) p2 = reduce(p2, s)
        for (m in mixed.values) p2 = reduce(p2, m)
        if (p2.isSimple) queue.addLast(p2) else { reduceMixedBy(p2); mixed[p2.indexes] = p2 }
    }
}

object Bytewords {
    private const val W = "ableacidalsoapexaquaarchatomauntawayaxisbackbaldbarnbeltbetabiasbluebodybragbrewbulbbuzzcalmcashcatschefcityclawcodecolacookcostcruxcurlcuspcyandarkdatadaysdelidicedietdoordowndrawdropdrumdulldutyeacheasyechoedgeepicevenexamexiteyesfactfairfernfigsfilmfishfizzflapflewfluxfoxyfreefrogfuelfundgalagamegeargemsgiftgirlglowgoodgraygrimgurugushgyrohalfhanghardhawkheathelphighhillholyhopehornhutsicedideaidleinchinkyintoirisironitemjadejazzjoinjoltjowljudojugsjumpjunkjurykeepkenokeptkeyskickkilnkingkitekiwiknoblamblavalazyleaflegsliarlimplionlistlogoloudloveluaulucklungmainmanymathmazememomenumeowmildmintmissmonknailnavyneednewsnextnoonnotenumbobeyoboeomitonyxopenovalowlspaidpartpeckplaypluspoempoolposepuffpumapurrquadquizraceramprealredorichroadrockroofrubyruinrunsrustsafesagascarsetssilkskewslotsoapsolosongstubsurfswantacotasktaxitenttiedtimetinytoiltombtoystriptunatwinuglyundouniturgeuservastveryvetovialvibeviewvisavoidvowswallwandwarmwaspwavewaxywebswhatwhenwhizwolfworkyankyawnyellyogayurtzapszerozestzinczonezoom"
    private val minimalIndex: Map<String, Int> = (0 until 256).associateBy { "" + W[it * 4] + W[it * 4 + 3] }
    fun encodeMinimal(data: ByteArray): String {
        val full = data + UrUtil.u32be(UrUtil.crc32(data))
        val sb = StringBuilder()
        for (b in full) { val i = b.toInt() and 0xff; sb.append(W[i * 4]).append(W[i * 4 + 3]) }
        return sb.toString()
    }
    fun decodeMinimal(s: String): ByteArray {
        val t = s.lowercase(); require(t.length % 2 == 0 && t.length >= 10) { "Invalid Bytewords" }
        val out = ByteArray(t.length / 2) { (minimalIndex[t.substring(it * 2, it * 2 + 2)] ?: throw IllegalArgumentException("Invalid Bytewords")).toByte() }
        val body = out.copyOf(out.size - 4)
        require(out.copyOfRange(out.size - 4, out.size).contentEquals(UrUtil.u32be(UrUtil.crc32(body)))) { "Invalid Bytewords checksum" }
        return body
    }
}

data class UR(val type: String, val cbor: ByteArray)

class UrEncoder(val ur: UR, maxFragmentLen: Int, firstSeqNum: Long = 0, minFragmentLen: Int = 10) {
    private val fe = FountainEncoder(ur.cbor, maxFragmentLen, firstSeqNum, minFragmentLen)
    val isSinglePart get() = fe.isSinglePart
    val seqLen get() = fe.seqLen
    fun nextPart(): String =
        if (fe.isSinglePart) encodeSingle(ur)
        else { val p = fe.nextPart(); "ur:${ur.type}/${p.seqNum}-${p.seqLen}/${Bytewords.encodeMinimal(p.cbor())}" }
    companion object {
        fun encodeSingle(ur: UR) = "ur:${ur.type}/${Bytewords.encodeMinimal(ur.cbor)}"
    }
}

class UrDecoder {
    private val fd = FountainDecoder()
    private var expectedType: String? = null
    var result: UR? = null; private set
    var error: String? = null; private set
    val isComplete get() = result != null || error != null
    fun progress() = if (result != null) 1.0 else fd.progress()
    val expectedParts get() = fd.expectedCount
    val receivedParts get() = fd.receivedCount

    fun receive(s: String): Boolean = try {
        if (result != null) false else {
            val lower = s.trim().lowercase()
            require(lower.startsWith("ur:"))
            val comps = lower.substring(3).split('/')
            require(comps.size >= 2)
            val type = comps[0]
            require(type.matches(Regex("[a-z0-9-]+")))
            if (expectedType == null) expectedType = type else if (expectedType != type) throw IllegalArgumentException()
            if (comps.size == 2) { result = UR(type, Bytewords.decodeMinimal(comps[1])); true }
            else {
                require(comps.size == 3)
                val seq = comps[1].split('-'); require(seq.size == 2)
                val sn = seq[0].toLong(); val sl = seq[1].toInt(); require(sn >= 1 && sl >= 1)
                val part = FountainPart.fromCbor(Bytewords.decodeMinimal(comps[2]))
                if (part.seqNum != sn || part.seqLen != sl) false
                else if (!fd.receive(part)) false
                else {
                    fd.result?.let { result = UR(type, it) }
                    fd.error?.let { error = it }
                    true
                }
            }
        }
    } catch (e: Exception) { false }

    companion object {
        fun decodeSingle(s: String): UR { val d = UrDecoder(); require(d.receive(s) && d.result != null) { "Invalid UR" }; return d.result!! }
    }
}
