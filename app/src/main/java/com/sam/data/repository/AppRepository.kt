package com.sam.data.repository

import com.sam.data.model.CovidDaily
import com.sam.data.model.CovidDetail
import com.sam.data.model.CovidOverview

import com.sam.data.model.ghana.GhanaDaily
import com.sam.data.model.ghana.GhanaPerRegion
import com.sam.data.source.generated.AppGeneratedSource
import com.sam.data.source.pref.AppPrefSource
import com.sam.data.source.remote.AppRemoteSource
import com.sam.util.IncrementStatus
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.functions.Function3
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * fiificodes 2019-12-01.
 */

data class Result<out T>(
    val data: T? = null,
    val error: Throwable? = null
)

fun <T> Observable<T>.responseToResult(): Observable<Result<T>> {
    return map { it.asResult() }
        .onErrorReturn {
            when (it) {
                is HttpException,
                is SocketTimeoutException,
                is UnknownHostException -> {
                    it.asErrorResult()
                }
                else -> throw it
            }
        }
}

fun <T> T.asResult(): Result<T> = Result(data = this, error = null)
fun <T> Throwable.asErrorResult(): Result<T> = Result(data = null, error = this)

open class AppRepository constructor(
    private val api: AppRemoteSource,
    private val pref: AppPrefSource,
    private val generated: AppGeneratedSource
) : Repository {
    override fun overview(): Observable<Result<CovidOverview>> {
        val cacheOverview = getCacheOverview()
        val localObservable =
            if (cacheOverview != null) Observable.just(Result(cacheOverview, null))
            else Observable.empty()

        val remoteObservable = api.overview()
            .flatMap {
                setCacheOverview(it)
                Observable.just(it.asResult())
            }
            .onErrorResumeNext { t: Throwable ->
                return@onErrorResumeNext if (cacheOverview != null) Observable.just(
                    Result(
                        cacheOverview,
                        t
                    )
                )
                else Observable.error(t)
            }

        return Observable.concatArrayEager(localObservable, remoteObservable)
    }

    override fun daily(): Observable<Result<List<CovidDaily>>> {
        val cacheDaily = getCacheDaily()
        val localObservable = Observable.just(cacheDaily.asResult())

        val remoteObservable = api.daily()
            .flatMap {
                var latestRecovered = 0
                var latestConfirmed = 0
                val proceedData = it.map { covid ->
                    covid.incrementConfirmed =
                        when {
                            covid.deltaConfirmed > latestConfirmed -> IncrementStatus.INCREASE
                            covid.deltaConfirmed < latestConfirmed -> IncrementStatus.DECREASE
                            else -> IncrementStatus.FLAT
                        }
                    covid.incrementRecovered =
                        when {
                            covid.deltaRecovered > latestRecovered -> IncrementStatus.INCREASE
                            covid.deltaRecovered < latestRecovered -> IncrementStatus.DECREASE
                            else -> IncrementStatus.FLAT
                        }
                    latestRecovered = covid.deltaRecovered
                    latestConfirmed = covid.deltaConfirmed
                    covid
                }.toMutableList()
                proceedData.reverse()
                setCacheDaily(proceedData)
                Observable.just(proceedData.toList().asResult())
            }
            .onErrorResumeNext { t: Throwable ->
                return@onErrorResumeNext Observable.just(Result(cacheDaily, t))
            }

        return Observable.concatArrayEager(localObservable, remoteObservable)
    }

    override fun confirmed(): Observable<List<CovidDetail>> {
        val cacheConfirmed = getCacheConfirmed()
        val localObservable = if (cacheConfirmed != null) Observable.just(cacheConfirmed)
        else Observable.empty()

        val remoteObservable = api.confirmed()
            .flatMap {
                setCacheConfirmed(it)
                Observable.just(it)
            }


        return Observable.concatArrayEager(localObservable, remoteObservable)
    }

    override fun deaths(): Observable<List<CovidDetail>> {
        val cacheDeath = getCacheDeath()
        val localObservable = if (cacheDeath != null) Observable.just(cacheDeath)
        else Observable.empty()

        val remoteObservable = api.deaths()
            .flatMap {
                setCacheDeath(it)
                Observable.just(it)
            }


        return Observable.concatArrayEager(localObservable, remoteObservable)
    }

    override fun recovered(): Observable<List<CovidDetail>> {
        val cacheRecovered = getCacheRecovered()
        val localObservable = if (cacheRecovered != null) Observable.just(cacheRecovered)
        else Observable.empty()

        val remoteObservable = api.recovered()
            .flatMap {
                setCacheRecovered(it)
                Observable.just(it)
            }

        //Publish local recovered data first and after api call completed publish the latest recovered data from API
        //So the user does not have to go back and forth to get the latest update
        return Observable.concatArrayEager(localObservable, remoteObservable)
    }

    override fun country(id: String): Observable<CovidOverview> = api.country(id)
        .flatMap {
            setCacheCountry(it)
            Observable.just(it)
        }


    override fun fullStats(): Observable<List<CovidDetail>> {
        return Observable.zip(
            api.confirmed(),
            api.recovered(),
            api.deaths(),
            Function3<List<CovidDetail>, List<CovidDetail>, List<CovidDetail>, List<CovidDetail>> { t1, t2, t3 ->
                val result: MutableList<CovidDetail> = mutableListOf()
                t1.forEach { it1 ->
                    val t2Match = if (it1.regionState == null)
                        t2.firstOrNull { it.countryRegion == it1.countryRegion }
                    else t2.firstOrNull { it.regionState == it1.regionState }
                    val t3Match = if (it1.regionState == null)
                        t3.firstOrNull { it.countryRegion == it1.countryRegion }
                    else t3.firstOrNull { it.regionState == it1.regionState }

                    result.add(
                        it1.copy(
                            recovered = t2Match?.recovered ?: 0,
                            deaths = t3Match?.deaths ?: 0
                        )
                    )

                }
                result
            })
            .flatMap {
                setCacheFull(it)
                Observable.just(it)
            }
    }

    override fun putPinnedRegion(data: CovidDetail): Completable {
        return Completable.create {
            if (pref.setPrefCountry(data)) it.onComplete()
            else it.onError(Throwable("Not able to save"))
        }
    }

    override fun removePinnedRegion(): Completable {
        return Completable.create {
            if (pref.setPrefCountry(null)) it.onComplete()
            else it.onError(Throwable("Not able to remove"))
        }
    }

    override fun pinnedRegion(): Observable<Result<CovidDetail>> {
        val prefData = getCachePinnedRegion()
        return if (prefData != null) {
            confirmed()
                .map { stream ->
                    stream.first {
                        if (it.regionState != null) it.regionState == prefData.regionState
                        else it.countryRegion == prefData.countryRegion
                    }.asResult()
                }
                .onErrorResumeNext { t: Throwable ->
                    Observable.just(Result(prefData, t))
                }
        } else Observable.just(Result())
    }


    override fun getGhanaDaily(): Observable<List<GhanaDaily>> = api.ghanaDaily()
        .map { it.data }

    override fun getGhanaPerRegion(): Observable<List<GhanaPerRegion>> =
        api.ghanaPerRegion()
            .map { it.data }

    override fun getCachePinnedRegion(): CovidDetail? = pref.getPrefCountry()

    override fun getCacheOverview(): CovidOverview? = pref.getOverview()

    override fun getCacheDaily(): List<CovidDaily> = pref.getDaily()

    override fun getCacheConfirmed(): List<CovidDetail>? = pref.getConfirmed()

    override fun getCacheDeath(): List<CovidDetail>? = pref.getDeath()

    override fun getCacheRecovered(): List<CovidDetail>? = pref.getRecovered()

    override fun getCacheFull(): List<CovidDetail>? = pref.getFullStats()

    override fun getCacheCountry(id: String): CovidOverview? = pref.getCountry()

    private fun setCacheOverview(covidOverview: CovidOverview) = pref.setOverview(covidOverview)

    private fun setCacheDaily(covid: List<CovidDaily>) = pref.setDaily(covid)

    private fun setCacheConfirmed(covid: List<CovidDetail>) = pref.setConfirmed(covid)

    private fun setCacheDeath(covid: List<CovidDetail>) = pref.setDeath(covid)

    private fun setCacheRecovered(covid: List<CovidDetail>) = pref.setRecovered(covid)

    private fun setCacheCountry(covid: CovidOverview) = pref.setCountry(covid)

    private fun setCacheFull(covid: List<CovidDetail>) = pref.setFullStats(covid)

    override fun getPerCountryItem() = generated.getPerCountryItem()

}