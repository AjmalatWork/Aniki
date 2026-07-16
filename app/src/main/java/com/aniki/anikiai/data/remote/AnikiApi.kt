package com.aniki.anikiai.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AnikiApi {
    @POST("enrich")
    suspend fun enrich(@Body request: EnrichRequestDto): Response<EnrichResponseDto>

    /** Extraction-only re-check of an article's OG/twitter:image -- never touches Gemini, used
     *  purely by the lazy thumbnail backfill (see ItemRepository.backfillThumbnails). */
    @POST("extract-thumbnail")
    suspend fun extractThumbnail(@Body request: ExtractThumbnailRequestDto): Response<ExtractThumbnailResponseDto>

    @GET("health")
    suspend fun health(): Response<Unit>

    @GET("sync")
    suspend fun pullSync(@Query("since") since: Long): Response<SyncPullResponse>

    @POST("sync")
    suspend fun pushSync(@Body request: SyncPushRequest): Response<SyncPushResponse>

    /** Raw JSON dump — shape is a server-side concern (see server/src/account/repo.ts), not a fixed DTO. */
    @GET("account/export")
    suspend fun exportAccount(): Response<ResponseBody>

    @DELETE("account")
    suspend fun deleteAccount(): Response<Unit>
}
