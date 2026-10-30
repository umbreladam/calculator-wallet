package calc.core

import org.junit.Assert.*
import org.junit.Test

class MarketTest {
    @Test fun krakenOhlc() {
        val body = """{"error":[],"result":{"XXBTZUSD":[[1700000000,"100.0","110.0","90.0","105.0","101.0","2.5",10],[1700000060,"105.0","106.0","104.0","104.5","105.0","1.0",3]],"last":1700000000}}"""
        val c = Market.parseKrakenOhlc(body)
        assertEquals(2, c.size); assertEquals(Candle(1700000000, 100.0, 110.0, 90.0, 105.0, 2.5), c[0])
    }
    @Test fun krakenError() { assertThrows(IllegalStateException::class.java) { Market.parseKrakenOhlc("""{"error":["EGeneral:Too many requests"]}""") } }
    @Test fun krakenTicker() {
        val t = Market.parseKrakenTicker("""{"error":[],"result":{"XXBTZUSD":{"a":["1","1","1"],"c":["110.0","0.1"],"v":["5","20.5"],"h":["111","120"],"l":["99","80"],"o":"100.0"}}}""")
        assertEquals(110.0, t.last, 0.0); assertEquals(120.0, t.high24, 0.0); assertEquals(80.0, t.low24, 0.0); assertEquals(20.5, t.vol24, 0.0); assertEquals(10.0, t.changePct, 1e-9)
    }
    @Test fun coinbaseAndBitstamp() {
        val cb = Market.parseCoinbase("[[120,1,4,2,3,5],[60,1,4,2,3,6]]")
        assertEquals(listOf(60L, 120L), cb.map { it.t }); assertEquals(Candle(60, 2.0, 4.0, 1.0, 3.0, 6.0), cb[0])
        val bs = Market.parseBitstamp("""{"data":{"ohlc":[{"timestamp":"60","open":"2","high":"4","low":"1","close":"3","volume":"6"}]}}""")
        assertEquals(Candle(60, 2.0, 4.0, 1.0, 3.0, 6.0), bs[0])
    }
    @Test fun aggregate8hFrom4h() {
        val h4 = 14400L; val base = 28800L * 1000
        val src = listOf(Candle(base, 1.0, 5.0, 0.5, 2.0, 1.0), Candle(base + h4, 2.0, 7.0, 1.5, 6.0, 2.0), Candle(base + 2 * h4, 6.0, 6.5, 5.0, 5.5, 3.0))
        val a = Market.aggregate(src, 28800)
        assertEquals(2, a.size); assertEquals(Candle(base, 1.0, 7.0, 0.5, 6.0, 3.0), a[0]); assertEquals(base + 28800, a[1].t)
    }
    @Test fun liveUpdate() {
        val c = listOf(Candle(0, 1.0, 2.0, 0.5, 1.5, 1.0))
        assertEquals(Candle(0, 1.0, 3.0, 0.5, 3.0, 1.0), Market.withLive(c, 3.0, 30, 60).last())
        val n = Market.withLive(c, 1.2, 65, 60); assertEquals(2, n.size); assertEquals(60L, n.last().t)
    }
    @Test fun sma() { assertEquals(listOf(null, 1.5, 2.5), Market.sma(listOf(1.0, 2.0, 3.0), 2)) }
}
