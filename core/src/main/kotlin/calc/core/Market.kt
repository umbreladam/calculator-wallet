package calc.core

import org.json.JSONArray
import org.json.JSONObject

/** One OHLCV candle. [t] is the open time in unix seconds. */
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

data class Ticker(val last: Double, val open24: Double, val high24: Double, val low24: Double, val vol24: Double) {
    val changePct get() = if (open24 > 0) (last - open24) / open24 * 100 else 0.0
}

/** Chart timeframes. [kraken] is the Kraken interval in minutes; [group] merges that many candles (Kraken has no 8h). */
enum class Timeframe(val label: String, val seconds: Long, val kraken: Int, val group: Int) {
    LINE("Line", 900, 15, 1), M1("1m", 60, 1, 1), M15("15m", 900, 15, 1), H8("8h", 28800, 240, 2), D1("1d", 86400, 1440, 1), W1("1w", 604800, 10080, 1);
}

/** Parsing of public market-data responses (no wallet data is ever involved). */
object Market {
    fun krakenOhlcUrl(tf: Timeframe) = "https://api.kraken.com/0/public/OHLC?pair=XBTUSD&interval=${tf.kraken}"
    const val KRAKEN_TICKER = "https://api.kraken.com/0/public/Ticker?pair=XBTUSD"
    fun coinbaseUrl(tf: Timeframe): String? {
        val g = when (tf.seconds) { 60L -> 60; 900L -> 900; 86400L -> 86400; else -> return null }
        return "https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=$g"
    }
    fun bitstampUrl(tf: Timeframe) = "https://www.bitstamp.net/api/v2/ohlc/btcusd/?limit=300&step=" +
        (when (tf) { Timeframe.H8 -> 14400; Timeframe.W1 -> 259200; else -> tf.seconds })

    private fun krakenResult(body: String): JSONObject {
        val o = JSONObject(body)
        val err = o.optJSONArray("error")
        if (err != null && err.length() > 0) throw IllegalStateException("Kraken: " + err.join(","))
        return o.getJSONObject("result")
    }

    fun parseKrakenOhlc(body: String): List<Candle> {
        val r = krakenResult(body)
        val key = r.keys().asSequence().first { it != "last" }
        val a = r.getJSONArray(key)
        return (0 until a.length()).map { i -> val c = a.getJSONArray(i)
            Candle(c.getLong(0), c.getString(1).toDouble(), c.getString(2).toDouble(), c.getString(3).toDouble(), c.getString(4).toDouble(), c.getString(6).toDouble()) }
    }

    fun parseKrakenTicker(body: String): Ticker {
        val r = krakenResult(body); val t = r.getJSONObject(r.keys().next())
        return Ticker(t.getJSONArray("c").getString(0).toDouble(), t.getString("o").toDouble(),
            t.getJSONArray("h").getString(1).toDouble(), t.getJSONArray("l").getString(1).toDouble(), t.getJSONArray("v").getString(1).toDouble())
    }

    /** Coinbase: [[time, low, high, open, close, volume], ...] newest first. */
    fun parseCoinbase(body: String): List<Candle> {
        val a = JSONArray(body)
        return (0 until a.length()).map { i -> val c = a.getJSONArray(i)
            Candle(c.getLong(0), c.getDouble(3), c.getDouble(2), c.getDouble(1), c.getDouble(4), c.getDouble(5)) }.sortedBy { it.t }
    }

    fun parseBitstamp(body: String): List<Candle> {
        val a = JSONObject(body).getJSONObject("data").getJSONArray("ohlc")
        return (0 until a.length()).map { i -> val c = a.getJSONObject(i)
            Candle(c.getString("timestamp").toLong(), c.getString("open").toDouble(), c.getString("high").toDouble(), c.getString("low").toDouble(), c.getString("close").toDouble(), c.getString("volume").toDouble()) }.sortedBy { it.t }
    }

    /** Merge candles into buckets of [seconds] aligned to the epoch (e.g. 4h -> 8h). */
    fun aggregate(src: List<Candle>, seconds: Long): List<Candle> =
        src.sortedBy { it.t }.groupBy { it.t / seconds * seconds }.map { (t, g) ->
            Candle(t, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.sumOf { it.v }) }

    /** Fold a live trade price into the last candle (or start a new one if its period has passed). */
    fun withLive(src: List<Candle>, price: Double, nowSec: Long, seconds: Long): List<Candle> {
        if (src.isEmpty()) return src
        val last = src.last(); val start = nowSec / seconds * seconds
        return if (start > last.t) src + Candle(start, last.c, maxOf(last.c, price), minOf(last.c, price), price, 0.0)
        else src.dropLast(1) + last.copy(h = maxOf(last.h, price), l = minOf(last.l, price), c = price)
    }

    fun sma(v: List<Double>, n: Int): List<Double?> = v.indices.map { i -> if (i + 1 < n) null else v.subList(i + 1 - n, i + 1).average() }
}
