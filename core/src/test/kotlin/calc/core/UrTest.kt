package calc.core

import org.junit.Assert.*
import org.junit.Test

class UrTest {
    private fun msg(n: Int, seed: String = "Wolf") = Xoshiro256(seed).nextData(n)

    @Test fun crc32() { assertEquals("ebe6c6e6", UrUtil.hex(UrUtil.u32be(UrUtil.crc32("Hello, world!".toByteArray())))); assertEquals("598c84dc", UrUtil.hex(UrUtil.u32be(UrUtil.crc32("Wolf".toByteArray())))) }

    @Test fun bytewords() {
        val input = byteArrayOf(0, 1, 2, 128.toByte(), 255.toByte())
        assertEquals("aeadaolazmjendeoti", Bytewords.encodeMinimal(input))
        assertArrayEquals(input, Bytewords.decodeMinimal("aeadaolazmjendeoti"))
        assertThrows(IllegalArgumentException::class.java) { Bytewords.decodeMinimal("aeadaolazojendeowf") }
    }

    @Test fun shuffle() {
        val rng = Xoshiro256("Wolf"); val v = (1..10).toList()
        val r = (0 until 3).map { Fountain.shuffled(v, rng) }
        assertEquals(listOf(6, 4, 9, 3, 10, 5, 7, 8, 1, 2), r[0])
        assertEquals(listOf(10, 8, 6, 5, 1, 2, 3, 9, 7, 4), r[1])
        assertEquals(listOf(6, 4, 5, 8, 9, 3, 2, 1, 7, 10), r[2])
    }

    @Test fun chooseDegree() {
        val m = msg(1024); val fl = Fountain.nominalFragmentLength(m.size, 10, 100); val frags = Fountain.partition(m, fl)
        val degrees = (1..20).map { Fountain.chooseDegree(frags.size, Xoshiro256("Wolf-$it")) }
        assertEquals(listOf(11, 3, 6, 5, 2, 1, 2, 11, 1, 3, 9, 10, 10, 4, 2, 1, 1, 2, 1, 1), degrees)
    }

    @Test fun chooseFragments() {
        val m = msg(1024); val cs = UrUtil.crc32(m); val fl = Fountain.nominalFragmentLength(m.size, 10, 100); val n = Fountain.partition(m, fl).size
        assertEquals(setOf(2, 5, 6, 8, 9, 10), Fountain.chooseFragments(13, n, cs))
        assertEquals(setOf(0, 1, 3, 4, 5, 7, 9, 10), Fountain.chooseFragments(21, n, cs))
        assertEquals(setOf(5, 6), Fountain.chooseFragments(29, n, cs))
    }

    @Test fun fountainEncoderCbor() {
        val enc = FountainEncoder(msg(256), 30)
        val parts = (0 until 20).map { UrUtil.hex(enc.nextPart().cbor()) }
        assertEquals("8501091901001a0167aa07581d916ec65cf77cadf55cd7f9cda1a1030026ddd42e905b77adc36e4f2d3c", parts[0])
        assertEquals("850a091901001a0167aa07581d330f0f33a05eead4f331df229871bee733b50de71afd2e5a79f196de09", parts[9])
        assertEquals("8514091901001a0167aa07581de055c2433562184fa71b4be94f262e200f01c6f74c284b0dc6fae6673f", parts[19])
    }

    @Test fun singlePartUr() {
        val ur = UR("bytes", Cbor.encodeBytes(msg(50)))
        val e = UrEncoder.encodeSingle(ur)
        assertEquals("ur:bytes/hdeymejtswhhylkepmykhhtsytsnoyoyaxaedsuttydmmhhpktpmsrjtgwdpfnsboxgwlbaawzuefywkdplrsrjynbvygabwjldapfcsdwkbrkch", e)
        assertArrayEquals(ur.cbor, UrDecoder.decodeSingle(e).cbor)
    }

    @Test fun urEncoderVectors() {
        val enc = UrEncoder(UR("bytes", Cbor.encodeBytes(msg(256))), 30)
        val parts = (0 until 20).map { enc.nextPart() }
        assertEquals("ur:bytes/1-9/lpadascfadaxcywenbpljkhdcahkadaemejtswhhylkepmykhhtsytsnoyoyaxaedsuttydmmhhpktpmsrjtdkgslpgh", parts[0])
        assertEquals("ur:bytes/9-9/lpasascfadaxcywenbpljkhdcajskecpmdckihdyhphfotjojtfmlnwmadspaxrkytbztpbauotbgtgtaeaevtgavtny", parts[8])
        assertEquals("ur:bytes/20-9/lpbbascfadaxcywenbpljkhdcayapmrleeleaxpasfrtrdkncffwjyjzgyetdmlewtkpktgllepfrltataztksmhkbot", parts[19])
    }

    @Test fun multipartRoundTripLarge() {
        val ur = UR("bytes", Cbor.encodeBytes(msg(32767)))
        val enc = UrEncoder(ur, 1000, 100); val dec = UrDecoder()
        var n = 0
        while (!dec.isComplete) { dec.receive(enc.nextPart()); n++; assertTrue(n < 1000) }
        assertArrayEquals(ur.cbor, dec.result!!.cbor)
    }

    @Test fun multipartWithLoss() {
        // drop every other frame (simulates a camera missing frames); fountain parts must still reconstruct
        val psbt = msg(1800, "psbt")
        val enc = PsbtExport.urParts(psbt, 120); val dec = UrDecoder(); var i = 0
        while (!dec.isComplete) { val p = enc.nextPart(); if (i++ % 2 == 0) continue; dec.receive(p.uppercase()); assertTrue(i < 5000) }
        assertEquals("crypto-psbt", dec.result!!.type)
        assertArrayEquals(psbt, (SignedPayload.fromUr(dec.result!!) as SignedPayload.Psbt).bytes.let { if (SignedPayload.isPsbt(it)) it else it })
    }
}
