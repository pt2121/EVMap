package net.vonforst.evmap.api.availability

import com.facebook.stetho.okhttp3.StethoInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import net.vonforst.evmap.api.RateLimitInterceptor
import net.vonforst.evmap.api.await
import net.vonforst.evmap.api.equivalentPlugTypes
import net.vonforst.evmap.cartesianProduct
import net.vonforst.evmap.model.ChargeLocation
import net.vonforst.evmap.model.Chargepoint
import net.vonforst.evmap.viewmodel.Resource
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

interface AvailabilityDetector {
    suspend fun getAvailability(location: ChargeLocation): ChargeLocationStatus
}

@ExperimentalCoroutinesApi
abstract class BaseAvailabilityDetector(private val client: OkHttpClient) : AvailabilityDetector {
    protected val radius = 150  // max radius in meters

    protected suspend fun httpGet(url: String): String {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()

        if (!response.isSuccessful) throw IOException(response.message)

        val str = response.body!!.string()
        return str
    }

    protected fun getCorrespondingChargepoint(
        cps: Iterable<Chargepoint>, type: String, power: Double
    ): Chargepoint? {
        var filter = cps.filter {
            it.type == type
        }
        if (filter.size > 1) {
            filter = filter.filter {
                if (power > 0) {
                    it.power == power
                } else true
            }
            // TODO: handle not matching powers
            /*if (filter.isEmpty()) {
                filter = listOfNotNull(cps.minBy {
                    abs(it.power - power)
                })
            }*/
        }
        return filter.getOrNull(0)
    }

    companion object {
        internal fun matchChargepoints(
            connectors: Map<Long, Pair<Double, String>>,
            chargepoints: List<Chargepoint>
        ): Map<Chargepoint, Set<Long>> {
            // iterate over each connector type
            val types = connectors.map { it.value.second }.distinct().toSet()
            val equivalentTypes = types.map { equivalentPlugTypes(it).plus(it) }.cartesianProduct()
            val geTypes = chargepoints.map { it.type }.distinct().toSet()
            if (!equivalentTypes.any { it == geTypes }) throw AvailabilityDetectorException("chargepoints do not match")
            return types.flatMap { type ->
                // find connectors of this type
                val connsOfType = connectors.filter { it.value.second == type }
                // find powers this connector is available as
                val powers = connsOfType.map { it.value.first }.distinct().sorted()
                // find corresponding powers in GE data
                val gePowers =
                    chargepoints.filter { equivalentPlugTypes(it.type).any { it == type } }
                        .map { it.power }.distinct().sorted()

                // if the distinct number of powers is the same, try to match.
                if (powers.size == gePowers.size) {
                    gePowers.zip(powers).map { (gePower, power) ->
                        val chargepoint =
                            chargepoints.find { equivalentPlugTypes(it.type).any { it == type } && it.power == gePower }!!
                        val ids = connsOfType.filter { it.value.first == power }.keys
                        if (chargepoint.count != ids.size) {
                            throw AvailabilityDetectorException("chargepoints do not match")
                        }
                        chargepoint to ids
                    }
                } else if (powers.size == 1 && gePowers.size == 2
                    && chargepoints.sumBy { it.count } == connsOfType.size
                ) {
                    // special case: dual charger(s) with load balancing
                    // GoingElectric shows 2 different powers, NewMotion just one
                    val allIds = connsOfType.keys.toList()
                    var i = 0
                    gePowers.map { gePower ->
                        val chargepoint =
                            chargepoints.find { it.type in equivalentPlugTypes(type) && it.power == gePower }!!
                        val ids = allIds.subList(i, i + chargepoint.count).toSet()
                        i += chargepoint.count
                        chargepoint to ids
                    }
                    // TODO: this will not necessarily first fill up the higher-power chargepoint
                } else {
                    throw AvailabilityDetectorException("chargepoints do not match")
                }
            }.toMap()
        }
    }
}

data class ChargeLocationStatus(
    val status: Map<Chargepoint, List<ChargepointStatus>>,
    val source: String
) {
    fun applyFilters(connectors: Set<String>?, minPower: Int?): ChargeLocationStatus {
        val statusFiltered = status.filterKeys {
            (connectors == null || connectors.map {
                equivalentPlugTypes(it)
            }.any { equivalent -> it.type in equivalent })
                    && (minPower == null || it.power > minPower)
        }
        return this.copy(status = statusFiltered)
    }

    val totalChargepoints = status.map { it.key.count }.sum()
}

enum class ChargepointStatus {
    AVAILABLE, UNKNOWN, CHARGING, OCCUPIED, FAULTED
}

class AvailabilityDetectorException(message: String) : Exception(message)

private val cookieManager = CookieManager().apply {
    setCookiePolicy(CookiePolicy.ACCEPT_ALL)
}

private val okhttp = OkHttpClient.Builder()
    .addInterceptor(RateLimitInterceptor())
    .addNetworkInterceptor(StethoInterceptor())
    .readTimeout(10, TimeUnit.SECONDS)
    .connectTimeout(10, TimeUnit.SECONDS)
    .cookieJar(JavaNetCookieJar(cookieManager))
    .build()
val availabilityDetectors = listOf(
    NewMotionAvailabilityDetector(okhttp)
    /*ChargecloudAvailabilityDetector(
        okhttp,
        "606a0da0dfdd338ee4134605653d4fd8"
    ), // Maingau
    ChargecloudAvailabilityDetector(
        okhttp,
        "6336fe713f2eb7fa04b97ff6651b76f8"
    )  // SW Kiel*/
)

suspend fun getAvailability(charger: ChargeLocation): Resource<ChargeLocationStatus> {
    var value: Resource<ChargeLocationStatus>? = null
    withContext(Dispatchers.IO) {
        for (ad in availabilityDetectors) {
            try {
                value = Resource.success(ad.getAvailability(charger))
                break
            } catch (e: IOException) {
                value = Resource.error(e.message, null)
                e.printStackTrace()
            } catch (e: HttpException) {
                value = Resource.error(e.message, null)
                e.printStackTrace()
            } catch (e: AvailabilityDetectorException) {
                value = Resource.error(e.message, null)
                e.printStackTrace()
            }
        }
    }
    return value ?: Resource.error(null, null)
}