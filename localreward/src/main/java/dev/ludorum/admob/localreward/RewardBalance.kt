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
        private const val KEY_REWARD = "REWARD"
        private const val KEY_DIGEST = "DIGEST"
        const val AD_TYPE_VIDEO = "Video"
        const val AD_TYPE_BANNER = "Banner"

        fun Sample(context: Context):RewardBalance {
            val reward = RewardBalance(context, "ca-app-pub-3940256099942544~3347511713", "COINS")
            reward.videoAdUnitId = "ca-app-pub-3940256099942544/5224354917"
            reward.bannerAdUnitId = "ca-app-pub-3940256099942544/1033173712"
            return reward
        }
    }

    var debug = BuildConfig.DEBUG
    var listener:RewardBalanceListener? = null
    var userId:String? = null
    var videoAdUnitId:String? = null
    var bannerAdUnitId:String? = null
    var rewardExpiryHour: Int = 24

    protected val context: Context
    protected val pref: SharedPreferences
    protected val rewardName: String
    protected var showAdWhenLoaded = false

    constructor(context: Context, admobAppId:String, rewardName:String) {
        this.context = context
        this.rewardName = rewardName.toUpperCase()
        this.pref = context.getSharedPreferences("AD_REWARD_${this.rewardName}", Context.MODE_PRIVATE)
        
        MobileAds.initialize(context, admobAppId)
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
        initAds()
    }

    fun hasReward(): Boolean {
        return getNumberOfReward() > 0
    }

    fun getNumberOfReward(): Int {
        var total:Int = 0
        rewardHistory { date, reward, usedHistory, remain, used->
            total += remain
        }
        return total
    }

    fun pay(): Boolean {
        if (hasReward()) {
            useReward()
            return true
        }
        return false
    }

    fun pause() {
        this.videoAd?.pause(context)
    }
    fun resume() {
        this.videoAd?.resume(context)
    }
    fun destroy() {
        this.videoAd?.destroy(context)
    }

    protected fun digest(json:String):String {
        return Digest.md5Hex("$rewardName|$userId|$json")
    }

    fun getRewardHistory():JSONArray {
        val jsonString = pref.getString(KEY_REWARD, "[]") ?: "[]"
        val digest = pref.getString(KEY_DIGEST, "") ?: ""
        if (digest(jsonString).equals(digest, true).not()) {
            return JSONArray()
        }
        val array = try {
            JSONArray(jsonString)
        } catch(e:Throwable) {
            JSONArray()
        }
        return array
    }
    protected fun rewardHistory(cursor:(date:Long, numberOfReward:Int, usedHistory:JSONArray, remain:Int, used:Int) -> Unit) {
        val array = getRewardHistory()
        val length = array.length()
        for (i in 0 until length) {
            try {
                val dateAndValue = array.getJSONArray(i)
                val date = dateAndValue.getLong(0)
                val reward = dateAndValue.getInt(1)
                val usedHistory = dateAndValue.getJSONArray(2)
                val usedHistoryLength = usedHistory.length()
                var used = 0
                for (j in 0 until usedHistoryLength) {
                    val usedDateAndValue = array.getJSONArray(i)
                    val usedDate = usedDateAndValue.getLong(0)
                    val usedValue = usedDateAndValue.getInt(1)
                    used += usedValue
                }
                val remain = reward - used
                if ((Date().time - date) < (3600 * 1000 * rewardExpiryHour)) {
                    cursor(date, reward, usedHistory, remain, used)
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
        var notUsed = true
        val newArray = JSONArray()
        rewardHistory { date, reward, usedHistory, remain, used->
            if (remain > 0 && notUsed) {
                usedHistory.put(
                    JSONArray().put(Date().time).put(1)
                )
                notUsed = false
            }
            newArray.put(JSONArray().put(date).put(reward).put(usedHistory))
        }
        val newJsonString = newArray.toString()
        edit.putString(KEY_REWARD, newJsonString)
        edit.putString(KEY_DIGEST, digest(newJsonString))

        edit.commit()
    }

    protected var videoAd: RewardedVideoAd? = null
    protected var bannerAd: InterstitialAd? = null
    protected val videoAdStatus = AdStatus()
    protected val bannerAdStatus = AdStatus()


    protected fun initAds() {
        initVideoAd()
        initBannerAd()
        if (bannerAdStatus.failed && videoAdStatus.failed) {
            listener?.onFailedReadyForRewardEarning()
        }
    }
    protected fun initBannerAd() {
        if (bannerAdStatus.failed.not()) {
            bannerAdStatus.reset()
            bannerAd = InterstitialAd(context)
            bannerAd?.let {bannerAd ->
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
                        bannerAdStatus.loaded = true
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
        }
    }
    protected fun initVideoAd() {
        videoAd?.destroy(context)
        if (videoAdStatus.failed.not()) {
            videoAdStatus.reset()
            videoAd = MobileAds.getRewardedVideoAdInstance(context)
            videoAd?.let { videoAd ->
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
        }
    }

    fun status():Map<String, AdStatus> {
        log("videoAd: " + if (videoAd?.isLoaded ?: false) "Loaded" else "")
        log("bannerAd: " + if (bannerAd?.isLoaded ?: false) "Loaded" else "")
        log("videoAdStatus: " + videoAdStatus.toString())
        log("bannerAdStatus: " + bannerAdStatus.toString())
        return mapOf(
            AD_TYPE_VIDEO to videoAdStatus,
            AD_TYPE_BANNER to bannerAdStatus
        )
    }

    protected fun showAdOrWait() {
        status()
        if (status().entries.all { (_, status) -> status.failed  }) {
            listener?.onFailedReadyForRewardEarning()
            return@showAdOrWait
        }
        videoAd?.let { videoAd ->
            if (videoAd.isLoaded && videoAdStatus.closedOrFailed().not()) {
                log("videoAd.isLoaded")
                if (!showAdWhenLoaded) {
                    listener?.onReadyForRewardEarning()
                    return@showAdOrWait
                }
                videoAd.show()
                return@showAdOrWait
            }
        }
        bannerAd?.let { bannerAd ->
            if (videoAdStatus.closedOrFailed() && bannerAd.isLoaded && bannerAdStatus.closedOrFailed().not()) {
                log("bannerAd.isLoaded")
                if (!showAdWhenLoaded) {
                    listener?.onReadyForRewardEarning()
                    return@showAdOrWait
                }
                bannerAd.show()
                return@showAdOrWait
            }
        }


        if (videoAdStatus.closedOrFailed() && bannerAdStatus.closedOrFailed()) {
            if (hasReward()) {
                listener?.onReadyToPayWithReward()
            }
            return@showAdOrWait
        }
    }

    protected fun log(msg:String) {
        if (debug) {
            Log.d("AD/${rewardName}", msg)
        }
    }
}