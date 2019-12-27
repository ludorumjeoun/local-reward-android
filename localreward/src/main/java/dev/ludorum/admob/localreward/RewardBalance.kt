package dev.ludorum.admob.localreward

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import cc.duduhuo.util.digest.Digest
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.InterstitialAd
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.reward.RewardItem
import com.google.android.gms.ads.reward.RewardedVideoAd
import com.google.android.gms.ads.reward.RewardedVideoAdListener
import org.json.JSONArray
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

interface UserTicketListener {
    fun readyForEarnTicket()
    fun cannotReadyForEarnTicket()

    fun readyTicketForPay()
}

class RewardBalance {

    companion object {
        const val KEY_DATA = "DATA"
        const val KEY_DIGEST = "DIGEST"
    }

    val debug = BuildConfig.DEBUG
    var listener:UserTicketListener? = null
    var userId:String? = null
    var videoAdUnitId:String? = null
    var bannerAdUnitId:String? = null

    protected val context: Context
    protected val pref: SharedPreferences
    protected val rewardName: String
    protected var showAdWhenLoaded = false


    constructor(context: Context, admobAppId:String, rewardName:String) {
        this.context = context
        this.rewardName = rewardName.toUpperCase()
        this.pref = context.getSharedPreferences("AD_REWARD_${this.rewardName}", Context.MODE_PRIVATE)


        MobileAds.initialize(context, admobAppId)
        this.videoAd = MobileAds.getRewardedVideoAdInstance(context)
        this.bannerAd = InterstitialAd(context)
    }



    fun readyOrEarnTicket() {
        if (hasTicket()) {
            listener?.readyTicketForPay()
        } else {
            readyForEarnTicket()
        }
    }
    fun earnTicket() {
        showAdWhenLoaded = true
        showAdOrWait()
    }
    fun readyForEarnTicket() {
        showAdWhenLoaded = false
        readyForAds()
    }

    fun hasTicket(): Boolean {
        return getNumberOfTicket() > 0
    }

    fun getNumberOfTicket(): Int {
        var total:Int = 0;
        ticketHistory { date, value ->
            total += value
        }
        return total;
    }

    fun useTicket(): Boolean {
        if (hasTicket()) {
            putTicket(-1)
            return true
        }
        return false
    }

    fun pause() {
        this.videoAd.pause(context)
    }
    fun resume() {
        this.videoAd.resume(context)
    }
    fun destroy() {
        this.videoAd.destroy(context)
    }

    protected fun digest(json:String):String {
        return Digest.md5Hex("$rewardName|$userId|$json")
    }
    protected fun ticketHistory(cursor:(date:Long, value:Int) -> Unit) {
        val jsonString = pref.getString(KEY_DATA, "[]") ?: "[]";
        val digest = pref.getString(KEY_DIGEST, "") ?: "";
        if (digest(jsonString).equals(digest, true).not()) {
            return
        }
        val array = try {
            JSONArray(jsonString)
        } catch(e:Throwable) {
            JSONArray()
        }
        val length = array.length()
        for (i in 0 until length) {
            try {
                val dataAndValue = array.getJSONArray(i)
                val date = dataAndValue.getLong(0);
                val value = dataAndValue.getInt(1);
                if ((Date().time - date) < (86400 * 1000 * 3)) {
                    cursor(date, value)
                }
            } catch (e:Throwable) {
                e.printStackTrace()
            }
        }

    }

    @SuppressLint("ApplySharedPref")
    protected fun putTicket(amount: Int) {
        val edit = pref.edit()

        val newArray = JSONArray()
        ticketHistory { date, value ->
            newArray.put(JSONArray().put(date).put(value))
        }
        newArray.put(JSONArray().put(Date().time).put(amount))

        val newJsonString = newArray.toString()
        edit.putString(KEY_DATA, newJsonString)
        edit.putString(KEY_DIGEST, digest(newJsonString))

        edit.commit()
    }

    @SuppressLint("ApplySharedPref")
    protected fun popTicket() {
        val edit = pref.edit()

        val useAmount = 1;
        val newArray = JSONArray()
        ticketHistory { date, value ->
            newArray.put(JSONArray().put(date).put(value))
        }
        val newJsonString = newArray.toString()
        edit.putString(KEY_DATA, newJsonString)
        edit.putString(KEY_DIGEST, digest(newJsonString))

        edit.commit()
    }

