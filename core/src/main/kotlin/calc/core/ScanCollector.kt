package calc.core

/** Accumulates QR frames from the camera: single QR, animated UR (fountain) or BBQr. */
class ScanCollector {
    sealed class State {
        object Idle : State()
        data class Progress(val fraction: Double, val label: String) : State()
        data class DoneUr(val ur: UR) : State()
        data class DoneBbqr(val joiner: Bbqr.Joiner) : State()
        data class DoneText(val text: String) : State()
        data class Error(val message: String) : State()
    }
    private var ur: UrDecoder? = null
    private var bb: Bbqr.Joiner? = null
    var state: State = State.Idle; private set

    fun reset() { ur = null; bb = null; state = State.Idle }

    fun feed(raw: String): State {
        if (state is State.DoneUr || state is State.DoneBbqr || state is State.DoneText) return state
        val s = raw.trim()
        state = when {
            s.lowercase().startsWith("ur:") -> {
                val d = ur ?: UrDecoder().also { ur = it }
                d.receive(s)
                when {
                    d.result != null -> { SecretGuard.checkUrType(d.result!!.type); State.DoneUr(d.result!!) }
                    d.error != null -> { ur = null; State.Error(d.error!!) }
                    else -> State.Progress(d.progress(), "UR ${d.receivedParts}/${d.expectedParts}")
                }
            }
            Bbqr.isBbqr(s) -> {
                val j = bb ?: Bbqr.Joiner().also { bb = it }
                if (!j.add(s) && j.total == 0) State.Error("Invalid BBQr")
                else if (j.isComplete) State.DoneBbqr(j)
                else State.Progress(j.received.toDouble() / j.total, "BBQr ${j.received}/${j.total}")
            }
            else -> if (ur == null && bb == null) State.DoneText(s) else state
        }
        return state
    }
}
