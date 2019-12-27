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

interface RewardBalanceListener {
    fun onReadyForRewardEarning()
    fun onFailedReadyForRewardEarning()

    fun onReadyToPayWithReward()
}

class RewardBalance {

    companion object {
        const val KEY_DATA = "DATA"
        const val KEY_DIGEST = "DIGEST"
    }

    val debug = BuildConfig.DEBUG
    var listener:RewardBalanceListener? = null
    var userId:String? = null
    var videoAdUnitId:String? = null
    var bannerAdUnitId:String? = null
    var rewardExpiryHour: Int = 24;

    protected val context: Context
    protected val pref: SharedPreferences
    protected val rewardName: String
    protected var showAdWhenLoaded = false

    protected var todayRewardCount = 0;
    protected var todayPaymentCount = 0;



    constructor(context: Context, admobAppId:String, rewardName:String) {
        this.context = context
        this.rewardName = rewardName.toUpperCase()
        this.pref = context.getSharedPreferences("AD_REWARD_${this.rewardName}", Context.MODE_PRIVATE)


        MobileAds.initialize(context, admobAppId)
        this.videoAd = MobileAds.getRewardedVideoAdInstance(context)
        this.bannerAd = InterstitialAd(context)
    }



    fun hasRewardOrEarnReward() {
        if (hasReward()) {
            listener?.onReadyToPayWithReward()
        } else {
            readyForEarnReward()
        }
    }
    fun earnReward() {
        showAdWhenLoaded = true
        showAdOrWait()
    }
    fun readyForEarnReward() {
        showAdWhenLoaded = false
        readyForAds()
    }

    fun hasReward(): Boolean {
        return getNumberOfReward() > 0
    }

    fun getNumberOfReward(): Int {
        var total:Int = 0;
        rewardHistory { date, reward, used, remain->
            total += remain
        }
        return total;
    }

    fun pay(): Boolean {
        if (hasReward()) {
            useReward()
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
    protected fun rewardHistory(cursor:(date:Long, numberOfUsed:Int, numberOfReward:Int, numberOfRemain: Int) -> Unit) {
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
                val reward = dataAndValue.getInt(1);
                val used = dataAndValue.getInt(2);
                val remain = reward - used;
                if (remain > 0 && (Date().time - date) < (3600 * 1000 * rewardExpiryHour)) {
                    cursor(date, remain, reward, used);
                }
            } catch (e:Throwable) {
                e.printStackTrace()
            }
        }

    }

    @SuppressLint("ApplySharedPref")
    protected fun putReward(amount: Int) {
        val edit = pref.edit()

        val newArray = JSONArray()
        rewardHistory { date, reward, used, remain->
            newArray.put(JSONArray().put(date).put(reward).put(used))
        }
        newArray.put(JSONArray().put(Date().time).put(amount).put(0))

        val newJsonString = newArray.toString()
        edit.putString(KEY_DATA, newJsonString)
        edit.putString(KEY_DIGEST, digest(newJsonString))

        edit.commit()
    }

    @SuppressLint("ApplySharedPref")
    protected fun useReward() {
        val edit = pref.edit()

        val newArray = JSONArray()
        rewardHistory { date, reward, used, remain->
            newArray.put(JSONArray().put(date).put(reward).put(used + 1))
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
                    if (hasReward()) {
                        listener?.onReadyToPayWithReward()
                    } else {
                        noVideoAd.set(true)
                        showAdOrWait()
                    }
                }

                override fun onRewarded(rewardItem: RewardItem) {
                    log("onRewarded(" + rewardItem.type + ", " + rewardItem.amount + ")")
                    if (rewardItem.type.equals(rewardName, ignoreCase = true)) {
                        putReward(rewardItem.amount)
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
                    if (hasReward()) {
                        listener?.onReadyToPayWithReward()
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
                    putReward(1)
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
            listener?.onFailedReadyForRewardEarning()
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
                listener?.onReadyForRewardEarning()
                return
            }
            videoAd.show()
            return
        }
        if (noVideoAd.get() && bannerAd.isLoaded && !noBannerAd.get()) {
            log("bannerAd.isLoaded")
            if (!showAdWhenLoaded) {
                listener?.onReadyForRewardEarning()
                return
            }
            bannerAd.show()
            return
        }
        if (noVideoAd.get() && noBannerAd.get()) {
            if (hasReward()) {
                listener?.onReadyToPayWithReward()
            } else {
                listener?.onFailedReadyForRewardEarning()
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