package calc.core

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.Deflater

class BbqrTest {
    @Test fun base32Vectors() {
        assertEquals("MZXW6YTBOI", Bbqr.base32("foobar".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), Bbqr.unbase32("MZXW6YTBOI"))
        assertEquals("MY", Bbqr.base32("f".toByteArray()))
    }
    @Test fun headerFormat() {
        val parts = Bbqr.split(ByteArray(1000) { it.toByte() }, 'P', 400)
        assertEquals(4, parts.size) // 1000 bytes -> 1600 base32 chars -> 400/part
        assertTrue(parts[0].startsWith("B$2P0400")); assertTrue(parts[3].startsWith("B$2P0403"))
        assertEquals("1Z", Bbqr.b36(71)); assertEquals(71, Bbqr.unb36("1Z"))
    }
    @Test fun roundTripShuffled() {
        for (size in listOf(1, 7, 399, 4096, 20000)) {
            val data = Xoshiro256("bbqr$size").nextData(size)
            val parts = Bbqr.split(data, 'P', 312).shuffled()
            val j = Bbqr.Joiner(); parts.forEach { assertTrue(j.add(it)); j.add(it) }
            assertTrue(j.isComplete); assertArrayEquals(data, j.result()); assertEquals('P', j.fileType)
        }
    }
    @Test fun decodeZlibAndHex() {
        val data = ("70736274ff" + "00".repeat(300)).toByteArray()
        val d = Deflater(Deflater.BEST_COMPRESSION, true); d.setInput(data); d.finish()
        val buf = ByteArray(4096); val n = d.deflate(buf)
        val body = Bbqr.base32(buf.copyOf(n))
        val j = Bbqr.Joiner(); assertTrue(j.add("B\$ZU0100$body")); assertArrayEquals(data, j.result())
        val h = Bbqr.Joiner(); assertTrue(h.add("B\$HT0100deadbeef")); assertArrayEquals(UrUtil.unhex("deadbeef"), h.result())
    }
    @Test fun rejectsMismatch() {
        val j = Bbqr.Joiner(); assertTrue(j.add("B$2P0200AAAA")); assertFalse(j.add("B$2T0201AAAA")); assertFalse(j.add("hello"))
    }
}

class ScanCollectorTest {
    @Test fun collectsMixedOrder() {
        val data = Xoshiro256("scan").nextData(3000)
        val c = ScanCollector(); val parts = Bbqr.split(data, 'P', 400)
        parts.reversed().forEach { c.feed(it) }
        val st = c.state as ScanCollector.State.DoneBbqr
        assertArrayEquals(data, st.joiner.result())
        val c2 = ScanCollector(); val enc = PsbtExport.urParts(data, 150)
        while (c2.state !is ScanCollector.State.DoneUr) c2.feed(enc.nextPart().uppercase())
        assertArrayEquals(data, Cbor.decode((c2.state as ScanCollector.State.DoneUr).ur.cbor) as ByteArray)
        assertTrue(ScanCollector().feed("bc1qxyz") is ScanCollector.State.DoneText)
    }
}
