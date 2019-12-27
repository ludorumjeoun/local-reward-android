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

interface RewardBalanceListener {
    fun onReadyForRewardEarning()
    fun onFailedReadyForRewardEarning()

    fun onReadyToPayWithReward()
}

class RewardBalance {

    companion object {
        const val KEY_REWARD = "REWARD"
        const val KEY_DIGEST = "DIGEST"

        fun Sample(context: Context):RewardBalance {
            val reward = RewardBalance(context, "ca-app-pub-3940256099942544~3347511713", "COINS")
            reward.videoAdUnitId = "ca-app-pub-3940256099942544/5224354917"
            reward.bannerAdUnitId = "ca-app-pub-3940256099942544/1033173712"
            return reward
        }
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
        rewardHistory { date, reward, usedHistory, remain, used->
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

    protected fun rewardHistory(cursor:(date:Long, numberOfReward:Int, usedHistory:JSONArray, remain:Int, used:Int) -> Unit) {
        val jsonString = pref.getString(KEY_REWARD, "[]") ?: "[]";
        val digest = pref.getString(KEY_DIGEST, "") ?: "";
        if (digest(jsonString).equals(digest, true).not()) {
            return
        }
        val array = try {
            JSONArray(jsonString)
        } catch(e:Throwable) {
            JSONArray()
        }
        log(array.toString(4));
        val length = array.length()
        for (i in 0 until length) {
            try {
                val dateAndValue = array.getJSONArray(i)
                val date = dateAndValue.getLong(0);
                val reward = dateAndValue.getInt(1);
                val usedHistory = dateAndValue.getJSONArray(2);
                val usedHistoryLength = usedHistory.length()
                var used = 0;
                for (j in 0 until usedHistoryLength) {
                    val usedDateAndValue = array.getJSONArray(i)
                    val usedDate = usedDateAndValue.getLong(0);
                    val usedValue = usedDateAndValue.getInt(1);
                    used += usedValue
                }
                val remain = reward - used
                if ((Date().time - date) < (3600 * 1000 * rewardExpiryHour)) {
                    cursor(date, reward, usedHistory, remain, used);
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
        rewardHistory { date, reward, usedHistory, remain, used->
            newArray.put(JSONArray().put(date).put(reward).put(usedHistory))
        }
        newArray.put(JSONArray().put(Date().time).put(amount).put(JSONArray()))

        val newJsonString = newArray.toString()
        edit.putString(KEY_REWARD, newJsonString)
        edit.putString(KEY_DIGEST, digest(newJsonString))

        edit.commit()
    }

    @SuppressLint("ApplySharedPref")
    protected fun useReward() {
        val edit = pref.edit()
        var notUsed = true;
        val newArray = JSONArray()
        rewardHistory { date, reward, usedHistory, remain, used->
            if (remain > 0 && notUsed) {
                usedHistory.put(
                    JSONArray().put(Date().time).put(1)
                )
                notUsed = false;
            }
            newArray.put(JSONArray().put(date).put(reward).put(usedHistory))
        }
        val newJsonString = newArray.toString()
        edit.putString(KEY_REWARD, newJsonString)
        edit.putString(KEY_DIGEST, digest(newJsonString))

        edit.commit()
    }

    protected var videoAd: RewardedVideoAd
    protected var bannerAd: InterstitialAd
    protected val videoAdStatus = AdStatus.default()
    protected val bannerAdStatus = AdStatus.default()

    protected fun readyForAds() {
        if (videoAdStatus.failed.not()) {
            videoAd.userId = userId
            videoAd.rewardedVideoAdListener = object : RewardedVideoAdListener {
                override fun onRewardedVideoAdLoaded() {
                    log("onRewardedVideoAdLoaded")
                    videoAdStatus.loaded = true
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
                    videoAdStatus.closed = true
                    if (hasReward()) {
                        listener?.onReadyToPayWithReward()
                    } else {
                        showAdOrWait()
                    }
                }

                override fun onRewarded(rewardItem: RewardItem) {
                    log("onRewarded(" + rewardItem.type + ", " + rewardItem.amount + ")")
                    if (rewardItem.type.equals(rewardName, ignoreCase = true)) {
                        putReward(rewardItem.amount)
                    }
                    videoAdStatus.rewarded = true
                }

                override fun onRewardedVideoAdLeftApplication() {
                    log("onRewardedVideoAdLeftApplication")
                }

                override fun onRewardedVideoAdFailedToLoad(i: Int) {
                    log("onRewardedVideoAdFailedToLoad($i)")
                    videoAdStatus.failed = true
                    showAdOrWait()
                }

                override fun onRewardedVideoCompleted() {
                    log("onRewardedVideoCompleted")
                }
            }
            videoAdUnitId?.let { id -> videoAd.loadAd(id, AdRequest.Builder().build()) }

        }
        if (bannerAdStatus.failed.not()) {
            bannerAd.setImmersiveMode(true)
            bannerAd.adListener = object : AdListener() {
                override fun onAdClosed() {
                    log("onAdClosed")
                    if (hasReward()) {
                        listener?.onReadyToPayWithReward()
                    } else {
                        showAdOrWait()
                    }
                }

                override fun onAdFailedToLoad(i: Int) {
                    log("onAdFailedToLoad($i)")
                    bannerAdStatus.failed = true
                    showAdOrWait()
                }

                override fun onAdLeftApplication() {
                    log("onAdLeftApplication")
                }

                override fun onAdOpened() {
                    log("onAdOpened")
                    putReward(1)
                    bannerAdStatus.rewarded = true
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
        if (bannerAdStatus.failed && videoAdStatus.failed) {
            listener?.onFailedReadyForRewardEarning()
        }
    }

    protected fun showAdOrWait() {
        log("videoAd: " + if (videoAd.isLoaded) "Loaded" else "")
        log("bannerAd: " + if (bannerAd.isLoaded) "Loaded" else "")
        log("videoAdStatus: " + videoAdStatus.toString())
        log("bannerAdStatus: " + bannerAdStatus.toString())
        /*
        - videoAd -> Loading
        - bannerAd -> Loading
        - videoAd -> Loaded or Failed
        - bannerAd -> Loaded or Failed
         */
        if (videoAd.isLoaded && videoAdStatus.closedOrFailed().not()) {
            log("videoAd.isLoaded")
            if (!showAdWhenLoaded) {
                listener?.onReadyForRewardEarning()
                return
            }
            videoAd.show()
            return
        }
        if (videoAdStatus.closedOrFailed() && bannerAd.isLoaded && bannerAdStatus.closedOrFailed().not()) {
            log("bannerAd.isLoaded")
            if (!showAdWhenLoaded) {
                listener?.onReadyForRewardEarning()
                return
            }
            bannerAd.show()
            return
        }
        if (videoAdStatus.closedOrFailed() && bannerAdStatus.closedOrFailed()) {
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