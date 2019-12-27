package dev.ludorum.admob.localreward

class AdStatus(var loaded: Boolean, var failed: Boolean, var rewarded:Boolean, var closed: Boolean) {

    companion object {
        fun default(): AdStatus {
            return AdStatus(false, false, false, false)
        }
    }

    override fun toString(): String {
        return "loaded: $loaded, failed: $failed, rewarded: $rewarded, closed: $closed"
    }



    fun closedOrFailed():Boolean {
        return closed.and(failed)
    }
}