    protected var videoAd: RewardedVideoAd
    protected var bannerAd: InterstitialAd
    protected val noVideoAd = AtomicBoolean(false)
    protected val noBannerAd = AtomicBoolean(false)

    protected fun readyForAds() {
        if (noVideoAd.get().not()) {
            videoAd.userId = userId
            videoAd.rewardedVideoAdListener = object : RewardedVideoAdListener {
                override fun onRewardedVideoAdLoaded() {
                    log("onRewardedVideoAdLoaded")
                    showAdOrWait()
                }

                override fun onRewardedVideoAdOpened() {
                    log("onRewardedVideoAdOpened")
                }

                override fun onRewardedVideoStarted() {
                    log("onRewardedVideoStarted")
                }

                override fun onRewardedVideoAdClosed() {
                    log("onRewardedVideoAdClosed")
                    if (hasTicket()) {
                        listener?.readyTicketForPay()
                    } else {
                        noVideoAd.set(true)
                        showAdOrWait()
                    }
                }

                override fun onRewarded(rewardItem: RewardItem) {
                    log("onRewarded(" + rewardItem.type + ", " + rewardItem.amount + ")")
                    if (rewardItem.type.equals(rewardName, ignoreCase = true)) {
                        putTicket(rewardItem.amount)
                    }
                }

                override fun onRewardedVideoAdLeftApplication() {
                    log("onRewardedVideoAdLeftApplication")
                }

                override fun onRewardedVideoAdFailedToLoad(i: Int) {
                    log("onRewardedVideoAdFailedToLoad($i)")
                    noVideoAd.set(true)
                    showAdOrWait()
                }

                override fun onRewardedVideoCompleted() {
                    log("onRewardedVideoCompleted")
                }
            }
            videoAdUnitId?.let { id -> videoAd.loadAd(id, AdRequest.Builder().build()) }

        }
        if (noBannerAd.get().not()) {
            bannerAd.setImmersiveMode(true)
            bannerAd.adListener = object : AdListener() {
                override fun onAdClosed() {
                    log("onAdClosed")
                    if (hasTicket()) {
                        listener?.readyTicketForPay()
                    } else {
                        noBannerAd.set(true)
                        showAdOrWait()
                    }
                }

                override fun onAdFailedToLoad(i: Int) {
                    log("onAdFailedToLoad($i)")
                    noBannerAd.set(true)
                    showAdOrWait()
                }

                override fun onAdLeftApplication() {
                    log("onAdLeftApplication")
                }

                override fun onAdOpened() {
                    log("onAdOpened")
                    putTicket(1)
                }

                override fun onAdLoaded() {
                    log("onAdLoaded")
                    showAdOrWait()
                }

                override fun onAdClicked() {
                    log("onAdClicked")
                }

                override fun onAdImpression() {
                    log("onAdImpression")
                }
            }
            bannerAdUnitId?.let { id ->
                bannerAd.adUnitId = id
                bannerAd.loadAd(AdRequest.Builder().build())
            }
        }
        if (noVideoAd.get() && noBannerAd.get()) {
            listener?.cannotReadyForEarnTicket()
        }
    }

    protected fun showAdOrWait() {
        log("videoAd: " + if (videoAd.isLoaded) "Loaded" else "")
        log("bannerAd: " + if (bannerAd.isLoaded) "Loaded" else "")
        log("noVideoAd: " + if (noVideoAd.get()) "Yes" else "No")
        log("noBannerAd: " + if (noBannerAd.get()) "Yes" else "No")
        /*
        - videoAd -> Loading
        - bannerAd -> Loading
        - videoAd -> Loaded or Failed
        - bannerAd -> Loaded or Failed
         */
        if (videoAd.isLoaded && !noVideoAd.get()) {
            log("videoAd.isLoaded")
            if (!showAdWhenLoaded) {
                listener?.readyForEarnTicket()
                return
            }
            videoAd.show()
            return
        }
        if (noVideoAd.get() && bannerAd.isLoaded && !noBannerAd.get()) {
            log("bannerAd.isLoaded")
            if (!showAdWhenLoaded) {
                listener?.readyForEarnTicket()
                return
            }
            bannerAd.show()
            return
        }
        if (noVideoAd.get() && noBannerAd.get()) {
            if (hasTicket()) {
                listener?.readyTicketForPay()
            } else {
                listener?.cannotReadyForEarnTicket()
            }
            return
        }
    }

    protected fun log(msg:String) {
        if (debug) {
            Log.d("AD/${rewardName}", msg)
        }
    }
}