package uk.ryanwong.giphytrending.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.reactivex.Completable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subscribers.DisposableSubscriber
import timber.log.Timber
import uk.ryanwong.giphytrending.BuildConfig
import uk.ryanwong.giphytrending.GiphyApplication
import uk.ryanwong.giphytrending.data.source.local.GiphyDatabase
import uk.ryanwong.giphytrending.data.source.local.toDomainModelList
import uk.ryanwong.giphytrending.data.source.local.toTrendingEntityList
import uk.ryanwong.giphytrending.data.source.network.GiphyApi
import uk.ryanwong.giphytrending.data.source.network.model.TrendingNetworkModel
import uk.ryanwong.giphytrending.domain.model.GiphyImageItemDomainModel
import javax.inject.Inject

class GiphyRepository {

    @Inject
    lateinit var giphyApiService: GiphyApi

    @Inject
    lateinit var giphyDatabase: GiphyDatabase

    private val _trendingList by lazy { MutableLiveData<List<GiphyImageItemDomainModel>>() }
    val trendingList: LiveData<List<GiphyImageItemDomainModel>>
        get() = _trendingList

    private val _showLoading by lazy { MutableLiveData<Boolean>() }
    val showLoading: LiveData<Boolean>
        get() = _showLoading

    private val _errorMessage by lazy { MutableLiveData<String>() }
    val errorMessage: LiveData<String>
        get() = _errorMessage

    init {
        GiphyApplication.appComponent.inject(this)
    }

    /***
     * Fetch and expose trending list
     * Repository currently retrieves cached data from local database.
     * This can be changed without acknowledging to the callers.
     */
    fun fetchTrending(): Disposable {
        return giphyDatabase.trendingDao()
            .queryData()
            .subscribeOn(Schedulers.io())
            .subscribe(
                { dataEntityList ->
                    if (dataEntityList != null) {
                        _errorMessage.postValue(null)
                        _trendingList.postValue(dataEntityList.toDomainModelList())
                    }
                },
                {
                    Timber.e("fetchTrending() - Database error: ${it.message}")
                    _errorMessage.postValue(
                        it?.message ?: "Error when retrieving data from the local storage."
                    )
                }
            )
    }

    /***
     * Request the repository to refresh and update the exposed trending list
     * Repository currently run RestAPI calls, cache data to local database,
     * and return the cached contents.
     */
    fun refreshTrending(): Disposable {
        return Completable.fromAction {
            _showLoading.postValue(true)
            // Mark existing contents dirty. After successful API call old entries will be removed
            giphyDatabase.trendingDao().markDirty()
        }.subscribeOn(Schedulers.io())
            .subscribe(
                {
                    Timber.v("refreshTrending() - mark dirty: success")
                    getTrendingFromNetwork()
                }, {
                    Timber.e("refreshTrending() -Database error when marking dirty bit: ${it.message}")
                    _showLoading.postValue(false)
                    _errorMessage.postValue(
                        it?.message ?: "Error when accessing to the local storage."
                    )
                }
            )
    }

    private fun getTrendingFromNetwork(): Disposable {
        return giphyApiService.getTrending(
            BuildConfig.GIPHY_API_KEY, BuildConfig.API_LIMIT,
            BuildConfig.API_RATING
        )
            .subscribeOn(Schedulers.io())
            .subscribeWith(cacheTrendingToDb())
    }

    private fun cacheTrendingToDb(): DisposableSubscriber<TrendingNetworkModel> {
        return object : DisposableSubscriber<TrendingNetworkModel>() {
            override fun onNext(trending: TrendingNetworkModel?) {
                if (trending != null) {
                    val dataList = trending.trendingData.toTrendingEntityList()
                    giphyDatabase.trendingDao().insertAllData(dataList)
                }
            }

            override fun onError(t: Throwable?) {
                Timber.e("cacheTrendingToDb(): error - ${t?.message}")
                _errorMessage.postValue(
                    t?.message ?: "Error when processing data returned from the server."
                )
                _showLoading.postValue(false)
            }

            override fun onComplete() {
                Timber.v("cacheTrendingToDb(): insertion completed")
                invalidateDirtyTrendingDb()
            }
        }
    }

    private fun invalidateDirtyTrendingDb(): Disposable {
        return Completable.fromAction {
            giphyDatabase.trendingDao().deleteDirty()
        }.subscribeOn(Schedulers.io())
            .subscribe({
                Timber.v("invalidateDirtyTrendingDb(): success")
                fetchTrending()
                _showLoading.postValue(false)
            },
                {
                    Timber.e("invalidateDirtyTrendingDb: error when invalidating dirty rows - ${it?.message}")
                    _showLoading.postValue(false)
                })
    }
}