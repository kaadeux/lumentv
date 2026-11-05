package com.lumentv.data.sports

import com.lumentv.core.RetryInterceptor
import com.lumentv.core.asHttp
import okhttp3.OkHttpClient

/** TV, phone and PC: the calendar over OkHttp, a "429" from TheSportsDB retried a little later. */
fun fixtureClient(http: OkHttpClient): FixtureClient =
    FixtureClient(http.newBuilder().addInterceptor(RetryInterceptor()).build().asHttp())
