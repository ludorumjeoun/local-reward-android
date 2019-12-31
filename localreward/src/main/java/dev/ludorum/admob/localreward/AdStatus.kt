package dev.ludorum.admob.localreward

class AdStatus(
        var loaded: Boolean = false,
        var failed: Boolean = false,
        var rewarded:Boolean = false,
        var closed: Boolean = false) {

    override fun toString(): String {
        return "loaded: $loaded, failed: $failed, rewarded: $rewarded, closed: $closed"
    }


    fun reset() {
        this.loaded = false;
        this.failed = false;
        this.rewarded = false;
        this.closed = false;
    }

    fun closedOrFailed():Boolean {
        return closed.or(failed)
    }
}