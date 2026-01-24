package com.example.mtgdatasetcollector.data

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val MTG_HOST = "mtg.voiicr.com.br"
private const val MTG_PIN  = "sha256/sK897x7BYt48bmZwPNXA5I/cKNJBOHwc2BSXsjbwDvI="

fun buildPinnedClient(): OkHttpClient {
    val pinner = CertificatePinner.Builder()
        .add(MTG_HOST, MTG_PIN)
        .build()

    return OkHttpClient.Builder()
        .certificatePinner(pinner)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
}
