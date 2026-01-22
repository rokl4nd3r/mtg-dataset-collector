package com.example.mtgdatasetcollector.util

object AppConfig {
    const val DBG = true
    const val DBG_TAG = "MTGDatasetCollector"

    // Local staging (store-and-forward)
    const val STAGING_DIR = "staging"
    const val IMAGES_DIR = "images"
    const val META_DIR = "meta"

    // Upload
    // Troque quando o backend estiver pronto. Emulador: 10.0.2.2 aponta pro host.
    const val DATASET_UPLOAD_URL = "http://10.0.2.2:8000/dataset/upload"
    const val BEARER_TOKEN = "" // se quiser, põe o token aqui por enquanto

    const val MAX_UPLOAD_RETRIES = 5
}
