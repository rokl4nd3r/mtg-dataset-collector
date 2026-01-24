package com.example.mtgdatasetcollector.util

import android.os.Build

object AppConfig {
    const val DBG = true
    const val DBG_TAG = "MTGDatasetCollector"

    const val STAGING_DIR = "staging"
    const val IMAGES_DIR = "images"
    const val META_DIR = "meta"

    const val DATASET_UPLOAD_URL = "https://mtg.voiicr.com.br:59890/dataset/upload"

    // COLE AQUI O TOKEN DO /opt/mtg-backend/.env (MTG_BEARER_TOKENS=...)
    const val BEARER_TOKEN = "5e75bcf6a0193ebc24e8a718f30f7958d5455ef7012880288a98394c26d7901f"

    val DEVICE_NAME: String = (Build.MODEL ?: "android").take(32)

    const val MAX_UPLOAD_RETRIES = 5
}
