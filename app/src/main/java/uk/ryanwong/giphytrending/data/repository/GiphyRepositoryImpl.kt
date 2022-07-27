package uk.ryanwong.giphytrending.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import uk.ryanwong.giphytrending.BuildConfig
import uk.ryanwong.giphytrending.data.source.local.GiphyDatabase
import uk.ryanwong.giphytrending.data.source.local.toDomainModelList
import uk.ryanwong.giphytrending.data.source.local.toTrendingEntityList
import uk.ryanwong.giphytrending.data.source.network.GiphyApi
import uk.ryanwong.giphytrending.data.source.network.model.TrendingNetworkResponse
import uk.ryanwong.giphytrending.di.IoDispatcher
import uk.ryanwong.giphytrending.domain.model.GiphyImageItemDomainModel
import javax.inject.Inject

class GiphyRepositoryImpl @Inject constructor(
    private val giphyApiService: GiphyApi,
    private val giphyDatabase: GiphyDatabase,
    @IoDispatcher private val dispatcher: CoroutineDispatcher
) : GiphyRepository {

    /**
     * Does not trigger network calls
     */
    override suspend fun fetchCachedTrending(): Result<List<GiphyImageItemDomainModel>> {
        return withContext(dispatcher) {
            try {
                Result.success(
                    value = giphyDatabase.trendingDao().queryData().toDomainModelList()
                )

            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (ex: Exception) {
                Timber.e("fetchTrending() - Database error: ${ex.message}")
                Result.failure(exception = ex)
            }
        }
    }

    /***
     * Trigger network calls, cache data to local database and return the results to caller
     */
    override suspend fun reloadTrending(apiMaxEntries: Int): Result<List<GiphyImageItemDomainModel>> {
        return withContext(dispatcher) {
            try {
                // Mark existing contents dirty. After successful API call old entries will be removed
                giphyDatabase.trendingDao().markDirty()
                Timber.v("reloadTrending() - mark dirty: success")

                val trendingNetworkResponse = getTrendingFromNetwork(apiMaxEntries)
                giphyDatabase.trendingDao()
                    .insertAllData(data = trendingNetworkResponse.trendingData.toTrendingEntityList())
                Timber.v("reloadTrending(): insertion completed")

                val invalidationResult = invalidateDirtyTrendingDb()
                if (invalidationResult.isFailure) {
                    // Error propagation is not ideal, but simplified in this demo app. Greg please don't blame me
                    Result.failure(
                        exception = invalidationResult.exceptionOrNull() ?: UnknownError()
                    )
                } else {
                    Result.success(
                        value = giphyDatabase.trendingDao().queryData().toDomainModelList()
                    )
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (ex: Exception) {
                Timber.e("refreshTrending() : ${ex.message}")
                Result.failure(exception = ex)
            }
        }
    }

    private suspend fun getTrendingFromNetwork(apiMaxEntries: Int): TrendingNetworkResponse {
        return withContext(dispatcher) {
            giphyApiService.getTrending(
                BuildConfig.GIPHY_API_KEY, apiMaxEntries,
                BuildConfig.API_RATING
            )
        }
    }

    private suspend fun invalidateDirtyTrendingDb(): Result<Unit> {
        return withContext(dispatcher) {
            try {
                giphyDatabase.trendingDao().deleteDirty()
                Timber.v("invalidateDirtyTrendingDb(): success")
                Result.success(Unit)

            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (ex: Exception) {
                Timber.e("refreshTrending() : ${ex.message}")
                Result.failure(exception = ex)
            }
        }
    }
}